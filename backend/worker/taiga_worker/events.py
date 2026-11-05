"""The worker's side of the event stream (``GET /v1/events``, docs/api.md).

The API tells a connected worker that an analysis was queued, so the worker claims it at
once instead of finding it on its next poll. Events are not replayed: whatever was queued
while the worker was not connected is found by the claim the worker makes after every
(re)connect, and a requeue that becomes due without any change to the data (a retry that
waited out its delay) has no event at all, which is why the poll interval stays as a
fallback.

The stream is only a doorbell. It never carries work and a failure of it never stops the
worker: :class:`EventStream` runs in a thread of its own, reconnects with a backoff and
does nothing but set an event for the worker loop to wait on.
"""

from __future__ import annotations

import http.client
import json
import logging
import socket
import threading
from typing import Any, Callable, Iterable, Iterator, TypeVar

from .api import ApiClient, ApiError

T = TypeVar("T")

# The API sends a comment every 20 seconds; a stream that stays quiet for three times
# that is dead.
READ_TIMEOUT_S = 60.0
MIN_BACKOFF_S = 1.0
MAX_BACKOFF_S = 30.0
# A line of an event is a few dozen bytes; this only bounds what a broken server can make
# the worker buffer.
MAX_LINE_BYTES = 64 * 1024


def parse_events(lines: Iterable[bytes | str]) -> Iterator[tuple[str, str]]:
    """Yield ``(event, data)`` for every event of a Server-Sent Events stream.

    ``lines`` are the lines of the stream, in order, with or without their line ending.
    Comments (``:``), ``id`` and ``retry`` fields and fields it does not know are skipped.
    An event ends at a blank line; one without a ``data`` field is not an event, and one
    the stream ended in the middle of is dropped. An event without an ``event`` field is
    named ``message``.
    """

    event = ""
    data: list[str] = []
    for raw in lines:
        line = raw.decode("utf-8", errors="replace") if isinstance(raw, bytes) else raw
        line = line.rstrip("\r\n")
        if not line:
            if data:
                yield event or "message", "\n".join(data)
            event, data = "", []
            continue
        if line.startswith(":"):
            continue
        field, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if field == "event":
            event = value
        elif field == "data":
            data.append(value)


class EventStream:
    """Listens to ``/v1/events`` in a background thread and sets ``wake`` when there is work.

    ``wake`` is set once on every successful (re)connect, because events are not
    replayed, and on every ``analysis`` event whose status is ``queued``. Any error or
    end of the stream leads to a new connection after a delay that starts at
    ``min_backoff_s``, doubles with every failed attempt up to ``max_backoff_s`` and starts
    over after a successful connection.

    ``call`` wraps the request that opens the stream, the way the worker wraps every
    other request: it logs in when there is no token and again when the stream answers
    ``401``. ``wait`` is how the thread sleeps between attempts; it returns True when it
    should stop (the default also wakes up on :meth:`stop`).
    """

    def __init__(
        self,
        api: ApiClient,
        wake: threading.Event,
        *,
        call: Callable[[Callable[[], T]], T] | None = None,
        logger: logging.Logger | None = None,
        read_timeout_s: float = READ_TIMEOUT_S,
        min_backoff_s: float = MIN_BACKOFF_S,
        max_backoff_s: float = MAX_BACKOFF_S,
        wait: Callable[[float], bool] | None = None,
    ) -> None:
        self.api = api
        self.wake = wake
        self.log = logger or logging.getLogger("taiga_worker")
        self.read_timeout_s = read_timeout_s
        self.min_backoff_s = min_backoff_s
        self.max_backoff_s = max_backoff_s
        self._call: Callable[[Callable[[], Any]], Any] = call or (lambda fn: fn())
        self._stop = threading.Event()
        self._wait = wait or self._stop.wait
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._response: Any = None
        self._connected_before = False
        self._warned = False

    # --- lifecycle ---

    def start(self) -> None:
        if self._thread is not None:
            return
        # A daemon thread: if it cannot be interrupted (see _interrupt) it must still
        # not keep a stopping worker alive.
        self._thread = threading.Thread(target=self._run, name="taiga-events", daemon=True)
        self._thread.start()

    def stop(self, timeout_s: float = 2.0) -> None:
        """Stop listening; returns once the thread is gone or after ``timeout_s``."""

        self._stop.set()
        self._interrupt()
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout_s)

    @property
    def stopping(self) -> bool:
        return self._stop.is_set()

    def _interrupt(self) -> None:
        """Make a read that is blocked on the open stream return at once.

        Closing the response would wait for the blocked read, so the socket under it is
        shut down instead: the read then sees the end of the stream and the thread
        closes the response itself. Where the socket cannot be found the read simply
        ends at the read timeout.
        """

        with self._lock:
            response = self._response
        sock = getattr(getattr(getattr(response, "fp", None), "raw", None), "_sock", None)
        if sock is None:
            return
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    # --- the thread ---

    def _run(self) -> None:
        delay = self.min_backoff_s
        while not self._stop.is_set():
            error: Exception | None = None
            try:
                connected = self._session()
            except Exception as exc:  # noqa: BLE001 - nothing here may stop the worker
                connected, error = False, exc
            if self._stop.is_set():
                return

            if connected:
                delay = self.min_backoff_s
                self.log.debug("the event stream ended; reconnecting in %ss", delay)
            elif not self._warned:
                self._warned = True
                self.log.warning(
                    "cannot listen for new analyses (%s); retrying in %ss and polling meanwhile",
                    error or "the stream was refused",
                    delay,
                )
            else:
                self.log.debug("event stream unavailable (%s); retrying in %ss", error, delay)

            if self._wait(delay):
                return
            if not connected:
                delay = min(delay * 2, self.max_backoff_s)

    def _session(self) -> bool:
        """Listen on one connection. Returns whether the connection was made.

        Errors before the connection is made are raised; once it is made, whatever ends
        it only ends the session.
        """

        response = self._call(lambda: self.api.open_events(self.read_timeout_s))
        with self._lock:
            self._response = response
        connected = False
        try:
            if self._stop.is_set():
                return False
            content_type = str(response.headers.get("Content-Type") or "")
            if not content_type.lower().startswith("text/event-stream"):
                # Some proxy or captive portal answering for the API; not a stream.
                raise ApiError(f"/v1/events did not answer with an event stream ({content_type or 'no type'})")

            connected = True
            if self._connected_before and self._warned:
                self.log.info("listening for new analyses again")
            elif not self._connected_before:
                self.log.info("listening for new analyses (event stream connected)")
            else:
                self.log.debug("event stream connected")
            self._connected_before = True
            self._warned = False
            # Nothing was replayed: whatever was queued meanwhile is found by a claim.
            self.wake.set()

            for event, data in parse_events(self._lines(response)):
                self._handle(event, data)
        except (OSError, http.client.HTTPException, ValueError) as exc:
            if connected:
                if not self._stop.is_set():
                    self.log.debug("the event stream broke: %s", exc)
            else:
                raise
        finally:
            with self._lock:
                self._response = None
            try:
                response.close()
            except Exception:  # noqa: BLE001 - closing a broken stream may fail
                pass
        return connected

    @staticmethod
    def _lines(response: Any) -> Iterator[bytes]:
        while True:
            line = response.readline(MAX_LINE_BYTES)
            if not line:
                return
            yield line

    def _handle(self, event: str, data: str) -> None:
        if event != "analysis":
            return
        try:
            payload = json.loads(data)
        except ValueError:
            self.log.debug("ignoring an analysis event that is not JSON")
            return
        if isinstance(payload, dict) and payload.get("status") == "queued":
            self.wake.set()
