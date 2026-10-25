from __future__ import annotations

import json
import shutil
import socket
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import quote, urlencode, urljoin

from . import __version__


class ApiError(RuntimeError):
    """An error returned by (or while talking to) the PocketBase API."""

    def __init__(self, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


class ApiClient:
    """Small stdlib-only PocketBase client for the worker routes."""

    def __init__(self, base_url: str, timeout_s: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/") + "/"
        self.timeout_s = timeout_s
        self.token: str | None = None

    def _request(
        self,
        method: str,
        path: str,
        body: Mapping[str, Any] | None = None,
        auth: bool = True,
    ) -> Any:
        data = None
        headers = {
            "Accept": "application/json",
            "User-Agent": f"taiga-sleep-worker/{__version__}",
        }
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if auth:
            if not self.token:
                raise ApiError("not authenticated", status=401)
            headers["Authorization"] = self.token

        request = urllib.request.Request(
            urljoin(self.base_url, path.lstrip("/")),
            data=data,
            headers=headers,
            method=method,
        )

        try:
            with urllib.request.urlopen(request, timeout=self.timeout_s) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            message = f"HTTP {exc.code}"
            try:
                payload = json.loads(raw)
                if isinstance(payload, dict) and payload.get("message"):
                    message = str(payload["message"])
            except (ValueError, TypeError):
                pass
            raise ApiError(message, status=exc.code) from exc
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
            raise ApiError(f"cannot reach {self.base_url}: {exc}") from exc

        if not raw:
            return None
        try:
            return json.loads(raw)
        except ValueError as exc:
            raise ApiError("API returned invalid JSON") from exc

    def login(self, email: str, password: str) -> str:
        payload = self._request(
            "POST",
            "/api/collections/workers/auth-with-password",
            {"identity": email, "password": password},
            auth=False,
        )
        if not isinstance(payload, dict) or not payload.get("token"):
            raise ApiError("login response did not contain a token")
        self.token = str(payload["token"])
        return self.token

    def auth_check(self) -> dict[str, Any]:
        payload = self._request("GET", "/api/taiga/auth-check")
        return payload if isinstance(payload, dict) else {}

    def claim(
        self,
        worker: str,
        lease_seconds: int,
        algorithms: list[str] | None = None,
    ) -> dict[str, Any] | None:
        body: dict[str, Any] = {"worker": worker, "lease_seconds": lease_seconds}
        if algorithms is not None:
            body["algorithms"] = list(algorithms)
        payload = self._request("POST", "/api/taiga/worker/jobs/claim", body)
        if not isinstance(payload, dict):
            raise ApiError("claim response was not an object")
        job = payload.get("job")
        if job is None:
            return None
        if not isinstance(job, dict) or not job.get("id"):
            raise ApiError("claim response contained an invalid job")
        return job

    def finish(
        self,
        job_id: str,
        worker: str,
        status: str,
        result: Mapping[str, Any] | None = None,
        error: str | None = None,
        retryable: bool | None = None,
    ) -> dict[str, Any]:
        if status not in {"done", "failed"}:
            raise ValueError("status must be 'done' or 'failed'")
        body: dict[str, Any] = {
            "worker": worker,
            "status": status,
            "result": dict(result) if result is not None else None,
            "error": error or "",
        }
        if retryable is not None:
            body["retryable"] = bool(retryable)
        payload = self._request(
            "POST",
            f"/api/taiga/worker/jobs/{job_id}/result",
            body,
        )
        return payload if isinstance(payload, dict) else {}

    def report_capabilities(
        self,
        worker: str,
        version: str,
        algorithms: list[dict[str, Any]],
    ) -> dict[str, Any]:
        payload = self._request(
            "POST",
            "/api/taiga/worker/capabilities",
            {"worker": worker, "version": version, "algorithms": algorithms},
        )
        return payload if isinstance(payload, dict) else {}

    def manifest(self, session_id: str, job_id: str, worker: str) -> dict[str, Any]:
        query = urlencode({"job": job_id, "worker": worker})
        payload = self._request(
            "GET",
            f"/api/taiga/worker/sessions/{quote(session_id, safe='')}/manifest?{query}",
        )
        return payload if isinstance(payload, dict) else {}

    def download_file(
        self,
        session_id: str,
        name: str,
        destination: Path,
        job_id: str,
        worker: str,
    ) -> None:
        if not self.token:
            raise ApiError("not authenticated", status=401)
        query = urlencode({"job": job_id, "worker": worker})
        url = urljoin(
            self.base_url,
            f"api/taiga/worker/sessions/{quote(session_id, safe='')}/files/"
            f"{quote(name, safe='')}?{query}",
        )
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/octet-stream",
                "Authorization": self.token,
                "User-Agent": f"taiga-sleep-worker/{__version__}",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=max(self.timeout_s, 300.0)) as response:
                with Path(destination).open("wb") as handle:
                    shutil.copyfileobj(response, handle)
        except urllib.error.HTTPError as exc:
            message = f"HTTP {exc.code}"
            try:
                payload = json.loads(exc.read())
                if isinstance(payload, dict) and payload.get("message"):
                    message = str(payload["message"])
            except (ValueError, TypeError):
                pass
            raise ApiError(message, status=exc.code) from exc
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
            raise ApiError(f"cannot download {name}: {exc}") from exc

    def requeue_expired(self) -> int:
        payload = self._request("POST", "/api/taiga/worker/jobs/requeue", {})
        if not isinstance(payload, dict):
            return 0
        try:
            return int(payload.get("requeued", 0))
        except (TypeError, ValueError):
            return 0
