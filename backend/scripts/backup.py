#!/usr/bin/env python3
"""Download a consistent backup of the whole PocketBase data directory.

PocketBase's backup API snapshots the database and the stored files together,
which is important: copying only `data.db` loses the uploaded recordings.

Usage:
    TAIGA_ADMIN_EMAIL=you@example.com ./backup.sh /srv/backups

The password is read from TAIGA_ADMIN_PASSWORD or prompted for. The backup is
downloaded and validated, then removed from the server by default so backups do
not accumulate inside pb_data. Pass --keep-remote to keep it there.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


class BackupError(RuntimeError):
    pass


def request(
    base_url: str,
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    token: str | None = None,
) -> Any:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = token

    req = urllib.request.Request(base_url.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            raw = response.read()
            if not raw:
                return None
            content_type = response.headers.get("Content-Type", "")
            if "application/json" in content_type:
                return json.loads(raw)
            return raw
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        message = f"HTTP {exc.code}"
        try:
            payload = json.loads(raw)
            message = payload.get("message") or message
        except (ValueError, TypeError):
            pass
        raise BackupError(f"{method} {path}: {message}") from exc
    except (urllib.error.URLError, OSError) as exc:
        raise BackupError(f"{method} {path}: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "destination",
        nargs="?",
        default="backups",
        help="directory for the downloaded backup (default: ./backups)",
    )
    parser.add_argument(
        "--api-url",
        default=os.environ.get("TAIGA_API_URL", "http://127.0.0.1:8090"),
        help="PocketBase base URL (env: TAIGA_API_URL)",
    )
    parser.add_argument(
        "--email",
        default=os.environ.get("TAIGA_ADMIN_EMAIL"),
        help="superuser email (env: TAIGA_ADMIN_EMAIL)",
    )
    parser.add_argument(
        "--keep-remote",
        action="store_true",
        help="leave the archive on the server as well as downloading it",
    )
    args = parser.parse_args(argv)

    if not args.email:
        print("backup: TAIGA_ADMIN_EMAIL or --email is required", file=sys.stderr)
        return 2

    password = os.environ.get("TAIGA_ADMIN_PASSWORD")
    if not password:
        password = getpass.getpass(f"PocketBase password for {args.email}: ")
    if not password:
        print("backup: empty password", file=sys.stderr)
        return 2

    dest = Path(args.destination)
    dest.mkdir(parents=True, exist_ok=True)

    try:
        auth = request(
            args.api_url,
            "POST",
            "/api/collections/_superusers/auth-with-password",
            {"identity": args.email, "password": password},
        )
        token = auth.get("token") if isinstance(auth, dict) else None
        if not token:
            raise BackupError("login did not return a token")

        before = request(args.api_url, "GET", "/api/backups", token=token) or []
        before_keys = {item["key"] for item in before}

        request(args.api_url, "POST", "/api/backups", {}, token=token)

        backup = None
        for _ in range(60):
            entries = request(args.api_url, "GET", "/api/backups", token=token) or []
            new_entries = [item for item in entries if item.get("key") not in before_keys]
            if new_entries:
                backup = max(new_entries, key=lambda item: item.get("modified", ""))
                break
            time.sleep(1)
        if backup is None:
            raise BackupError("server did not finish the backup in 60 seconds")

        file_token_payload = request(args.api_url, "POST", "/api/files/token", token=token)
        file_token = file_token_payload.get("token") if isinstance(file_token_payload, dict) else None
        if not file_token:
            raise BackupError("could not obtain a file token")

        archive = request(
            args.api_url,
            "GET",
            f"/api/backups/{backup['key']}?token={file_token}",
        )
        if not isinstance(archive, bytes) or not archive:
            raise BackupError("backup download was empty")

        stamp = time.strftime("%Y%m%d-%H%M%S")
        target = dest / f"taiga-sleep-backup-{stamp}.zip"
        with tempfile.NamedTemporaryFile(dir=dest, suffix=".part", delete=False) as handle:
            handle.write(archive)
            temp_name = handle.name
        Path(temp_name).replace(target)

        if not zipfile.is_zipfile(target):
            target.unlink(missing_ok=True)
            raise BackupError("downloaded file is not a valid zip archive")

        print(f"wrote {target} ({target.stat().st_size} bytes)")

        if not args.keep_remote:
            try:
                request(args.api_url, "DELETE", f"/api/backups/{backup['key']}", token=token)
            except BackupError as exc:
                print(f"warning: could not remove remote backup: {exc}", file=sys.stderr)

        return 0
    except BackupError as exc:
        print(f"backup failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
