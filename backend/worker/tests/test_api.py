from __future__ import annotations

import gzip
import json
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Iterator

import pytest
from taiga_worker.api import ApiClient, ApiError


@dataclass
class Reply:
    status: int = 404
    body: bytes = b""
    headers: dict[str, str] = field(default_factory=dict)
    # Bytes really written when they are fewer than the declared Content-Length,
    # which is what a response that is cut off mid-body looks like.
    sent: bytes | None = None


class Recorder:
    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.answers: dict[tuple[str, str], Reply] = {}


@pytest.fixture()
def server() -> Iterator[tuple[ApiClient, Recorder]]:
    recorder = Recorder()

    class Handler(BaseHTTPRequestHandler):
        def _answer(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            recorder.requests.append(
                {
                    "method": self.command,
                    "path": self.path,
                    "headers": {key.lower(): value for key, value in self.headers.items()},
                    "body": json.loads(body) if body else None,
                }
            )
            reply = recorder.answers.get((self.command, self.path), Reply())
            self.send_response(reply.status)
            for name, value in reply.headers.items():
                self.send_header(name, value)
            if reply.body:
                self.send_header("Content-Length", str(len(reply.body)))
            self.end_headers()
            if reply.body:
                self.wfile.write(reply.body if reply.sent is None else reply.sent)

        do_GET = do_POST = do_PUT = _answer

        def log_message(self, *args: Any) -> None:
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(
        target=httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True
    )
    thread.start()
    try:
        yield ApiClient(f"http://127.0.0.1:{httpd.server_port}", timeout_s=5), recorder
    finally:
        httpd.shutdown()
        httpd.server_close()


def answer(
    recorder: Recorder,
    method: str,
    path: str,
    status: int,
    body: Any = None,
    headers: dict[str, str] | None = None,
    sent: bytes | None = None,
) -> None:
    payload = (
        body
        if isinstance(body, bytes)
        else (json.dumps(body).encode() if body is not None else b"")
    )
    recorder.answers[(method, path)] = Reply(status, payload, dict(headers or {}), sent)


def test_login_keeps_a_worker_token_and_sends_it_as_a_bearer(server) -> None:
    client, recorder = server
    answer(
        recorder,
        "POST",
        "/api/collections/workers/auth-with-password",
        200,
        {"token": "t1", "record": {"id": "w1", "email": "w@example.com"}},
    )
    answer(recorder, "PUT", "/v1/workers/me", 204)

    assert client.login("w@example.com", "pw") == "t1"
    client.announce("worker-1", "0.1.0", [{"id": "baseline"}])

    login, announce = recorder.requests
    assert login["path"] == "/api/collections/workers/auth-with-password"
    assert login["body"] == {"identity": "w@example.com", "password": "pw"}
    assert "authorization" not in login["headers"]
    assert announce["headers"]["authorization"] == "Bearer t1"
    assert announce["body"] == {
        "name": "worker-1",
        "version": "0.1.0",
        "algorithms": [{"id": "baseline"}],
    }


def test_login_refuses_an_answer_without_the_worker_account(server) -> None:
    client, recorder = server
    answer(
        recorder,
        "POST",
        "/api/collections/workers/auth-with-password",
        200,
        {"token": "t1"},
    )

    with pytest.raises(ApiError, match="worker account"):
        client.login("w@example.com", "pw")
    assert client.token is None


def test_errors_carry_the_status_message_and_code(server) -> None:
    client, recorder = server
    # PocketBase's auth routes report no code of ours; a custom /v1 route does.
    answer(
        recorder,
        "POST",
        "/api/collections/workers/auth-with-password",
        400,
        {"status": 400, "message": "Failed to authenticate.", "data": {}},
    )
    with pytest.raises(ApiError) as caught:
        client.login("w@example.com", "wrong")
    assert caught.value.status == 400
    assert caught.value.code is None
    assert str(caught.value) == "Failed to authenticate."

    client.token = "t1"
    answer(
        recorder,
        "POST",
        "/v1/analyses/claim",
        409,
        {"status": 409, "message": "the lease expired", "data": {"code": "not_leased"}},
    )
    with pytest.raises(ApiError) as caught:
        client.claim([], 60)
    assert (caught.value.status, caught.value.code) == (409, "not_leased")
    assert str(caught.value) == "the lease expired"


def test_an_unreachable_api_is_an_error_without_a_status() -> None:
    client = ApiClient("http://127.0.0.1:1", timeout_s=1)
    client.token = "t1"

    with pytest.raises(ApiError) as caught:
        client.claim([], 60)

    assert caught.value.status is None


def test_requests_need_a_token() -> None:
    with pytest.raises(ApiError) as caught:
        ApiClient("http://127.0.0.1:1").claim([], 60)
    assert caught.value.status == 401


def test_claim_returns_the_analysis_or_none(server) -> None:
    client, recorder = server
    client.token = "t1"
    analysis = {"id": "an1", "recording": "rec1", "algorithm": "baseline", "version": "1.1.0"}
    answer(recorder, "POST", "/v1/analyses/claim", 200, analysis)

    assert client.claim([{"id": "baseline", "version": "1.1.0"}], 90) == analysis
    assert recorder.requests[0]["body"] == {
        "algorithms": [{"id": "baseline", "version": "1.1.0"}],
        "lease_seconds": 90,
    }

    answer(recorder, "POST", "/v1/analyses/claim", 204)
    assert client.claim([], 90) is None


def test_renew_extends_the_lease_and_the_document_says_whether_it_can(server) -> None:
    client, recorder = server
    client.token = "t1"
    answer(recorder, "POST", "/v1/analyses/an1/lease", 200, {"id": "an1", "status": "running"})
    answer(
        recorder, "GET", "/v1/openapi.json", 200, {"paths": {"/v1/analyses/claim": {"post": {}}}}
    )

    assert client.supports_lease_renewal() is False

    answer(
        recorder,
        "GET",
        "/v1/openapi.json",
        200,
        {"paths": {"/v1/analyses/{id}/lease": {"post": {}}}},
    )
    assert client.supports_lease_renewal() is True
    assert client.renew("an1", 90)["status"] == "running"

    document, _, renew = recorder.requests
    assert "authorization" not in document["headers"]
    assert renew["headers"]["authorization"] == "Bearer t1"
    assert renew["body"] == {"lease_seconds": 90}


def test_complete_and_fail_post_to_the_analysis(server) -> None:
    client, recorder = server
    client.token = "t1"
    answer(recorder, "POST", "/v1/analyses/an1/complete", 200, {"id": "an1", "status": "succeeded"})
    answer(recorder, "POST", "/v1/analyses/an2/fail", 200, {"id": "an2", "status": "failed"})
    answer(
        recorder,
        "POST",
        "/v1/analyses/an3/complete",
        409,
        {"status": 409, "message": "lost", "data": {"code": "not_leased"}},
    )

    client.complete("an1", {"model": "baseline"})
    client.fail("an2", "boom", True)
    with pytest.raises(ApiError) as caught:
        client.complete("an3", {})

    complete, fail, _ = recorder.requests
    assert complete["body"] == {"result": {"model": "baseline"}}
    assert fail["body"] == {"error": "boom", "retryable": True}
    assert (caught.value.status, caught.value.code) == (409, "not_leased")


def test_recording_and_files(server) -> None:
    client, recorder = server
    client.token = "t1"
    answer(
        recorder,
        "GET",
        "/api/collections/recordings/records/rec%201",
        200,
        {"id": "rec 1", "meta": {"format": "x"}},
    )
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files",
        200,
        {"items": [{"name": "hr.log", "size": 8, "encoding": "gzip", "uncompressed_size": 20}]},
    )

    assert client.recording("rec 1")["meta"] == {"format": "x"}
    assert client.files("rec1")[0]["name"] == "hr.log"


def test_download_writes_the_bytes_and_asks_for_no_compression(server, tmp_path: Path) -> None:
    client, recorder = server
    client.token = "t1"
    body = bytes(range(256)) * 100
    answer(recorder, "GET", "/v1/recordings/rec1/files/hr.log", 200, body)
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files/ecg.log",
        404,
        {"status": 404, "message": "file not found", "data": {}},
    )

    client.download_file("rec1", "hr.log", tmp_path / "hr.log")
    with pytest.raises(ApiError) as caught:
        client.download_file("rec1", "ecg.log", tmp_path / "ecg.log")

    assert (tmp_path / "hr.log").read_bytes() == body
    request = recorder.requests[0]
    assert request["headers"]["authorization"] == "Bearer t1"
    # Without Accept-Encoding: gzip the API sends a compressed file inflated.
    assert "gzip" not in request["headers"].get("accept-encoding", "")
    assert caught.value.status == 404


def compressed_night(size: int = 1 << 20) -> bytes:
    # Not very compressible on purpose, so the stored form is not tiny.
    return bytes(index % 251 for index in range(size))


def test_a_compressed_download_is_requested_as_stored_and_inflated(server, tmp_path: Path) -> None:
    client, recorder = server
    client.token = "t1"
    payload = compressed_night()
    # The app uploads one member per chunk, so a stored file is many members.
    stored = b"".join(
        gzip.compress(payload[index : index + 65_536], mtime=0)
        for index in range(0, len(payload), 65_536)
    )
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files/hr.log",
        200,
        stored,
        headers={"Content-Encoding": "gzip"},
    )

    client.download_file(
        "rec1",
        "hr.log",
        tmp_path / "hr.log",
        encoding="gzip",
        uncompressed_size=len(payload),
    )

    assert (tmp_path / "hr.log").read_bytes() == payload
    assert recorder.requests[0]["headers"]["accept-encoding"] == "gzip"


def test_a_compressed_download_that_inflates_too_few_bytes_is_an_error(
    server, tmp_path: Path
) -> None:
    client, recorder = server
    client.token = "t1"
    payload = compressed_night(50_000)
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files/hr.log",
        200,
        gzip.compress(payload, mtime=0),
        headers={"Content-Encoding": "gzip"},
    )

    with pytest.raises(ApiError, match="not the advertised"):
        client.download_file(
            "rec1",
            "hr.log",
            tmp_path / "hr.log",
            encoding="gzip",
            uncompressed_size=len(payload) + 1,
        )


def test_a_compressed_download_that_inflates_too_many_bytes_is_an_error(
    server, tmp_path: Path
) -> None:
    client, recorder = server
    client.token = "t1"
    payload = compressed_night(50_000)
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files/hr.log",
        200,
        gzip.compress(payload, mtime=0),
        headers={"Content-Encoding": "gzip"},
    )

    with pytest.raises(ApiError, match="inflates past the advertised"):
        client.download_file(
            "rec1",
            "hr.log",
            tmp_path / "hr.log",
            encoding="gzip",
            uncompressed_size=len(payload) - 1,
        )


def test_a_cut_off_compressed_download_is_an_error(server, tmp_path: Path) -> None:
    client, recorder = server
    client.token = "t1"
    payload = compressed_night(50_000)
    stored = gzip.compress(payload, mtime=0)
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files/hr.log",
        200,
        stored,
        headers={"Content-Encoding": "gzip"},
        sent=stored[: len(stored) // 2],
    )

    with pytest.raises(ApiError, match="cannot download hr.log"):
        client.download_file(
            "rec1",
            "hr.log",
            tmp_path / "hr.log",
            encoding="gzip",
            uncompressed_size=len(payload),
        )


def test_a_corrupt_compressed_member_is_an_error(server, tmp_path: Path) -> None:
    client, recorder = server
    client.token = "t1"
    payload = compressed_night(50_000)
    stored = bytearray(gzip.compress(payload, mtime=0))
    stored[len(stored) // 2] ^= 0xFF
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files/hr.log",
        200,
        bytes(stored),
        headers={"Content-Encoding": "gzip"},
    )

    with pytest.raises(ApiError, match="cannot download hr.log"):
        client.download_file(
            "rec1",
            "hr.log",
            tmp_path / "hr.log",
            encoding="gzip",
            uncompressed_size=len(payload),
        )


def test_a_damaged_deflate_stream_is_an_error(server, tmp_path: Path) -> None:
    client, recorder = server
    client.token = "t1"
    payload = compressed_night(50_000)
    stored = bytearray(gzip.compress(payload, mtime=0))
    stored[12] ^= 0xFF
    answer(
        recorder,
        "GET",
        "/v1/recordings/rec1/files/hr.log",
        200,
        bytes(stored),
        headers={"Content-Encoding": "gzip"},
    )

    with pytest.raises(ApiError, match="cannot download hr.log"):
        client.download_file(
            "rec1",
            "hr.log",
            tmp_path / "hr.log",
            encoding="gzip",
            uncompressed_size=len(payload),
        )


def test_a_compressed_download_without_the_stored_encoding_is_an_error(
    server, tmp_path: Path
) -> None:
    client, recorder = server
    client.token = "t1"
    payload = compressed_night(1000)
    answer(recorder, "GET", "/v1/recordings/rec1/files/hr.log", 200, payload)

    with pytest.raises(ApiError, match="not as the gzip"):
        client.download_file(
            "rec1",
            "hr.log",
            tmp_path / "hr.log",
            encoding="gzip",
            uncompressed_size=len(payload),
        )


def test_a_compressed_download_without_an_advertised_size_is_an_error(
    server, tmp_path: Path
) -> None:
    client, _ = server
    client.token = "t1"

    with pytest.raises(ApiError, match="does not say"):
        client.download_file("rec1", "hr.log", tmp_path / "hr.log", encoding="gzip")
