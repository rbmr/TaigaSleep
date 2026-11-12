from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Iterator

import pytest

from taiga_worker.api import ApiClient, ApiError


class Recorder:
    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.answers: dict[tuple[str, str], tuple[int, bytes]] = {}


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
            status, payload = recorder.answers.get((self.command, self.path), (404, b""))
            self.send_response(status)
            if payload:
                self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if payload:
                self.wfile.write(payload)

        do_GET = do_POST = do_PUT = _answer

        def log_message(self, *args: Any) -> None:
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    try:
        yield ApiClient(f"http://127.0.0.1:{httpd.server_port}", timeout_s=5), recorder
    finally:
        httpd.shutdown()
        httpd.server_close()


def answer(recorder: Recorder, method: str, path: str, status: int, body: Any = None) -> None:
    payload = body if isinstance(body, bytes) else (json.dumps(body).encode() if body is not None else b"")
    recorder.answers[(method, path)] = (status, payload)


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


def test_errors_carry_the_status_code_and_message(server) -> None:
    client, recorder = server
    answer(
        recorder,
        "POST",
        "/api/collections/workers/auth-with-password",
        400,
        {"code": 400, "message": "Failed to authenticate.", "data": {}},
    )

    with pytest.raises(ApiError) as caught:
        client.login("w@example.com", "wrong")

    assert caught.value.status == 400
    assert caught.value.code == "400"
    assert str(caught.value) == "Failed to authenticate."


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
    answer(recorder, "GET", "/v1/openapi.json", 200, {"paths": {"/v1/analyses/claim": {"post": {}}}})

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
        {"code": "not_leased", "message": "lost"},
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
    answer(recorder, "GET", "/v1/recordings/rec%201", 200, {"id": "rec 1", "meta": {"format": "x"}})
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
        {"code": "not_found", "message": "file not found"},
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
