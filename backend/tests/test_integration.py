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
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from tests import recording_fixture as fx

BACKEND_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = BACKEND_DIR.parent
WORKER_DIR = BACKEND_DIR / "worker"
MIGRATIONS_DIR = BACKEND_DIR / "pocketbase" / "pb_migrations"
HOOKS_DIR = BACKEND_DIR / "pocketbase" / "pb_hooks"

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


class IntegrationTest(unittest.TestCase):
    server: subprocess.Popen[bytes] | None = None
    tmpdir: str = ""
    base_url: str = ""
    admin_token: str = ""
    token_a: str = ""
    token_b: str = ""
    worker_token: str = ""
    user_a_id: str = ""
    user_b_id: str = ""
    worker_id: str = ""
    session_a_id: str = ""

    @classmethod
    def setUpClass(cls) -> None:
        binary = os.environ.get("POCKETBASE_BIN") or shutil.which("pocketbase")
        if not binary:
            raise unittest.SkipTest(
                "pocketbase binary not found; set POCKETBASE_BIN or add pocketbase to PATH"
            )

        cls.tmpdir = tempfile.mkdtemp(prefix="taiga-backend-test-")
        data_dir = Path(cls.tmpdir) / "pb_data"
        cls.base_url = f"http://127.0.0.1:{free_port()}"
        log_path = Path(cls.tmpdir) / "pocketbase.log"

        subprocess.run(
            [binary, "superuser", "upsert", ADMIN_EMAIL, ADMIN_PASSWORD, "--dir", str(data_dir)],
            check=True,
            capture_output=True,
        )

        cls.log_handle = log_path.open("wb")
        cls.server = subprocess.Popen(
            [
                binary,
                "serve",
                "--http",
                cls.base_url.replace("http://", ""),
                "--dir",
                str(data_dir),
                "--migrationsDir",
                str(MIGRATIONS_DIR),
                "--hooksDir",
                str(HOOKS_DIR),
                "--automigrate=false",
                "--hooksWatch=false",
                "--hooksPool=1",
            ],
            stdout=cls.log_handle,
            stderr=subprocess.STDOUT,
        )

        cls._wait_for_health()

        try:
            cls.admin_token = cls._login("_superusers", ADMIN_EMAIL, ADMIN_PASSWORD)
            cls.user_a_id = cls._create_record(
                "users",
                {"email": USER_A_EMAIL, "password": USER_PASSWORD, "passwordConfirm": USER_PASSWORD},
            )["id"]
            cls.user_b_id = cls._create_record(
                "users",
                {"email": USER_B_EMAIL, "password": USER_PASSWORD, "passwordConfirm": USER_PASSWORD},
            )["id"]
            cls.worker_id = cls._create_record(
                "workers",
                {
                    "email": WORKER_EMAIL,
                    "password": WORKER_PASSWORD,
                    "passwordConfirm": WORKER_PASSWORD,
                    "name": "integration",
                },
            )["id"]

            cls.token_a = cls._login("users", USER_A_EMAIL, USER_PASSWORD)
            cls.token_b = cls._login("users", USER_B_EMAIL, USER_PASSWORD)
            cls.worker_token = cls._login("workers", WORKER_EMAIL, WORKER_PASSWORD)

            session = cls._create_record(
                "sessions",
                {
                    "owner": cls.user_a_id,
                    "device": "Polar H10",
                    "started_at": "2026-01-01 22:00:00.000Z",
                    "ended_at": "2026-01-02 06:00:00.000Z",
                    "duration_s": 28800,
                    "state": "stopped",
                },
                token=cls.token_a,
            )
            cls.session_a_id = session["id"]
        except Exception:
            cls._dump_log_on_failure()
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.server is not None:
            cls.server.terminate()
            try:
                cls.server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                cls.server.kill()
                cls.server.wait(timeout=5)
        if getattr(cls, "log_handle", None) is not None:
            cls.log_handle.close()

        if os.environ.get("TAIGA_KEEP_TEST_DATA") and cls.tmpdir:
            print(f"\nTest data kept in {cls.tmpdir}", file=sys.stderr)
        elif cls.tmpdir:
            shutil.rmtree(cls.tmpdir, ignore_errors=True)

    @classmethod
    def _dump_log_on_failure(cls) -> None:
        log_path = Path(cls.tmpdir) / "pocketbase.log"
        if log_path.exists():
            print("\n--- pocketbase log ---", file=sys.stderr)
            print(log_path.read_text(errors="replace")[-8000:], file=sys.stderr)

    @classmethod
    def _wait_for_health(cls) -> None:
        deadline = time.monotonic() + 25
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            try:
                status, _ = cls.request("GET", "/api/taiga/health")
                if status == 200:
                    return
            except Exception as exc:  # noqa: BLE001 - only used for retries
                last_error = exc
            if cls.server and cls.server.poll() is not None:
                break
            time.sleep(0.2)
        cls._dump_log_on_failure()
        raise RuntimeError(f"server did not become healthy: {last_error}")

    @classmethod
    def request(
        cls,
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

        request = urllib.request.Request(cls.base_url + path, data=data, headers=headers, method=method)
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

    @classmethod
    def request_raw(
        cls,
        method: str,
        path: str,
        data: bytes,
        token: str | None = None,
        expected: int | tuple[int, ...] = 200,
    ) -> tuple[int, Any]:
        headers = {"Content-Type": "application/octet-stream"}
        if token:
            headers["Authorization"] = token

        request = urllib.request.Request(cls.base_url + path, data=data, headers=headers, method=method)
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

    def _create_upload_session(self, session_id: str, **kwargs: Any) -> tuple[str, dict[str, Any]]:
        metadata = fx.meta(session_id, **kwargs)
        _, payload = self.request(
            "POST",
            "/api/taiga/sessions",
            {"meta": metadata},
            token=self.token_a,
            expected=(200, 201),
        )
        return str(payload["session"]["id"]), payload

    def _upload_bytes(self, session_id: str, name: str, data: bytes, chunk_size: int = 7) -> dict[str, Any]:
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

    def _upload_recording(self, session_id: str, files: dict[str, object]) -> None:
        for name in ("hr.log", "ecg.log", "acc.log", "events.jsonl"):
            data = files.get(name)
            if isinstance(data, bytes) and data:
                self._upload_bytes(session_id, name, data)

    @classmethod
    def _login(cls, collection: str, email: str, password: str) -> str:
        _, payload = cls.request(
            "POST",
            f"/api/collections/{collection}/auth-with-password",
            {"identity": email, "password": password},
        )
        return str(payload["token"])

    @classmethod
    def _create_record(
        cls, collection: str, body: dict[str, Any], token: str | None = None
    ) -> dict[str, Any]:
        _, payload = cls.request(
            "POST",
            f"/api/collections/{collection}/records",
            body,
            token=token if token is not None else cls.admin_token,
        )
        return payload

    @classmethod
    def _create_job(cls, model: str, session_id: str | None = None) -> dict[str, Any]:
        return cls._create_record(
            "jobs",
            {
                "session": session_id or cls.session_a_id,
                "kind": "analysis",
                "model": model,
            },
            token=cls.token_a,
        )

    def test_01_health_is_public(self) -> None:
        status, payload = self.request("GET", "/api/taiga/health")
        self.assertEqual(status, 200)
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["service"], "taiga-sleep")

    def test_02_registration_disabled_and_auth_check(self) -> None:
        status, payload = self.request(
            "POST",
            "/api/collections/users/records",
            {
                "email": "stranger@example.com",
                "password": USER_PASSWORD,
                "passwordConfirm": USER_PASSWORD,
            },
            expected=(400, 403),
        )
        self.assertIn(status, (400, 403))

        self.request("GET", "/api/taiga/auth-check", expected=401)

        _, payload = self.request("GET", "/api/taiga/auth-check", token=self.token_a)
        self.assertEqual(payload["id"], self.user_a_id)
        self.assertEqual(payload["collection"], "users")

        _, payload = self.request("GET", "/api/taiga/auth-check", token=self.worker_token)
        self.assertEqual(payload["id"], self.worker_id)
        self.assertEqual(payload["collection"], "workers")

    def test_03_sessions_are_owner_only(self) -> None:
        _, payload = self.request("GET", "/api/collections/sessions/records", token=self.token_a)
        self.assertIn(self.session_a_id, [item["id"] for item in payload["items"]])

        _, payload = self.request("GET", "/api/collections/sessions/records", token=self.token_b)
        self.assertNotIn(self.session_a_id, [item["id"] for item in payload["items"]])

        self.request(
            "GET",
            f"/api/collections/sessions/records/{self.session_a_id}",
            token=self.token_b,
            expected=404,
        )

        self.request(
            "POST",
            "/api/collections/sessions/records",
            {
                "owner": self.user_a_id,
                "started_at": "2026-01-02 22:00:00.000Z",
                "state": "stopped",
            },
            token=self.token_b,
            expected=(400, 403),
        )

    def test_04_jobs_are_private_and_idempotent(self) -> None:
        first = self._create_job("idempotent-model")
        second = self._create_job("idempotent-model")
        self.assertEqual(first["id"], second["id"])
        self.assertEqual(first["status"], "queued")
        self.assertEqual(first["attempts"], 0)

        self.request(
            "GET",
            f"/api/collections/jobs/records/{first['id']}",
            token=self.token_b,
            expected=404,
        )

        _, payload = self.request("GET", "/api/collections/jobs/records", token=self.token_b)
        self.assertNotIn(first["id"], [item["id"] for item in payload["items"]])

        self.request(
            "POST",
            "/api/collections/jobs/records",
            {
                "session": self.session_a_id,
                "kind": "analysis",
                "model": "not-mine",
            },
            token=self.token_b,
            expected=(400, 403),
        )

        # Keep the shared queue clean for the claim tests.
        self.request(
            "DELETE",
            f"/api/collections/jobs/records/{first['id']}",
            token=self.token_a,
            expected=204,
        )

    def test_05_job_is_claimed_exactly_once(self) -> None:
        job = self._create_job("claim-model")

        _, payload = self.request(
            "POST",
            "/api/taiga/worker/jobs/claim",
            {"worker": "worker-1", "lease_seconds": 30},
            token=self.worker_token,
        )
        claimed = payload["job"]
        self.assertIsNotNone(claimed)
        self.assertEqual(claimed["id"], job["id"])
        self.assertEqual(claimed["status"], "running")
        self.assertEqual(claimed["attempts"], 1)
        self.assertEqual(claimed["worker"], "worker-1")

        _, payload = self.request(
            "POST",
            "/api/taiga/worker/jobs/claim",
            {"worker": "worker-2"},
            token=self.worker_token,
        )
        self.assertIsNone(payload["job"])

        self.request(
            "POST",
            f"/api/taiga/worker/jobs/{job['id']}/result",
            {"worker": "someone-else", "status": "done", "result": {}},
            token=self.worker_token,
            expected=403,
        )

        _, payload = self.request(
            "POST",
            f"/api/taiga/worker/jobs/{job['id']}/result",
            {"worker": "worker-1", "status": "done", "result": {"noop": True}},
            token=self.worker_token,
        )
        self.assertEqual(payload["job"]["status"], "done")
        self.assertEqual(payload["job"]["result"], {"noop": True})

        # Retried completion after a lost response must be harmless.
        self.request(
            "POST",
            f"/api/taiga/worker/jobs/{job['id']}/result",
            {"worker": "worker-1", "status": "done", "result": {"noop": True}},
            token=self.worker_token,
        )

        _, payload = self.request(
            "POST",
            "/api/taiga/worker/jobs/claim",
            {"worker": "worker-2"},
            token=self.worker_token,
        )
        self.assertIsNone(payload["job"])

    def test_06_expired_lease_is_requeued_by_cron(self) -> None:
        job = self._create_job("lease-model")

        _, payload = self.request(
            "POST",
            "/api/taiga/worker/jobs/claim",
            {"worker": "worker-1", "lease_seconds": 1},
            token=self.worker_token,
        )
        self.assertEqual(payload["job"]["id"], job["id"])

        time.sleep(1.2)

        _, crons = self.request("GET", "/api/crons", token=self.admin_token)
        self.assertIn("taiga_requeue_expired_jobs", [cron["id"] for cron in crons])

        self.request(
            "POST",
            "/api/crons/taiga_requeue_expired_jobs",
            token=self.admin_token,
            expected=204,
        )

        _, payload = self.request(
            "POST",
            "/api/taiga/worker/jobs/claim",
            {"worker": "worker-2", "lease_seconds": 60},
            token=self.worker_token,
        )
        claimed = payload["job"]
        self.assertIsNotNone(claimed)
        self.assertEqual(claimed["id"], job["id"])
        self.assertEqual(claimed["attempts"], 2)
        self.assertEqual(claimed["worker"], "worker-2")

        self.request(
            "POST",
            f"/api/taiga/worker/jobs/{job['id']}/result",
            {"worker": "worker-2", "status": "failed", "error": "test cleanup"},
            token=self.worker_token,
        )

    def test_07_worker_process_runs_a_job(self) -> None:
        job = self._create_job("worker-e2e-model")

        env = os.environ.copy()
        env.update(
            {
                "TAIGA_API_URL": self.base_url,
                "TAIGA_WORKER_EMAIL": WORKER_EMAIL,
                "TAIGA_WORKER_PASSWORD": WORKER_PASSWORD,
                "TAIGA_WORKER_ID": "integration-worker",
                "TAIGA_JOB_MEMORY_MB": "256",
                "TAIGA_JOB_TIMEOUT_S": "60",
                "TAIGA_LOG_LEVEL": "INFO",
            }
        )
        result = subprocess.run(
            [sys.executable, "-m", "taiga_worker", "--once"],
            cwd=WORKER_DIR,
            env=env,
            capture_output=True,
            text=True,
            timeout=90,
        )
        if result.returncode != 0:
            self._dump_log_on_failure()
            self.fail(f"worker exited with {result.returncode}\n{result.stderr}")

        _, payload = self.request(
            "GET",
            f"/api/collections/jobs/records/{job['id']}",
            token=self.token_a,
        )
        self.assertEqual(payload["status"], "done")
        self.assertEqual(payload["attempts"], 1)
        self.assertEqual(payload["worker"], "integration-worker")
        self.assertTrue(payload["result"]["noop"])
        self.assertEqual(payload["result"]["model"], "worker-e2e-model")

    def test_08_worker_routes_require_a_worker_token(self) -> None:
        self.request("POST", "/api/taiga/worker/jobs/claim", {"worker": "x"}, expected=401)
        self.request(
            "POST",
            "/api/taiga/worker/jobs/claim",
            {"worker": "x"},
            token=self.token_a,
            expected=403,
        )
        self.request(
            "POST",
            "/api/taiga/worker/jobs/requeue",
            {},
            token=self.token_a,
            expected=403,
        )

    def test_09_upload_create_resume_and_complete(self) -> None:
        files = fx.recording("20261025_090000")
        session_id, created = self._create_upload_session("20261025_090000")
        self.assertEqual(created["session"]["upload_status"], "pending")
        self.assertEqual(created["upload"]["files"]["meta.json"]["state"], "ok")

        hr = files["hr.log"]
        _, first = self.request_raw(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=0",
            hr[:10],
            token=self.token_a,
        )
        self.assertEqual(first["state"], "partial")
        self.assertEqual(first["validated_length"], 8)

        _, second = self.request_raw(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=10",
            hr[10:],
            token=self.token_a,
        )
        self.assertEqual(second["state"], "ok")
        self.assertEqual(second["frames"], 3)
        self.assertEqual(second["length"], len(hr))

        status, wrong = self.request_raw(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=5",
            b"x",
            token=self.token_a,
            expected=409,
        )
        self.assertIn("expected", wrong["message"])

        for name in ("events.jsonl", "ecg.log", "acc.log"):
            data = files.get(name)
            if isinstance(data, bytes) and data:
                self._upload_bytes(session_id, name, data)

        _, jobs_before = self.request("GET", "/api/collections/jobs/records", token=self.token_a)
        _, complete = self.request(
            "POST",
            f"/api/taiga/sessions/{session_id}/upload/complete",
            {},
            token=self.token_a,
        )
        self.assertEqual(complete["session"]["upload_status"], "uploaded")
        self.assertTrue(complete["upload"]["completed"])
        self.assertEqual(complete["upload"]["files"]["hr.log"]["state"], "ok")
        _, jobs_after = self.request("GET", "/api/collections/jobs/records", token=self.token_a)
        self.assertEqual(len(jobs_after["items"]), len(jobs_before["items"]))

        _, manifest = self.request(
            "GET",
            f"/api/taiga/sessions/{session_id}/upload",
            token=self.token_a,
        )
        self.assertIsNotNone(manifest["upload"]["meta"])
        self.assertEqual(manifest["upload"]["meta"]["session_id"], "20261025_090000")

    def test_10_upload_truncated_and_corrupt_tails(self) -> None:
        truncated = fx.recording("20261025_100000")
        session_id, _ = self._create_upload_session("20261025_100000")
        self._upload_bytes(session_id, "hr.log", truncated["hr.log"] + b"\x10\x00\x00\x00abc")
        _, done = self.request(
            "POST",
            f"/api/taiga/sessions/{session_id}/upload/complete",
            {},
            token=self.token_a,
        )
        entry = done["upload"]["files"]["hr.log"]
        self.assertEqual(entry["state"], "ok")
        self.assertEqual(entry["length"], len(truncated["hr.log"]))
        self.assertTrue(entry["truncated"])
        self.assertIn("truncated", done["upload"]["upload_error"])

        corrupt = fx.recording("20261025_100001")
        session_id, _ = self._create_upload_session("20261025_100001")
        data = corrupt["hr.log"] + fx.frame(b"abcd", bad_crc=True)
        _, append = self.request_raw(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=0",
            data,
            token=self.token_a,
        )
        self.assertEqual(append["state"], "corrupt")
        self.assertEqual(append["length"], len(corrupt["hr.log"]))
        self.assertIn("CRC", append["error"])

        _, done = self.request(
            "POST",
            f"/api/taiga/sessions/{session_id}/upload/complete",
            {},
            token=self.token_a,
        )
        entry = done["upload"]["files"]["hr.log"]
        self.assertEqual(entry["length"], len(corrupt["hr.log"]))
        self.assertIn("truncated", done["upload"]["upload_error"])

    def test_11_upload_validation_and_owner_isolation(self) -> None:
        bad = fx.meta("20261025_110000")
        bad["format"] = "not-taiga"
        self.request(
            "POST",
            "/api/taiga/sessions",
            {"meta": bad},
            token=self.token_a,
            expected=400,
        )

        files = fx.recording("20261025_110001")
        session_id, created = self._create_upload_session("20261025_110001")

        self.request(
            "GET",
            f"/api/taiga/sessions/{session_id}/upload",
            token=self.token_b,
            expected=404,
        )
        self.request_raw(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/file/hr.log?offset=0",
            b"x",
            token=self.token_b,
            expected=404,
        )
        self.request(
            "POST",
            f"/api/taiga/sessions/{session_id}/upload/complete",
            {},
            token=self.token_b,
            expected=404,
        )

        self.request_raw(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/file/other.log?offset=0",
            b"x",
            token=self.token_a,
            expected=400,
        )
        self.request_raw(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/file/..%2Fmeta.json?offset=0",
            b"x",
            token=self.token_a,
            expected=(400, 404),
        )

        changed = dict(created["upload"]["meta"])
        changed["session_id"] = "20261025_110002"
        self.request(
            "PUT",
            f"/api/taiga/sessions/{session_id}/upload/meta",
            {"meta": changed},
            token=self.token_a,
            expected=400,
        )

        recording_id, _ = self._create_upload_session("20261025_110003", state="recording")
        self._upload_bytes(recording_id, "hr.log", files["hr.log"])
        self.request(
            "POST",
            f"/api/taiga/sessions/{recording_id}/upload/complete",
            {},
            token=self.token_a,
            expected=409,
        )

    def test_12_upload_delete_removes_recording_directory(self) -> None:
        files = fx.recording("20261025_120000")
        session_id, _ = self._create_upload_session("20261025_120000")
        self._upload_bytes(session_id, "hr.log", files["hr.log"])

        directory = Path(self.tmpdir) / "pb_data" / "recordings" / session_id
        self.assertTrue(directory.is_dir())

        self.request(
            "DELETE",
            f"/api/collections/sessions/records/{session_id}",
            token=self.token_a,
            expected=204,
        )
        self.assertFalse(directory.exists())

    def test_13_upload_create_is_idempotent(self) -> None:
        metadata = fx.meta("20261025_130000")
        _, first = self.request(
            "POST",
            "/api/taiga/sessions",
            {"meta": metadata},
            token=self.token_a,
            expected=(200, 201),
        )
        _, second = self.request(
            "POST",
            "/api/taiga/sessions",
            {"meta": metadata},
            token=self.token_a,
            expected=200,
        )
        self.assertEqual(first["session"]["id"], second["session"]["id"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
