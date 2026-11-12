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
    """Small stdlib-only client for the worker's side of the API (docs/api.md).

    Recordings come from PocketBase's record API, whose rules let a worker read one
    while it holds the lease of an analysis of it; the queue, the recording files and
    the realtime stream are the API's own routes under /v1 and PocketBase's /api.
    Every error has PocketBase's shape: {"status", "message", "data"}.
    """

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
                    data = payload.get("data")
                    if isinstance(data, dict) and data.get("code"):
                        code = str(data["code"])
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

    def open_realtime(self, timeout_s: float = 360.0) -> Any:
        """Open PocketBase's realtime stream (GET /api/realtime) and return the response.

        The caller reads it line by line, subscribes with the client id of its first
        message and closes it. ``timeout_s`` is how long the stream may stay silent
        before a read fails; PocketBase closes a connection that sent nothing for five
        minutes, so a connection that stays quiet longer than that is dead.
        """

        return self._open("GET", "/api/realtime", accept="text/event-stream", timeout_s=timeout_s)

    def subscribe_realtime(self, client_id: str, subscriptions: list[str]) -> None:
        """Subscribe the open realtime connection to the topics changes are read from."""

        self._request(
            "POST",
            "/api/realtime",
            {"clientId": client_id, "subscriptions": list(subscriptions)},
        )

    def login(self, email: str, password: str) -> str:
        payload = self._request(
            "POST",
            "/api/collections/workers/auth-with-password",
            {"identity": email, "password": password},
            auth=False,
        )
        if not isinstance(payload, dict) or not payload.get("token"):
            raise ApiError("login response did not contain a token")
        record = payload.get("record")
        if not isinstance(record, dict) or not record.get("id"):
            raise ApiError("login response did not contain the worker account")
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

    def renew(self, analysis_id: str, lease_seconds: int) -> dict[str, Any]:
        """Extend the lease on the analysis this worker is running."""

        payload = self._request(
            "POST",
            f"/v1/analyses/{quote(analysis_id, safe='')}/lease",
            {"lease_seconds": lease_seconds},
        )
        return payload if isinstance(payload, dict) else {}

    def supports_lease_renewal(self) -> bool:
        """Whether this API has the lease renewal route; an older one does not."""

        payload = self._request("GET", "/v1/openapi.json", auth=False)
        paths = payload.get("paths") if isinstance(payload, dict) else None
        return isinstance(paths, dict) and "/v1/analyses/{id}/lease" in paths

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

        payload = self._request(
            "GET", f"/api/collections/recordings/records/{quote(recording_id, safe='')}"
        )
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
