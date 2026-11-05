"""A real API server and the helpers the integration tests drive it with.

The suite starts the Go binary in a temporary data directory, creates the accounts
through the admin API (the operator's way, since the API has no sign-up) and from then on
talks to the server only through API v1, like any client would.
"""

from __future__ import annotations

import gzip
import json as jsonlib
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator, Mapping

from tests import recording_fixture as fx

BACKEND_DIR = Path(__file__).resolve().parents[1]
WORKER_DIR = BACKEND_DIR / "worker"

ADMIN_EMAIL = "admin@example.com"
ADMIN_PASSWORD = "admin-password-123"
PASSWORD = "account-password-123"

# Kept small so the tests can cross the limit without sending megabytes.
CHUNK_LIMIT_MB = 1


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def unique(prefix: str) -> str:
    """An id that is valid as a recording id and unique within the run."""

    return f"{prefix}-{uuid.uuid4().hex[:10]}"


@dataclass
class Response:
    status: int
    headers: Any
    body: bytes

    def json(self) -> Any:
        return jsonlib.loads(self.body)

    def header(self, name: str) -> str | None:
        return self.headers.get(name)


@dataclass
class Account:
    id: str
    email: str
    password: str
    token: str


class Backend:
    """A running API server, its accounts and helpers to talk to it."""

    def __init__(self, binary: str) -> None:
        self.binary = binary
        self.tmpdir = tempfile.mkdtemp(prefix="taiga-backend-test-")
        self.data_dir = Path(self.tmpdir) / "pb_data"
        self.base_url = f"http://127.0.0.1:{free_port()}"
        self.server: subprocess.Popen[bytes] | None = None
        self.log_path = Path(self.tmpdir) / "server.log"
        self.log_handle = self.log_path.open("wb")
        self.admin_token = ""
        self.users: dict[str, Account] = {}
        self.workers: dict[str, Account] = {}

        try:
            self._start()
            self._seed()
        except Exception:
            self.dump_log()
            self.close()
            raise

    # --- lifecycle ---

    def _start(self) -> None:
        subprocess.run(
            [
                self.binary,
                "superuser",
                "upsert",
                ADMIN_EMAIL,
                ADMIN_PASSWORD,
                "--dir",
                str(self.data_dir),
            ],
            check=True,
            capture_output=True,
        )
        env = os.environ.copy()
        env["TAIGA_MAX_UPLOAD_CHUNK_MB"] = str(CHUNK_LIMIT_MB)
        self.server = subprocess.Popen(
            [
                self.binary,
                "serve",
                "--http",
                self.base_url.replace("http://", ""),
                "--dir",
                str(self.data_dir),
                "--automigrate=false",
            ],
            env=env,
            stdout=self.log_handle,
            stderr=subprocess.STDOUT,
        )
        self.wait_for_health()

    def _seed(self) -> None:
        # The admin API is the operator's tool and the only way to create accounts.
        response = self.post(
            "/api/collections/_superusers/auth-with-password",
            json={"identity": ADMIN_EMAIL, "password": ADMIN_PASSWORD},
            expect=200,
        )
        self.admin_token = response.json()["token"]
        self.users["a"] = self.new_account("users", "a")
        self.users["b"] = self.new_account("users", "b")
        self.workers["fake"] = self.new_account("workers", "fake")
        self.workers["other"] = self.new_account("workers", "other")
        self.workers["real"] = self.new_account("workers", "real")

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
        else:
            shutil.rmtree(self.tmpdir, ignore_errors=True)

    def dump_log(self) -> None:
        if self.log_path.exists():
            print("\n--- server log ---", file=sys.stderr)
            print(self.log_path.read_text(errors="replace")[-8000:], file=sys.stderr)

    def wait_for_health(self) -> None:
        deadline = time.monotonic() + 25
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            try:
                if self.get("/v1/health").status == 200:
                    return
            except Exception as exc:  # noqa: BLE001 - only used for retries
                last_error = exc
            if self.server and self.server.poll() is not None:
                break
            time.sleep(0.2)
        self.dump_log()
        raise RuntimeError(f"server did not become healthy: {last_error}")

    # --- requests ---

    def request(
        self,
        method: str,
        path: str,
        *,
        token: str | Account | None = None,
        json: Any = None,
        data: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        expect: int | tuple[int, ...] | None = None,
    ) -> Response:
        sent = dict(headers or {})
        if json is not None:
            data = jsonlib.dumps(json).encode("utf-8")
            sent["Content-Type"] = "application/json"
        elif data is not None:
            sent.setdefault("Content-Type", "application/octet-stream")
        if isinstance(token, Account):
            token = token.token
        if token:
            sent["Authorization"] = f"Bearer {token}"

        request = urllib.request.Request(self.base_url + path, data=data, headers=sent, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as raw:
                response = Response(raw.status, raw.headers, raw.read())
        except urllib.error.HTTPError as exc:
            response = Response(exc.code, exc.headers, exc.read())

        if expect is not None:
            allowed = (expect,) if isinstance(expect, int) else expect
            if response.status not in allowed:
                raise AssertionError(
                    f"{method} {path} returned {response.status}, expected {allowed}: {response.body[:300]!r}"
                )
        return response

    def get(self, path: str, **kwargs: Any) -> Response:
        return self.request("GET", path, **kwargs)

    def put(self, path: str, **kwargs: Any) -> Response:
        return self.request("PUT", path, **kwargs)

    def post(self, path: str, **kwargs: Any) -> Response:
        return self.request("POST", path, **kwargs)

    def patch(self, path: str, **kwargs: Any) -> Response:
        return self.request("PATCH", path, **kwargs)

    def delete(self, path: str, **kwargs: Any) -> Response:
        return self.request("DELETE", path, **kwargs)

    def head(self, path: str, **kwargs: Any) -> Response:
        return self.request("HEAD", path, **kwargs)

    def admin(self, method: str, path: str, **kwargs: Any) -> Response:
        return self.request(method, path, token=self.admin_token, **kwargs)

    # --- accounts ---

    def login(self, email: str, password: str, expect: int | tuple[int, ...] | None = None) -> Response:
        return self.post("/v1/auth/login", json={"email": email, "password": password}, expect=expect)

    def new_account(self, collection: str, label: str) -> Account:
        email = f"{label}-{uuid.uuid4().hex[:8]}@example.com"
        body = {
            "email": email,
            "password": PASSWORD,
            "passwordConfirm": PASSWORD,
            "verified": True,
        }
        if collection == "workers":
            body["name"] = f"worker-{label}"
        created = self.admin("POST", f"/api/collections/{collection}/records", json=body, expect=200).json()
        token = self.login(email, PASSWORD, expect=200).json()["token"]
        return Account(created["id"], email, PASSWORD, token)

    def new_user(self, label: str = "user") -> Account:
        return self.new_account("users", label)

    def new_worker(self, label: str = "worker") -> Account:
        return self.new_account("workers", label)

    @property
    def user_a(self) -> Account:
        return self.users["a"]

    @property
    def user_b(self) -> Account:
        return self.users["b"]

    @property
    def worker(self) -> Account:
        """The account the tests play a worker with."""

        return self.workers["fake"]

    @property
    def other_worker(self) -> Account:
        return self.workers["other"]

    # --- recordings ---

    def put_recording(
        self,
        token: str | Account,
        recording_id: str,
        meta: Mapping[str, Any] | None = None,
        *,
        expect: int | tuple[int, ...] = (200, 201),
        **meta_options: Any,
    ) -> Response:
        meta = meta if meta is not None else fx.meta(recording_id, **meta_options)
        return self.put(f"/v1/recordings/{recording_id}", token=token, json=dict(meta), expect=expect)

    def upload(
        self,
        token: str | Account,
        recording_id: str,
        name: str,
        data: bytes,
        *,
        chunk_size: int = 4096,
        compress: bool = False,
    ) -> int:
        """Append data to a file chunk by chunk and return the stored size."""

        offset = 0
        for chunk in fx.chunks(data, chunk_size):
            body = gzip.compress(chunk, mtime=0) if compress else chunk
            headers = {"Upload-Offset": str(offset)}
            if compress:
                headers["Content-Encoding"] = "gzip"
            response = self.patch(
                f"/v1/recordings/{recording_id}/files/{name}",
                token=token,
                data=body,
                headers=headers,
                expect=204,
            )
            offset = int(response.header("Upload-Offset") or -1)
            assert offset == int(headers["Upload-Offset"]) + len(body)
        return offset

    def complete_recording(self, token: str | Account, recording_id: str, expect: int = 200) -> Response:
        return self.patch(f"/v1/recordings/{recording_id}", token=token, json={"complete": True}, expect=expect)

    def make_recording(
        self,
        token: str | Account,
        recording_id: str | None = None,
        *,
        files: Mapping[str, Any] | None = None,
        hr_frames: int = 3,
        compress: bool = False,
        chunk_size: int = 4096,
        complete: bool = True,
        state: str = "stopped",
    ) -> str:
        """Create a recording, upload its files and seal it; returns its id."""

        recording_id = recording_id or unique("rec")
        files = dict(files) if files is not None else fx.recording(recording_id, hr_frames=hr_frames, state=state)
        self.put_recording(token, recording_id, files["meta"], expect=(200, 201))
        for name in ("hr.log", "ecg.log", "acc.log", "events.jsonl"):
            data = files.get(name)
            if isinstance(data, bytes) and data:
                self.upload(token, recording_id, name, data, compress=compress, chunk_size=chunk_size)
        if complete:
            self.complete_recording(token, recording_id)
        return recording_id

    def recording_dir(self, recording_id: str) -> Path:
        return self.data_dir / "recordings" / recording_id

    # --- workers and the queue ---

    def offer(self, worker: Account, *manifests: Mapping[str, Any]) -> None:
        """Announce algorithms for a worker, replacing what it announced before."""

        self.put(
            "/v1/workers/me",
            token=worker,
            json={"name": f"test-{worker.id}", "version": "0.0.1", "algorithms": list(manifests)},
            expect=204,
        )

    def claim(
        self,
        worker: Account,
        algorithm: str | None = None,
        *,
        version: str | None = None,
        lease_seconds: int = 60,
        expect: int | tuple[int, ...] = (200, 204),
    ) -> Response:
        body: dict[str, Any] = {"lease_seconds": lease_seconds}
        if algorithm is not None:
            entry = {"id": algorithm}
            if version is not None:
                entry["version"] = version
            body["algorithms"] = [entry]
        return self.post("/v1/analyses/claim", token=worker, json=body, expect=expect)

    def request_analysis(
        self,
        token: str | Account,
        recording_id: str,
        algorithm: str,
        *,
        version: str | None = None,
        params: Mapping[str, Any] | None = None,
        expect: int | tuple[int, ...] = (200, 201),
    ) -> Response:
        body: dict[str, Any] = {"algorithm": algorithm}
        if version is not None:
            body["version"] = version
        if params is not None:
            body["params"] = dict(params)
        return self.post(f"/v1/recordings/{recording_id}/analyses", token=token, json=body, expect=expect)

    def analysis(self, token: str | Account, analysis_id: str) -> dict[str, Any]:
        return self.get(f"/v1/analyses/{analysis_id}", token=token, expect=200).json()

    def drain_queue(self) -> None:
        """Remove every analysis that could still be claimed or leased."""

        listing = self.admin("GET", "/api/collections/analyses/records?perPage=500", expect=200).json()
        for item in listing["items"]:
            if item["status"] in ("queued", "running"):
                self.admin("DELETE", f"/api/collections/analyses/records/{item['id']}", expect=204)

    def worker_env(self, **changes: str) -> dict[str, str]:
        account = self.workers["real"]
        env = os.environ.copy()
        env.update(
            {
                "TAIGA_API_URL": self.base_url,
                "TAIGA_WORKER_EMAIL": account.email,
                "TAIGA_WORKER_PASSWORD": account.password,
                "TAIGA_WORKER_ID": "integration-worker",
                "TAIGA_JOB_MEMORY_MB": "512",
                "TAIGA_JOB_TIMEOUT_S": "120",
                "TAIGA_LOG_LEVEL": "INFO",
                "TAIGA_ALGORITHMS": "baseline",
            }
        )
        env.update(changes)
        return env

    def run_worker_once(self) -> str:
        """Run the real worker for one claim; returns what it logged."""

        result = subprocess.run(
            [sys.executable, "-m", "taiga_worker", "--once"],
            cwd=WORKER_DIR,
            env=self.worker_env(),
            capture_output=True,
            text=True,
            timeout=120,
        )
        if result.returncode != 0:
            self.dump_log()
            raise AssertionError(f"worker exited with {result.returncode}\n{result.stderr}")
        return result.stderr

    @contextmanager
    def running_worker(self, **env: str) -> Iterator["WorkerProcess"]:
        """Run the real worker as a service (not ``--once``) and stop it with SIGTERM afterwards.

        It logs at DEBUG, which is where it says that it is waiting for work.
        """

        process = subprocess.Popen(
            [sys.executable, "-m", "taiga_worker"],
            cwd=WORKER_DIR,
            env=self.worker_env(**{"TAIGA_LOG_LEVEL": "DEBUG", "PYTHONUNBUFFERED": "1", **env}),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        worker = WorkerProcess(process)
        try:
            yield worker
        finally:
            worker.stop()

    def wait_for_status(
        self,
        token: str | Account,
        analysis_id: str,
        statuses: tuple[str, ...],
        timeout_s: float = 60.0,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_s
        while True:
            analysis = self.analysis(token, analysis_id)
            if analysis["status"] in statuses or time.monotonic() > deadline:
                return analysis
            time.sleep(0.2)


class WorkerProcess:
    """The real worker running as a service; its log is collected in the background."""

    def __init__(self, process: "subprocess.Popen[str]") -> None:
        self.process = process
        self.stop_seconds: float | None = None
        self._lines: list[str] = []
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()

    def _read(self) -> None:
        assert self.process.stderr is not None
        for line in self.process.stderr:
            self._lines.append(line)

    @property
    def log(self) -> str:
        return "".join(self._lines)

    @property
    def returncode(self) -> int | None:
        return self.process.returncode

    def wait_for_log(self, text: str, timeout_s: float = 30.0, *, times: int = 1) -> None:
        """Block until the log holds ``text`` ``times`` times, or fail with the log."""

        deadline = time.monotonic() + timeout_s
        while self.log.count(text) < times:
            if self.process.poll() is not None or time.monotonic() > deadline:
                raise AssertionError(f"the worker never logged {text!r} ({times}x); its log:\n{self.log}")
            time.sleep(0.05)

    def stop(self, timeout_s: float = 15.0) -> None:
        """SIGTERM the worker and wait for it; ``stop_seconds`` is how long that took."""

        started = time.monotonic()
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=timeout_s)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
                self._reader.join(5)
                raise AssertionError(f"the worker did not stop on SIGTERM; its log:\n{self.log}") from None
        self._reader.join(5)
        self.stop_seconds = time.monotonic() - started


class CuttableProxy:
    """A TCP forwarder in front of the API that can be cut and restored.

    A client that talks to the API through it loses its connections when it is cut and is
    refused until it is restored, which is what a restarted API or a dropped network
    looks like to it.
    """

    def __init__(self, target_port: int) -> None:
        self._target = ("127.0.0.1", target_port)
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(16)
        self.url = f"http://127.0.0.1:{self._listener.getsockname()[1]}"
        self._forwarding = True
        self._sockets: list[socket.socket] = []
        self._lock = threading.Lock()
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self) -> None:
        while True:
            try:
                client, _ = self._listener.accept()
            except OSError:
                return
            if not self._forwarding:
                client.close()
                continue
            try:
                upstream = socket.create_connection(self._target, timeout=5)
            except OSError:
                client.close()
                continue
            upstream.settimeout(None)
            with self._lock:
                self._sockets += [client, upstream]
            for source, sink in ((client, upstream), (upstream, client)):
                threading.Thread(target=self._pump, args=(source, sink), daemon=True).start()

    @staticmethod
    def _pump(source: socket.socket, sink: socket.socket) -> None:
        try:
            while data := source.recv(65536):
                sink.sendall(data)
        except OSError:
            pass
        finally:
            for end in (source, sink):
                try:
                    end.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def cut(self) -> None:
        """Drop every connection and refuse new ones until :meth:`restore`."""

        self._forwarding = False
        with self._lock:
            sockets, self._sockets = self._sockets, []
        for end in sockets:
            try:
                end.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def restore(self) -> None:
        self._forwarding = True

    def close(self) -> None:
        self.cut()
        try:
            self._listener.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self._listener.close()


def manifest(algorithm: str, version: str = "1.0.0", **changes: Any) -> dict[str, Any]:
    """What a worker announces about an algorithm (docs/analysis-contract.md)."""

    value: dict[str, Any] = {
        "id": algorithm,
        "version": version,
        "name": f"Test {algorithm}",
        "description": "An algorithm announced by the integration tests.",
        "license": "MIT",
        "citation": None,
        "homepage": None,
        "needs": [{"name": "hr", "required": True, "min_rate_hz": None, "max_rate_hz": None}],
        "epoch_seconds": 30,
        "stage_scheme": "aasm4",
        "supported_stages": ["wake", "rem", "light", "deep", "unknown"],
        "capabilities": ["stages"],
        "default_params": {},
        "params_schema": {"type": "object"},
        "source": "builtin",
    }
    value.update(changes)
    return value


def result(algorithm: str, version: str = "1.0.0", **changes: Any) -> dict[str, Any]:
    """The smallest analysis result the API accepts (docs/analysis-contract.md)."""

    value: dict[str, Any] = {
        "contract_version": 3,
        "model": algorithm,
        "model_version": version,
        "params": {},
        "params_hash": "",
        "stages": {
            "epoch_seconds": 30,
            "start_unix_ms": 1_792_798_200_000,
            "count": 4,
            "segments": [{"stage": "wake", "count": 1}, {"stage": "light", "count": 3}],
        },
        "hr": {
            "epoch_seconds": 30,
            "count": 4,
            "mean_bpm": [61.0, 58.5, None, 57.0],
            "sample_count": [30, 30, 0, 29],
        },
        "metrics": {"total_duration_s": 120.0},
        "metric_support": {},
        "quality": {"score": 100.0},
        "sleep_quality": {"score": 78.0},
        "capabilities": {"stages": True},
        "provenance": {"worker": "integration-test"},
        "extras": {"anything": [1, {"else": True}]},
        "warnings": [],
    }
    value.update(changes)
    return value
