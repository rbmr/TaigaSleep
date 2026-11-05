from __future__ import annotations

import http.client
import json
import shutil
import socket
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import quote, urljoin

from . import __version__


class ApiError(RuntimeError):
    """An error returned by (or while talking to) the Taiga Sleep API."""

    def __init__(self, message: str, status: int | None = None, code: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code


class ApiClient:
    """Small stdlib-only client for the worker's side of API v1 (docs/api.md)."""

    def __init__(self, base_url: str, timeout_s: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/") + "/"
        self.timeout_s = timeout_s
        self.token: str | None = None

    def _open(
        self,
        method: str,
        path: str,
        body: Mapping[str, Any] | None = None,
        auth: bool = True,
        accept: str = "application/json",
        timeout_s: float | None = None,
    ) -> Any:
        """Send one request and return the open response, or raise ApiError.

        No Accept-Encoding is sent, so the API inflates a file it stores compressed
        and every download is the original bytes.
        """

        data = None
        headers = {
            "Accept": accept,
            "User-Agent": f"taiga-sleep-worker/{__version__}",
        }
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if auth:
            if not self.token:
                raise ApiError("not authenticated", status=401)
            headers["Authorization"] = f"Bearer {self.token}"

        request = urllib.request.Request(
            urljoin(self.base_url, path.lstrip("/")),
            data=data,
            headers=headers,
            method=method,
        )

        try:
            return urllib.request.urlopen(request, timeout=timeout_s or self.timeout_s)
        except urllib.error.HTTPError as exc:
            message, code = f"HTTP {exc.code}", None
            try:
                payload = json.loads(exc.read())
                if isinstance(payload, dict):
                    message = str(payload.get("message") or message)
                    code = str(payload["code"]) if payload.get("code") else None
            except (ValueError, TypeError, OSError):
                pass
            raise ApiError(message, status=exc.code, code=code) from exc
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
            raise ApiError(f"cannot reach {self.base_url}: {exc}") from exc

    def _request(
        self,
        method: str,
        path: str,
        body: Mapping[str, Any] | None = None,
        auth: bool = True,
    ) -> Any:
        try:
            with self._open(method, path, body, auth) as response:
                raw = response.read()
        except (socket.timeout, TimeoutError, OSError, http.client.HTTPException) as exc:
            raise ApiError(f"cannot read the answer of {self.base_url}: {exc}") from exc

        if not raw:
            return None
        try:
            return json.loads(raw)
        except ValueError as exc:
            raise ApiError("API returned invalid JSON") from exc

    def login(self, email: str, password: str) -> str:
        payload = self._request(
            "POST",
            "/v1/auth/login",
            {"email": email, "password": password},
            auth=False,
        )
        if not isinstance(payload, dict) or not payload.get("token"):
            raise ApiError("login response did not contain a token")
        if payload.get("role") != "worker":
            raise ApiError("the account is not a worker account")
        self.token = str(payload["token"])
        return self.token

    def announce(
        self,
        name: str,
        version: str,
        algorithms: list[dict[str, Any]],
    ) -> None:
        """Say what this worker can run; also tells the API that it is alive."""

        self._request(
            "PUT",
            "/v1/workers/me",
            {"name": name, "version": version, "algorithms": algorithms},
        )

    def claim(
        self,
        algorithms: list[dict[str, str]],
        lease_seconds: int,
    ) -> dict[str, Any] | None:
        """Take the oldest queued analysis this worker can run, or None."""

        payload = self._request(
            "POST",
            "/v1/analyses/claim",
            {"algorithms": list(algorithms), "lease_seconds": lease_seconds},
        )
        if payload is None:
            return None
        if not isinstance(payload, dict) or not payload.get("id") or not payload.get("recording"):
            raise ApiError("claim response was not an analysis")
        return payload

    def complete(self, analysis_id: str, result: Mapping[str, Any]) -> dict[str, Any]:
        payload = self._request(
            "POST",
            f"/v1/analyses/{quote(analysis_id, safe='')}/complete",
            {"result": dict(result)},
        )
        return payload if isinstance(payload, dict) else {}

    def fail(self, analysis_id: str, error: str, retryable: bool) -> dict[str, Any]:
        payload = self._request(
            "POST",
            f"/v1/analyses/{quote(analysis_id, safe='')}/fail",
            {"error": error, "retryable": bool(retryable)},
        )
        return payload if isinstance(payload, dict) else {}

    def recording(self, recording_id: str) -> dict[str, Any]:
        """The recording, including its meta; readable while the lease is held."""

        payload = self._request("GET", f"/v1/recordings/{quote(recording_id, safe='')}")
        if not isinstance(payload, dict):
            raise ApiError("recording response was not an object")
        return payload

    def files(self, recording_id: str) -> list[dict[str, Any]]:
        payload = self._request("GET", f"/v1/recordings/{quote(recording_id, safe='')}/files")
        items = payload.get("items") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            raise ApiError("files response did not contain a list")
        return [item for item in items if isinstance(item, dict)]

    def download_file(self, recording_id: str, name: str, destination: Path) -> None:
        path = f"/v1/recordings/{quote(recording_id, safe='')}/files/{quote(name, safe='')}"
        try:
            with self._open(
                "GET", path, accept="application/octet-stream", timeout_s=max(self.timeout_s, 300.0)
            ) as response:
                with Path(destination).open("wb") as handle:
                    shutil.copyfileobj(response, handle)
        except (socket.timeout, TimeoutError, OSError, http.client.HTTPException) as exc:
            raise ApiError(f"cannot download {name}: {exc}") from exc
