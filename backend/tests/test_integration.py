"""End-to-end tests for the PocketBase hooks and the worker.

These tests need a PocketBase binary. Point POCKETBASE_BIN at it, or put
`pocketbase` on PATH. The suite starts a real server in a temporary data
directory, applies the migrations, and exercises the HTTP API.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterator

import pytest

from tests import recording_fixture as fx

BACKEND_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = BACKEND_DIR.parent
WORKER_DIR = BACKEND_DIR / "worker"

ADMIN_EMAIL = "admin@example.com"
ADMIN_PASSWORD = "admin-password-123"
USER_A_EMAIL = "a@example.com"
USER_B_EMAIL = "b@example.com"
USER_PASSWORD = "user-password-123"
WORKER_EMAIL = "worker@example.com"
WORKER_PASSWORD = "worker-password-123"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class Backend:
    """A running API server and the records the tests share."""

    def __init__(self, binary: str) -> None:
        self.binary = binary
        self.tmpdir = tempfile.mkdtemp(prefix="taiga-backend-test-")
        self.base_url = f"http://127.0.0.1:{free_port()}"
        self.server: subprocess.Popen[bytes] | None = None
        self.log_path = Path(self.tmpdir) / "pocketbase.log"
        self.log_handle = self.log_path.open("wb")
        self.admin_token = ""
        self.token_a = ""
        self.token_b = ""
        self.worker_token = ""
        self.user_a_id = ""
        self.user_b_id = ""
        self.worker_id = ""
        self.session_a_id = ""

        try:
            self._start()
            self._seed()
        except Exception:
            self.dump_log()
            self.close()
            raise

    def _start(self) -> None:
        data_dir = Path(self.tmpdir) / "pb_data"
        subprocess.run(
            [self.binary, "superuser", "upsert", ADMIN_EMAIL, ADMIN_PASSWORD, "--dir", str(data_dir)],
            check=True,
            capture_output=True,
        )

        self.server = subprocess.Popen(
            [
                self.binary,
                "serve",
                "--http",
                self.base_url.replace("http://", ""),
                "--dir",
                str(data_dir),
                "--automigrate=false",
            ],
            stdout=self.log_handle,
            stderr=subprocess.STDOUT,
        )

        self.wait_for_health()

    def _seed(self) -> None:
        self.admin_token = self.login("_superusers", ADMIN_EMAIL, ADMIN_PASSWORD)
        self.user_a_id = self.create_record(
            "users",
            {"email": USER_A_EMAIL, "password": USER_PASSWORD, "passwordConfirm": USER_PASSWORD},
        )["id"]
        self.user_b_id = self.create_record(
            "users",
            {"email": USER_B_EMAIL, "password": USER_PASSWORD, "passwordConfirm": USER_PASSWORD},
        )["id"]
        self.worker_id = self.create_record(
            "workers",
            {
                "email": WORKER_EMAIL,
                "password": WORKER_PASSWORD,
                "passwordConfirm": WORKER_PASSWORD,
                "name": "integration",
            },
        )["id"]

        self.token_a = self.login("users", USER_A_EMAIL, USER_PASSWORD)
        self.token_b = self.login("users", USER_B_EMAIL, USER_PASSWORD)
        self.worker_token = self.login("workers", WORKER_EMAIL, WORKER_PASSWORD)

        session = self.create_record(
            "sessions",
            {
                "owner": self.user_a_id,
                "device": "Polar H10",
                "started_at": "2026-01-01 22:00:00.000Z",
                "ended_at": "2026-01-02 06:00:00.000Z",
                "duration_s": 28800,
                "state": "stopped",
            },
            token=self.token_a,
        )
        self.session_a_id = session["id"]

    def close(self) -> None:
        if self.server is not None:
            self.server.terminate()
            try:
                self.server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.server.kill()
                self.server.wait(timeout=5)
            self.server = None
        if not self.log_handle.closed:
            self.log_handle.close()

        if os.environ.get("TAIGA_KEEP_TEST_DATA"):
            print(f"\nTest data kept in {self.tmpdir}", file=sys.stderr)
        elif self.tmpdir:
            shutil.rmtree(self.tmpdir, ignore_errors=True)

    def dump_log(self) -> None:
        if self.log_path.exists():
            print("\n--- pocketbase log ---", file=sys.stderr)
            print(self.log_path.read_text(errors="replace")[-8000:], file=sys.stderr)

    def wait_for_health(self) -> None:
        deadline = time.monotonic() + 25
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            try:
                status, _ = self.request("GET", "/api/taiga/health")
                if status == 200:
                    return
            except Exception as exc:  # noqa: BLE001 - only used for retries
                last_error = exc
            if self.server and self.server.poll() is not None:
                break
            time.sleep(0.2)
        self.dump_log()
        raise RuntimeError(f"server did not become healthy: {last_error}")

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        token: str | None = None,
        expected: int | tuple[int, ...] = 200,
    ) -> tuple[int, Any]:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = token

        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status = response.status
                raw = response.read()
        except urllib.error.HTTPError as exc:
            status = exc.code
            raw = exc.read()

        payload = json.loads(raw) if raw else None
        allowed = (expected,) if isinstance(expected, int) else expected
        if status not in allowed:
            raise AssertionError(f"{method} {path} returned {status}, expected {allowed}: {payload}")
        return status, payload

    def request_raw(
        self,
        method: str,
        path: str,
        data: bytes,
        token: str | None = None,
        expected: int | tuple[int, ...] = 200,
    ) -> tuple[int, Any]:
        headers = {"Content-Type": "application/octet-stream"}
        if token:
            headers["Authorization"] = token

        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status = response.status
                raw = response.read()
        except urllib.error.HTTPError as exc:
            status = exc.code
            raw = exc.read()

        payload = json.loads(raw) if raw else None
        allowed = (expected,) if isinstance(expected, int) else expected
        if status not in allowed:
            raise AssertionError(f"{method} {path} returned {status}, expected {allowed}: {payload}")
        return status, payload

    def request_bytes(
        self,
        path: str,
        token: str | None = None,
        expected: int | tuple[int, ...] = 200,
    ) -> tuple[int, bytes]:
        headers = {}
        if token:
            headers["Authorization"] = token
        request = urllib.request.Request(self.base_url + path, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status = response.status
                raw = response.read()
        except urllib.error.HTTPError as exc:
            status = exc.code
            raw = exc.read()

        allowed = (expected,) if isinstance(expected, int) else expected
        if status not in allowed:
            raise AssertionError(f"GET {path} returned {status}, expected {allowed}: {raw[:200]!r}")
        return status, raw

    def create_upload_session(self, session_id: str, **kwargs: Any) -> tuple[str, dict[str, Any]]:
        metadata = fx.meta(session_id, **kwargs)
        _, payload = self.request(
            "POST",
            "/api/taiga/sessions",
            {"meta": metadata},
            token=self.token_a,
            expected=(200, 201),
        )
        return str(payload["session"]["id"]), payload

    def upload_bytes(self, session_id: str, name: str, data: bytes, chunk_size: int = 7) -> dict[str, Any]:
        offset = 0
        last: dict[str, Any] = {}
        for chunk in fx.chunks(data, chunk_size):
            _, last = self.request_raw(
                "PUT",
                f"/api/taiga/sessions/{session_id}/upload/file/{name}?offset={offset}",
                chunk,
                token=self.token_a,
            )
            offset += len(chunk)
        return last

    def upload_recording(self, session_id: str, files: dict[str, object]) -> None:
        for name in ("hr.log", "ecg.log", "acc.log", "events.jsonl"):
            data = files.get(name)
            if isinstance(data, bytes) and data:
                self.upload_bytes(session_id, name, data)

    @staticmethod
    def analysis_result(model: str, version: str = "unversioned") -> dict[str, Any]:
        return {
            "contract_version": 1,
            "model": model,
            "model_version": version,
            "stages": {
                "epoch_seconds": 30,
                "start_unix_ms": 0,
                "count": 1,
                "segments": [{"stage": "light", "count": 1}],
                "confidence": [0.5],
                "artifacts": [False],
                "probabilities": None,
            },
            "metrics": {"total_duration_s": 30.0},
            "metric_support": {},
            "quality": {"score": 100.0, "components": {}, "notes": []},
            "capabilities": {"stages": True},
            "provenance": {"runtime_s": 0.1, "peak_rss_mb": 10.0},
            "extras": {},
            "warnings": [],
        }

    def create_uploaded_session(self, session_id: str, hr_frames: int = 120) -> str:
        files = fx.recording(session_id, hr_frames=hr_frames)
        sid, _ = self.create_upload_session(session_id)
        self.upload_recording(sid, files)
        self.request(
            "POST",
            f"/api/taiga/sessions/{sid}/upload/complete",
            {},
            token=self.token_a,
        )
        return sid

    def claim_job(self, worker: str = "worker-1", lease_seconds: int = 30):
        _, payload = self.request(
            "POST",
            "/api/taiga/worker/jobs/claim",
            {"worker": worker, "lease_seconds": lease_seconds},
            token=self.worker_token,
        )
        return payload["job"]

    def finish_job(
        self,
        job: dict[str, Any],
        status: str,
        *,
        worker: str = "worker-1",
        result: dict[str, Any] | None = None,
        error: str = "",
        retryable: bool | None = None,
    ) -> None:
        body: dict[str, Any] = {
            "worker": worker,
            "status": status,
            "result": result,
            "error": error,
        }
        if retryable is not None:
            body["retryable"] = retryable
        self.request(
            "POST",
            f"/api/taiga/worker/jobs/{job['id']}/result",
            body,
            token=self.worker_token,
        )

    def login(self, collection: str, email: str, password: str) -> str:
        _, payload = self.request(
            "POST",
            f"/api/collections/{collection}/auth-with-password",
            {"identity": email, "password": password},
        )
        return str(payload["token"])

    def create_record(
        self, collection: str, body: dict[str, Any], token: str | None = None
    ) -> dict[str, Any]:
        _, payload = self.request(
            "POST",
            f"/api/collections/{collection}/records",
            body,
            token=token if token is not None else self.admin_token,
        )
        return payload

    def create_job(self, model: str, session_id: str | None = None) -> dict[str, Any]:
        return self.create_record(
            "jobs",
            {
                "session": session_id or self.session_a_id,
                "kind": "analysis",
                "model": model,
            },
            token=self.token_a,
        )


@pytest.fixture(scope="session")
def api() -> Iterator[Backend]:
    binary = os.environ.get("POCKETBASE_BIN") or shutil.which("pocketbase")
    if not binary:
        pytest.skip("API binary not found; set POCKETBASE_BIN to the built Go binary")

    backend = Backend(binary)
    try:
        yield backend
    finally:
        backend.close()


def test_01_health_is_public(api: Backend) -> None:
    status, payload = api.request("GET", "/api/taiga/health")
    assert status == 200
    assert payload["status"] == "ok"
    assert payload["service"] == "taiga-sleep"


def test_02_registration_disabled_and_auth_check(api: Backend) -> None:
    status, payload = api.request(
        "POST",
        "/api/collections/users/records",
        {
            "email": "stranger@example.com",
            "password": USER_PASSWORD,
            "passwordConfirm": USER_PASSWORD,
        },
        expected=(400, 403),
    )
    assert status in (400, 403)

    api.request("GET", "/api/taiga/auth-check", expected=401)

    _, payload = api.request("GET", "/api/taiga/auth-check", token=api.token_a)
    assert payload["id"] == api.user_a_id
    assert payload["collection"] == "users"

    _, payload = api.request("GET", "/api/taiga/auth-check", token=api.worker_token)
    assert payload["id"] == api.worker_id
    assert payload["collection"] == "workers"


def test_03_sessions_are_owner_only(api: Backend) -> None:
    _, payload = api.request("GET", "/api/collections/sessions/records", token=api.token_a)
    assert api.session_a_id in [item["id"] for item in payload["items"]]

    _, payload = api.request("GET", "/api/collections/sessions/records", token=api.token_b)
    assert api.session_a_id not in [item["id"] for item in payload["items"]]

    api.request(
        "GET",
        f"/api/collections/sessions/records/{api.session_a_id}",
        token=api.token_b,
        expected=404,
    )

    api.request(
        "POST",
        "/api/collections/sessions/records",
        {
            "owner": api.user_a_id,
            "started_at": "2026-01-02 22:00:00.000Z",
            "state": "stopped",
        },
        token=api.token_b,
        expected=(400, 403),
    )


def test_04_jobs_are_private_and_idempotent(api: Backend) -> None:
    first = api.create_job("idempotent-model")
    second = api.create_job("idempotent-model")
    assert first["id"] == second["id"]
    assert first["status"] == "queued"
    assert first["attempts"] == 0

    api.request(
        "GET",
        f"/api/collections/jobs/records/{first['id']}",
        token=api.token_b,
        expected=404,
    )

    _, payload = api.request("GET", "/api/collections/jobs/records", token=api.token_b)
    assert first["id"] not in [item["id"] for item in payload["items"]]

    api.request(
        "POST",
        "/api/collections/jobs/records",
        {
            "session": api.session_a_id,
            "kind": "analysis",
            "model": "not-mine",
        },
        token=api.token_b,
        expected=(400, 403),
    )

    # Keep the shared queue clean for the claim tests.
    api.request(
        "DELETE",
        f"/api/collections/jobs/records/{first['id']}",
        token=api.admin_token,
        expected=204,
    )


def test_05_job_is_claimed_exactly_once(api: Backend) -> None:
    job = api.create_job("claim-model")

    _, payload = api.request(
        "POST",
        "/api/taiga/worker/jobs/claim",
        {"worker": "worker-1", "lease_seconds": 30},
        token=api.worker_token,
    )
    claimed = payload["job"]
    assert claimed is not None
    assert claimed["id"] == job["id"]
    assert claimed["status"] == "running"
    assert claimed["attempts"] == 1
    assert claimed["worker"] == "worker-1"

    _, payload = api.request(
        "POST",
        "/api/taiga/worker/jobs/claim",
        {"worker": "worker-2"},
        token=api.worker_token,
    )
    assert payload["job"] is None

    api.request(
        "POST",
        f"/api/taiga/worker/jobs/{job['id']}/result",
        {"worker": "someone-else", "status": "done", "result": {}},
        token=api.worker_token,
        expected=403,
    )

    result = {"analysis": Backend.analysis_result("claim-model")}
    _, payload = api.request(
        "POST",
        f"/api/taiga/worker/jobs/{job['id']}/result",
        {"worker": "worker-1", "status": "done", "result": result},
        token=api.worker_token,
    )
    assert payload["job"]["status"] == "done"
    analysis_id = payload["job"]["result"]["analysis"]
    _, analysis = api.request(
        "GET", f"/api/taiga/analyses/{analysis_id}", token=api.token_a
    )
    assert analysis["status"] == "done"
    assert analysis["model"] == "claim-model"

    # Retried completion after a lost response must be harmless.
    api.request(
        "POST",
        f"/api/taiga/worker/jobs/{job['id']}/result",
        {"worker": "worker-1", "status": "done", "result": result},
        token=api.worker_token,
    )

    _, payload = api.request(
        "POST",
        "/api/taiga/worker/jobs/claim",
        {"worker": "worker-2"},
        token=api.worker_token,
    )
    assert payload["job"] is None


def test_06_expired_lease_is_requeued_by_cron(api: Backend) -> None:
    job = api.create_job("lease-model")

    _, payload = api.request(
        "POST",
        "/api/taiga/worker/jobs/claim",
        {"worker": "worker-1", "lease_seconds": 1},
        token=api.worker_token,
    )
    assert payload["job"]["id"] == job["id"]

    time.sleep(1.2)

    _, crons = api.request("GET", "/api/crons", token=api.admin_token)
    assert "taiga_requeue_expired_jobs" in [cron["id"] for cron in crons]

    api.request(
        "POST",
        "/api/crons/taiga_requeue_expired_jobs",
        token=api.admin_token,
        expected=204,
    )

    _, payload = api.request(
        "POST",
        "/api/taiga/worker/jobs/claim",
        {"worker": "worker-2", "lease_seconds": 60},
        token=api.worker_token,
    )
    claimed = payload["job"]
    assert claimed is not None
    assert claimed["id"] == job["id"]
    assert claimed["attempts"] == 2
    assert claimed["worker"] == "worker-2"

    api.request(
        "POST",
        f"/api/taiga/worker/jobs/{job['id']}/result",
        {"worker": "worker-2", "status": "failed", "error": "test cleanup"},
        token=api.worker_token,
    )


def test_07_worker_process_runs_a_job(api: Backend) -> None:
    files = fx.recording("20261025_070000", hr_frames=120)
    session_id, _ = api.create_upload_session("20261025_070000")
    api.upload_recording(session_id, files)
    api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/upload/complete",
        {},
        token=api.token_a,
    )
    _, requested = api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/analysis",
        {"models": [{"model": "baseline", "version": "1.1.0", "params": {}}]},
        token=api.token_a,
    )
    job = requested["jobs"][0]
    analysis_id = requested["analyses"][0]["id"]

    env = os.environ.copy()
    env.update(
        {
            "TAIGA_API_URL": api.base_url,
            "TAIGA_WORKER_EMAIL": WORKER_EMAIL,
            "TAIGA_WORKER_PASSWORD": WORKER_PASSWORD,
            "TAIGA_WORKER_ID": "integration-worker",
            "TAIGA_JOB_MEMORY_MB": "512",
            "TAIGA_JOB_TIMEOUT_S": "120",
            "TAIGA_LOG_LEVEL": "INFO",
            "TAIGA_ALGORITHMS": "baseline",
        }
    )
    result = subprocess.run(
        [sys.executable, "-m", "taiga_worker", "--once"],
        cwd=WORKER_DIR,
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if result.returncode != 0:
        api.dump_log()
        pytest.fail(f"worker exited with {result.returncode}\n{result.stderr}")

    _, job_payload = api.request(
        "GET",
        f"/api/collections/jobs/records/{job['id']}",
        token=api.token_a,
    )
    assert job_payload["status"] == "done"
    assert job_payload["attempts"] == 1
    assert job_payload["worker"] == "integration-worker"
    assert job_payload["result"]["analysis"] == analysis_id

    _, analysis = api.request(
        "GET", f"/api/taiga/analyses/{analysis_id}", token=api.token_a
    )
    assert analysis["status"] == "done"
    assert analysis["model"] == "baseline"
    assert analysis["model_version"] == "1.1.0"
    assert analysis["stages"]["count"] > 0
    assert "metrics" in analysis
    assert "quality" in analysis
    assert analysis["quality"]["score"] is not None
    assert analysis["provenance"]["worker"] == "integration-worker"


def test_08_worker_routes_require_a_worker_token(api: Backend) -> None:
    api.request("POST", "/api/taiga/worker/jobs/claim", {"worker": "x"}, expected=401)
    api.request(
        "POST",
        "/api/taiga/worker/jobs/claim",
        {"worker": "x"},
        token=api.token_a,
        expected=403,
    )
    api.request(
        "POST",
        "/api/taiga/worker/jobs/requeue",
        {},
        token=api.token_a,
        expected=403,
    )


def test_09_upload_create_resume_and_complete(api: Backend) -> None:
    files = fx.recording("20261025_090000")
    session_id, created = api.create_upload_session("20261025_090000")
    assert created["session"]["upload_status"] == "pending"
    assert created["upload"]["files"]["meta.json"]["state"] == "ok"

    hr = files["hr.log"]
    _, first = api.request_raw(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=0",
        hr[:10],
        token=api.token_a,
    )
    assert first["state"] == "partial"
    assert first["validated_length"] == 8

    _, second = api.request_raw(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=10",
        hr[10:],
        token=api.token_a,
    )
    assert second["state"] == "ok"
    assert second["frames"] == 3
    assert second["length"] == len(hr)

    status, wrong = api.request_raw(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=5",
        b"x",
        token=api.token_a,
        expected=409,
    )
    assert "expected" in wrong["message"]

    for name in ("events.jsonl", "ecg.log", "acc.log"):
        data = files.get(name)
        if isinstance(data, bytes) and data:
            api.upload_bytes(session_id, name, data)

    _, jobs_before = api.request("GET", "/api/collections/jobs/records", token=api.token_a)
    _, complete = api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/upload/complete",
        {},
        token=api.token_a,
    )
    assert complete["session"]["upload_status"] == "uploaded"
    assert complete["upload"]["completed"]
    assert complete["upload"]["files"]["hr.log"]["state"] == "ok"
    _, jobs_after = api.request("GET", "/api/collections/jobs/records", token=api.token_a)
    assert len(jobs_after["items"]) == len(jobs_before["items"])

    _, manifest = api.request(
        "GET",
        f"/api/taiga/sessions/{session_id}/upload",
        token=api.token_a,
    )
    assert manifest["upload"]["meta"] is not None
    assert manifest["upload"]["meta"]["session_id"] == "20261025_090000"


def test_10_upload_truncated_and_corrupt_tails(api: Backend) -> None:
    truncated = fx.recording("20261025_100000")
    session_id, _ = api.create_upload_session("20261025_100000")
    api.upload_bytes(session_id, "hr.log", truncated["hr.log"] + b"\x10\x00\x00\x00abc")
    _, done = api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/upload/complete",
        {},
        token=api.token_a,
    )
    entry = done["upload"]["files"]["hr.log"]
    assert entry["state"] == "ok"
    assert entry["length"] == len(truncated["hr.log"])
    assert entry["truncated"]
    assert "truncated" in done["upload"]["upload_error"]

    corrupt = fx.recording("20261025_100001")
    session_id, _ = api.create_upload_session("20261025_100001")
    data = corrupt["hr.log"] + fx.frame(b"abcd", bad_crc=True)
    _, append = api.request_raw(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=0",
        data,
        token=api.token_a,
    )
    assert append["state"] == "corrupt"
    assert append["length"] == len(corrupt["hr.log"])
    assert "CRC" in append["error"]

    _, done = api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/upload/complete",
        {},
        token=api.token_a,
    )
    entry = done["upload"]["files"]["hr.log"]
    assert entry["length"] == len(corrupt["hr.log"])
    assert "truncated" in done["upload"]["upload_error"]


def test_11_upload_validation_and_owner_isolation(api: Backend) -> None:
    bad = fx.meta("20261025_110000")
    bad["format"] = "not-taiga"
    api.request(
        "POST",
        "/api/taiga/sessions",
        {"meta": bad},
        token=api.token_a,
        expected=400,
    )

    files = fx.recording("20261025_110001")
    session_id, created = api.create_upload_session("20261025_110001")

    api.request(
        "GET",
        f"/api/taiga/sessions/{session_id}/upload",
        token=api.token_b,
        expected=404,
    )
    api.request_raw(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=0",
        b"x",
        token=api.token_b,
        expected=404,
    )
    api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/upload/complete",
        {},
        token=api.token_b,
        expected=404,
    )

    api.request_raw(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/file/other.log?offset=0",
        b"x",
        token=api.token_a,
        expected=400,
    )
    api.request_raw(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/file/..%2Fmeta.json?offset=0",
        b"x",
        token=api.token_a,
        expected=(400, 404),
    )

    changed = dict(created["upload"]["meta"])
    changed["session_id"] = "20261025_110002"
    api.request(
        "PUT",
        f"/api/taiga/sessions/{session_id}/upload/meta",
        {"meta": changed},
        token=api.token_a,
        expected=400,
    )

    recording_id, _ = api.create_upload_session("20261025_110003", state="recording")
    api.upload_bytes(recording_id, "hr.log", files["hr.log"])
    api.request(
        "POST",
        f"/api/taiga/sessions/{recording_id}/upload/complete",
        {},
        token=api.token_a,
        expected=409,
    )


def test_12_upload_delete_removes_recording_directory(api: Backend) -> None:
    files = fx.recording("20261025_120000")
    session_id, _ = api.create_upload_session("20261025_120000")
    api.upload_bytes(session_id, "hr.log", files["hr.log"])

    directory = Path(api.tmpdir) / "pb_data" / "recordings" / session_id
    assert directory.is_dir()

    api.request(
        "DELETE",
        f"/api/collections/sessions/records/{session_id}",
        token=api.token_a,
        expected=204,
    )
    assert not directory.exists()


def test_13_upload_create_is_idempotent(api: Backend) -> None:
    metadata = fx.meta("20261025_130000")
    _, first = api.request(
        "POST",
        "/api/taiga/sessions",
        {"meta": metadata},
        token=api.token_a,
        expected=(200, 201),
    )
    _, second = api.request(
        "POST",
        "/api/taiga/sessions",
        {"meta": metadata},
        token=api.token_a,
        expected=200,
    )
    assert first["session"]["id"] == second["session"]["id"]


def test_14_algorithms_analysis_request_and_views(api: Backend) -> None:
    capabilities = [
        {
            "id": "baseline",
            "version": "1.0.0",
            "name": "Baseline HR/ACC",
            "description": "test",
            "license": "GPL-3.0-or-later",
            "citation": None,
            "homepage": None,
            "needs": [{"name": "hr", "required": True}],
            "epoch_seconds": 30,
            "stage_scheme": "aasm4",
            "supported_stages": ["wake", "rem", "light", "deep", "unknown"],
            "capabilities": ["stages", "confidence", "artifacts"],
            "default_params": {},
            "params_schema": {},
            "source": "builtin",
        },
        {
            "id": "wav2sleep",
            "version": "1.0.0",
            "name": "wav2sleep ECG",
            "description": "test",
            "license": "MIT",
            "citation": "Carter and Tarassenko, ML4H 2024",
            "homepage": "https://github.com/joncarter1/wav2sleep",
            "needs": [{"name": "ecg", "required": True, "min_rate_hz": 50.0}],
            "epoch_seconds": 30,
            "stage_scheme": "aasm4",
            "supported_stages": ["wake", "rem", "light", "deep", "unknown"],
            "capabilities": [
                "stages",
                "confidence",
                "probabilities",
                "artifacts",
            ],
            "default_params": {"max_length_hours": 10},
            "params_schema": {},
            "source": "builtin",
        },
        {
            "id": "sleep-ecg",
            "version": "1.0.0",
            "name": "SleepECG",
            "description": "test",
            "license": "BSD-3-Clause",
            "citation": "Hofer and Brunner, JOSS 2023",
            "homepage": "https://github.com/cbrnr/sleepecg",
            "needs": [
                {"name": "hr", "required": False},
                {"name": "ecg", "required": False},
            ],
            "epoch_seconds": 30,
            "stage_scheme": "sleep-ecg",
            "supported_stages": ["wake", "rem", "nrem", "sleep", "unknown"],
            "capabilities": [
                "stages",
                "confidence",
                "probabilities",
                "artifacts",
                "no_deep",
            ],
            "default_params": {"classifier": "wrn-gru-mesa"},
            "params_schema": {},
            "source": "builtin",
        },
    ]
    api.request(
        "POST",
        "/api/taiga/worker/capabilities",
        {"worker": "integration-worker", "version": "0.1.0", "algorithms": capabilities},
        token=api.worker_token,
    )
    _, listing = api.request("GET", "/api/taiga/algorithms", token=api.token_a)
    assert "baseline" in [item["id"] for item in listing["algorithms"]]
    baseline = next(item for item in listing["algorithms"] if item["id"] == "baseline")
    assert baseline["version"] == "1.0.0"
    assert baseline["worker_count"] == 1
    assert "supported_stages" in baseline
    wav2sleep = next(
        item for item in listing["algorithms"] if item["id"] == "wav2sleep"
    )
    assert wav2sleep["license"] == "MIT"
    assert wav2sleep["needs"][0]["name"] == "ecg"
    assert "probabilities" in wav2sleep["capabilities"]
    sleep_ecg = next(
        item for item in listing["algorithms"] if item["id"] == "sleep-ecg"
    )
    assert sleep_ecg["license"] == "BSD-3-Clause"
    assert sleep_ecg["stage_scheme"] == "sleep-ecg"
    assert "no_deep" in sleep_ecg["capabilities"]

    session_id = api.create_uploaded_session("20261025_140000")
    api.request(
        "POST",
        f"/api/taiga/sessions/{api.session_a_id}/analysis",
        {"models": [{"model": "baseline", "version": "1.0.0"}]},
        token=api.token_a,
        expected=409,
    )
    api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/analysis",
        {"models": [{"model": "baseline"}]},
        token=api.token_a,
        expected=400,
    )

    body = {"models": [{"model": "baseline", "version": "1.0.0", "params": {}}]}
    _, first = api.request(
        "POST", f"/api/taiga/sessions/{session_id}/analysis", body, token=api.token_a
    )
    _, second = api.request(
        "POST", f"/api/taiga/sessions/{session_id}/analysis", body, token=api.token_a
    )
    assert first["analyses"][0]["id"] == second["analyses"][0]["id"]
    assert first["jobs"][0]["id"] == second["jobs"][0]["id"]
    analysis_id = first["analyses"][0]["id"]
    job = first["jobs"][0]

    _, detail = api.request(
        "GET", f"/api/taiga/sessions/{session_id}", token=api.token_a
    )
    assert detail["session"]["id"] == session_id
    assert detail["analysis_summary"]["queued"] == 1
    assert detail["analyses"][0]["id"] == analysis_id
    assert detail["jobs"][0]["id"] == job["id"]
    assert "upload" in detail

    _, sessions = api.request(
        "GET", "/api/taiga/sessions?upload_status=uploaded&perPage=50", token=api.token_a
    )
    assert session_id in [item["session"]["id"] for item in sessions["items"]]
    assert "hasMore" in sessions

    api.request(
        "GET", f"/api/taiga/sessions/{session_id}", token=api.token_b, expected=404
    )
    api.request(
        "GET", f"/api/taiga/analyses/{analysis_id}", token=api.token_b, expected=404
    )

    claimed = api.claim_job()
    assert claimed["id"] == job["id"]
    assert claimed["params"] == {}
    api.finish_job(
        claimed,
        "done",
        result={"analysis": Backend.analysis_result("baseline", "1.0.0")},
    )
    _, analysis = api.request(
        "GET", f"/api/taiga/analyses/{analysis_id}", token=api.token_a
    )
    assert analysis["status"] == "done"
    assert analysis["model_version"] == "1.0.0"

    _, stopped = api.request(
        "GET", "/api/taiga/sessions?state=stopped&perPage=50", token=api.token_a
    )
    assert session_id in [item["session"]["id"] for item in stopped["items"]]


def test_15_worker_download_is_scoped(api: Backend) -> None:
    session_id = api.create_uploaded_session("20261025_150000")
    _, requested = api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/analysis",
        {"models": [{"model": "baseline", "version": "1.0.0", "params": {}}]},
        token=api.token_a,
    )
    job = requested["jobs"][0]
    claimed = api.claim_job()
    assert claimed["id"] == job["id"]

    api.request(
        "GET",
        f"/api/taiga/worker/sessions/{session_id}/manifest",
        token=api.worker_token,
        expected=400,
    )
    api.request(
        "GET",
        f"/api/taiga/worker/sessions/{session_id}/manifest?job={job['id']}&worker=other",
        token=api.worker_token,
        expected=403,
    )
    _, manifest = api.request(
        "GET",
        f"/api/taiga/worker/sessions/{session_id}/manifest?job={job['id']}&worker=worker-1",
        token=api.worker_token,
    )
    assert "hr.log" in manifest["upload"]["files"]
    assert "meta.json" in manifest["upload"]["files"]

    _, data = api.request_bytes(
        f"/api/taiga/worker/sessions/{session_id}/files/hr.log?job={job['id']}&worker=worker-1",
        token=api.worker_token,
    )
    assert data.startswith(b"TSLG")
    api.request_bytes(
        f"/api/taiga/worker/sessions/{session_id}/files/hr.log?job={job['id']}&worker=other",
        token=api.worker_token,
        expected=403,
    )

    api.finish_job(claimed, "failed", error="cleanup", retryable=False)


def test_16_worker_result_validation_and_retry(api: Backend) -> None:
    session_id = api.create_uploaded_session("20261025_160000")
    _, requested = api.request(
        "POST",
        f"/api/taiga/sessions/{session_id}/analysis",
        {"models": [{"model": "baseline", "version": "1.0.0", "params": {}}]},
        token=api.token_a,
    )
    job = requested["jobs"][0]
    analysis_id = requested["analyses"][0]["id"]
    claimed = api.claim_job()
    assert claimed["id"] == job["id"]

    api.finish_job(claimed, "done", result={})
    _, job_payload = api.request(
        "GET", f"/api/collections/jobs/records/{job['id']}", token=api.token_a
    )
    assert job_payload["status"] == "failed"
    _, analysis = api.request(
        "GET", f"/api/taiga/analyses/{analysis_id}", token=api.token_a
    )
    assert analysis["status"] == "failed"
    assert "no analysis result" in analysis["error"]

    _, retried = api.request(
        "POST", f"/api/taiga/jobs/{job['id']}/retry", {}, token=api.token_a
    )
    assert retried["job"]["status"] == "queued"
    assert retried["analysis"]["status"] == "queued"

    claimed = api.claim_job()
    assert claimed["id"] == job["id"]
    api.finish_job(claimed, "failed", error="cleanup", retryable=False)


def test_17_openapi_document_is_served(api: Backend) -> None:
    """The API serves a generated OpenAPI 3.1 document, not a checked-in file."""

    status, document = api.request("GET", "/api/taiga/openapi.json")
    assert status == 200
    assert document["openapi"] == "3.1.0"
    assert document["info"]["title"]

    paths = document["paths"]
    assert "/api/taiga/health" in paths
    assert "/api/taiga/worker/jobs/claim" in paths

    public = ("/api/taiga/health", "/api/taiga/openapi.json", "/api/taiga/docs")
    for path, item in paths.items():
        for operation in item.values():
            assert operation["operationId"]
            assert operation["responses"]
            scheme = None
            if "security" in operation:
                scheme = next(iter(operation["security"][0]))
            if path.startswith("/api/taiga/worker/"):
                assert scheme == "workerToken"
            elif path.startswith(public):
                assert scheme is None
            else:
                assert scheme == "userToken"

    # Shapes only: no credentials, session ids or recorded values.
    text = json.dumps(document)
    assert api.session_a_id not in text
    assert api.token_a not in text
    assert USER_PASSWORD not in text


def test_18_docs_page_and_assets_are_served(api: Backend) -> None:
    """Swagger UI is served by the API itself, with no CDN or extra service."""

    _, page = api.request_bytes("/api/taiga/docs")
    assert b"SwaggerUIBundle(" in page
    assert b'url: "/api/taiga/openapi.json"' in page
    assert b"/api/taiga/docs/swagger-ui.css" in page
    assert b"/api/taiga/docs/swagger-ui-bundle.js" in page
    assert b"taiga-bar" not in page

    _, css = api.request_bytes("/api/taiga/docs/swagger-ui.css")
    assert b".swagger-ui" in css

    _, bundle = api.request_bytes("/api/taiga/docs/swagger-ui-bundle.js")
    assert b"SwaggerUIBundle" in bundle

    api.request("GET", "/api/taiga/docs/not-an-asset", expected=404)
