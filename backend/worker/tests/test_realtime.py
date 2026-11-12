from __future__ import annotations

import threading
import time
from typing import Any, Callable, Iterator

import pytest

from taiga_worker.api import ApiClient, ApiError
from taiga_worker.realtime import MAX_LINE_BYTES, Realtime, parse_events
from tests.realtime_server import (
    TOPIC,
    RealtimeServer,
    Plan,
    change,
    message,
    queued,
    wait_until,
)

# --- the parser ---


def parsed(text: str | bytes) -> list[tuple[str, str]]:
    raw = text if isinstance(text, bytes) else text.encode()
    return list(parse_events(raw.splitlines(keepends=True)))


def test_a_message_has_a_name_and_data_and_ends_at_a_blank_line() -> None:
    assert parsed('event: analyses/*\ndata: {"action": "create"}\n\n') == [
        ("analyses/*", '{"action": "create"}')
    ]


def test_comments_and_fields_it_does_not_know_are_skipped_inside_a_message() -> None:
    text = ": a comment\nid: 7\nretry: 100\nevent: analyses/*\nflavour: salty\ndata: x\n\n"
    assert parsed(text) == [("analyses/*", "x")]


def test_several_messages_follow_each_other_and_do_not_leak_into_one_another() -> None:
    text = "event: one\ndata: a\n\ndata: b\n\nevent: three\ndata: c\n\n"
    assert parsed(text) == [("one", "a"), ("message", "b"), ("three", "c")]


def test_data_lines_are_joined_with_a_newline() -> None:
    assert parsed("data: a\ndata:\ndata: b\n\n") == [("message", "a\n\nb")]


def test_only_one_space_after_the_colon_is_dropped() -> None:
    assert parsed("event:analyses/*\ndata:  two spaces\n\n") == [("analyses/*", " two spaces")]


def test_carriage_returns_are_line_endings_too() -> None:
    assert parsed("event: analyses/*\r\ndata: x\r\n\r\n") == [("analyses/*", "x")]


def test_a_message_without_data_and_one_cut_short_are_not_messages() -> None:
    assert parsed("event: analyses/*\n\n") == []
    assert parsed("event: analyses/*\ndata: half") == []
    assert parsed("event: analyses/*\ndata: whole\n") == []


def test_bytes_that_are_not_utf8_do_not_raise() -> None:
    assert parsed(b"event: analyses/*\ndata: \xff\xfe\n\n") == [("analyses/*", "��")]


# --- the stream ---


class Wake(threading.Event):
    """A wake event that counts how often it was rung."""

    def __init__(self) -> None:
        super().__init__()
        self.rings = 0

    def set(self) -> None:
        self.rings += 1
        super().set()


@pytest.fixture()
def server() -> Iterator[RealtimeServer]:
    stub = RealtimeServer()
    try:
        yield stub
    finally:
        stub.close()


@pytest.fixture()
def streams() -> Iterator[list[Realtime]]:
    started: list[Realtime] = []
    try:
        yield started
    finally:
        for stream in started:
            stream.stop()


def listen(
    server: RealtimeServer,
    streams: list[Realtime],
    *,
    token: str | None = "t1",
    call: Callable[[Callable[[], Any]], Any] | None = None,
    start: bool = True,
    **options: Any,
) -> tuple[Realtime, Wake]:
    options.setdefault("min_backoff_s", 0.01)
    options.setdefault("max_backoff_s", 0.05)
    api = ApiClient(server.url, timeout_s=5)
    api.token = token
    wake = Wake()
    stream = Realtime(api, wake, call=call, **options)
    streams.append(stream)
    if start:
        stream.start()
    return stream, wake


def test_connecting_subscribes_the_worker_and_rings_it_once(server, streams) -> None:
    _, wake = listen(server, streams)

    assert wake.wait(5)
    assert server.wait_for_connections(1)
    (subscription,) = server.wait_for_subscriptions(1)
    request = server.requests[0]
    assert request["token"] == "t1"
    assert request["accept"] == "text/event-stream"
    # A stream is never compressed.
    assert "gzip" not in (request["accept_encoding"] or "")
    assert subscription["token"] == "t1"
    assert subscription["clientId"] == "client-1"
    assert subscription["subscriptions"] == [TOPIC]
    assert wake.rings == 1


def test_only_a_queued_analysis_rings_the_worker(server, streams) -> None:
    _, wake = listen(server, streams)
    (connection,) = server.wait_for_connections(1)
    assert wake.wait(5)
    wake.clear()

    connection.send(change("update", id="a1", recording="r1", status="running"))
    connection.send(change("update", id="a1", recording="r1", status="succeeded"))
    connection.send(change("delete", id="a1", recording="r1", status="failed"))
    connection.send(message("recordings/*", "create", {"id": "r1"}))
    connection.send(message("something/*", "create", {"id": "x", "status": "queued"}))
    connection.send(b": a comment\n\n")
    connection.send(b"event: analyses/*\ndata: this is not json\n\n")
    connection.send(b'event: analyses/*\ndata: ["queued"]\n\n')
    connection.send(b'event: analyses/*\ndata:{"record": {"status": "running"}}\n\n')
    connection.send(queued("a2"))

    assert wake.wait(5)
    assert wake.rings == 2  # connecting and the one queued analysis
    wake.clear()
    connection.send(queued("a3"))
    assert wake.wait(5)
    assert wake.rings == 3


def test_a_stream_that_ends_is_reconnected_and_subscribed_again(server, streams) -> None:
    server.plans = [Plan(then="end")]
    _, wake = listen(server, streams)

    server.wait_for_connections(2)
    first, second = server.wait_for_subscriptions(2)

    assert first["clientId"] == "client-1"
    assert second["clientId"] == "client-2"
    assert wait_until(lambda: wake.rings == 2)


def test_a_stream_that_is_cut_off_is_reconnected(server, streams) -> None:
    server.plans = [Plan(then="cut")]
    _, wake = listen(server, streams)

    server.wait_for_connections(2)
    server.wait_for_subscriptions(2)

    assert wait_until(lambda: wake.rings == 2)


def test_a_stream_the_api_refuses_is_tried_again(server, streams) -> None:
    server.plans = [Plan(status=503, content_type="application/json", body=b"{}"), Plan(status=404)]
    _, wake = listen(server, streams)

    server.wait_for_connections(1)

    assert wait_until(lambda: wake.rings == 1)
    assert len(server.requests) == 3
    assert wake.rings == 1  # a refused stream is no connection


def test_the_delay_doubles_up_to_the_maximum_and_starts_over_after_a_connection(server, streams) -> None:
    refused = Plan(status=503, content_type="application/json", body=b"{}")
    server.plans = [refused] * 5 + [Plan(then="end")] + [refused] * 2
    delays: list[float] = []

    def wait(seconds: float) -> bool:
        delays.append(seconds)
        return False

    _, wake = listen(server, streams, min_backoff_s=1.0, max_backoff_s=8.0, wait=wait)

    server.wait_for_connections(2)  # the one that ended, and the open one after the refusals
    assert wait_until(lambda: len(delays) == 8)
    assert delays == [1, 2, 4, 8, 8, 1, 1, 2]
    assert wake.rings == 2


def test_something_that_answers_but_is_no_event_stream_is_not_a_connection(server, streams) -> None:
    server.plans = [Plan(content_type="text/html", body=b"<html>log in to the wifi</html>")]
    delays: list[float] = []

    def wait(seconds: float) -> bool:
        delays.append(seconds)
        return False

    _, wake = listen(server, streams, min_backoff_s=1.0, wait=wait)

    server.wait_for_connections(1)
    assert wait_until(lambda: len(server.requests) == 2)
    assert delays == [1.0]
    assert wake.rings == 1  # only the second try was a stream


def test_a_stream_without_a_token_is_refused_and_the_worker_is_not_rung(server, streams) -> None:
    _, wake = listen(server, streams, token=None)

    time.sleep(0.3)

    assert server.requests == []
    assert server.subscriptions == []
    assert wake.rings == 0


def test_a_rejected_token_is_replaced_through_call_and_the_stream_opened_again(server, streams) -> None:
    server.revoked.add("stale")
    holder: dict[str, ApiClient] = {}

    def call(fn: Callable[[], Any]) -> Any:
        try:
            return fn()
        except ApiError as exc:
            if exc.status != 401:
                raise
            holder["api"].token = "fresh"
            return fn()

    stream, wake = listen(server, streams, token="stale", call=call, start=False)
    holder["api"] = stream.api
    stream.start()

    assert wake.wait(5)
    assert [request["token"] for request in server.requests] == ["stale", "fresh"]


def test_without_call_a_rejected_token_is_just_a_failed_attempt(server, streams) -> None:
    server.revoked.add("stale")
    _, wake = listen(server, streams, token="stale")

    assert wait_until(lambda: len(server.requests) >= 3)
    assert wake.rings == 0


def test_a_connection_that_goes_quiet_is_noticed_and_replaced(server, streams) -> None:
    # PocketBase closes an idle connection; this is the same rule, sped up.
    _, wake = listen(server, streams, read_timeout_s=0.3)

    started = time.monotonic()
    server.wait_for_connections(2)

    assert time.monotonic() - started < 3
    assert wait_until(lambda: wake.rings == 2)


def test_stopping_ends_a_read_that_is_blocked_at_once(server, streams) -> None:
    stream, _ = listen(server, streams, read_timeout_s=60)
    (connection,) = server.wait_for_connections(1)
    time.sleep(0.1)  # the thread is now reading, with a minute to go

    started = time.monotonic()
    stream.stop(timeout_s=5)

    assert time.monotonic() - started < 1.5
    assert connection.gone.wait(2), "the stream was left open"
    assert not stream._thread.is_alive()  # type: ignore[union-attr]


def test_stopping_during_the_wait_between_attempts_is_prompt(server, streams) -> None:
    server.plans = [Plan(then="end")]
    stream, _ = listen(server, streams, min_backoff_s=30.0, max_backoff_s=30.0)
    server.wait_for_connections(1)
    time.sleep(0.1)  # now waiting thirty seconds to reconnect

    started = time.monotonic()
    stream.stop(timeout_s=5)

    assert time.monotonic() - started < 1.5
    assert not stream._thread.is_alive()  # type: ignore[union-attr]
    assert len(server.requests) == 1


def test_a_stop_before_the_stream_was_opened_leaves_nothing_open(server, streams) -> None:
    stream, _ = listen(server, streams)
    stream.stop(timeout_s=5)

    assert not stream._thread.is_alive()  # type: ignore[union-attr]
    for connection in server.connections:
        assert connection.gone.wait(2)


def test_garbage_does_not_stop_the_stream(server, streams) -> None:
    _, wake = listen(server, streams)
    (connection,) = server.wait_for_connections(1)
    assert wake.wait(5)
    wake.clear()

    connection.send(b"\xff\xfe\x00\x01 not a message at all\n\n")
    connection.send(b"data: " + b"x" * (MAX_LINE_BYTES + 1) + b"\n\n")  # longer than a line may be
    connection.send(b'event: analyses/*\ndata: {"record": {"status": "queued"\n\n')  # truncated JSON
    connection.send(queued())

    assert wake.wait(5)
    assert len(server.requests) == 1  # it never had to reconnect


def test_an_unreachable_api_is_retried_and_never_raises(streams) -> None:
    delays: list[float] = []
    holder: dict[str, Realtime] = {}

    def wait(seconds: float) -> bool:
        delays.append(seconds)
        if len(delays) == 4:
            holder["stream"].stop()
        return holder["stream"].stopping

    api = ApiClient("http://127.0.0.1:1", timeout_s=1)
    api.token = "t1"
    wake = Wake()
    stream = Realtime(api, wake, min_backoff_s=1.0, max_backoff_s=4.0, wait=wait)
    holder["stream"] = stream
    streams.append(stream)
    stream.start()

    assert wait_until(lambda: not stream._thread.is_alive())  # type: ignore[union-attr]
    assert delays == [1, 2, 4, 4]
    assert wake.rings == 0


def test_a_failure_is_logged_once_as_a_warning_and_recovery_once_as_info(server, streams, caplog) -> None:
    refused = Plan(status=503, content_type="application/json", body=b"{}")
    server.plans = [refused] * 3
    with caplog.at_level("DEBUG", logger="taiga_worker"):
        _, wake = listen(server, streams)
        assert wake.wait(5)

    warnings = [r for r in caplog.records if r.levelname == "WARNING"]
    assert len(warnings) == 1
    assert "polling meanwhile" in warnings[0].getMessage()
    assert any(r.levelname == "INFO" for r in caplog.records)
    assert not [r for r in caplog.records if r.levelno >= 40]
