"""End-to-end tests for API v1 and the worker.

The suite starts the real Go API in a temporary data directory (point POCKETBASE_BIN at
the binary built with ``go build -trimpath``) and talks to it over HTTP the way the app
and the worker do. The worker tests run the real worker as a subprocess on synthetic
recordings. See ``tests/harness.py`` for the helpers and docs/api.md for the contract.
"""

from __future__ import annotations

import gzip
import json
import re
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from typing import Any

import pytest

from tests import recording_fixture as fx
from tests.harness import (
    PASSWORD,
    WORKER_DIR,
    Account,
    Backend,
    CuttableProxy,
    Response,
    manifest,
    result,
    unique,
)

T0_MS = 1_792_798_200_000  # the start every synthetic recording has


def iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def assert_error(response: Response, status: int, code: str) -> dict[str, Any]:
    """Every error of every endpoint has the same body: {"code", "message"}."""

    assert response.status == status, response.body[:300]
    assert (response.header("Content-Type") or "").startswith("application/json")
    body = response.json()
    assert set(body) == {"code", "message"}
    assert body["code"] == code
    assert re.fullmatch(r"[a-z_]+", body["code"])
    assert isinstance(body["message"], str) and body["message"]
    return body


def queued_analysis(
    api: Backend,
    *,
    params: dict[str, Any] | None = None,
    recording: str | None = None,
    algorithm: str | None = None,
    version: str = "1.0.0",
) -> tuple[str, str, dict[str, Any]]:
    """A queued analysis of a sealed recording on an algorithm the fake worker offers."""

    algorithm = algorithm or unique("alg")
    api.offer(api.worker, manifest(algorithm, version))
    recording = recording or api.make_recording(api.user_a)
    created = api.request_analysis(api.user_a, recording, algorithm, params=params, expect=201).json()
    return algorithm, recording, created


# ---------------------------------------------------------------------------
# System
# ---------------------------------------------------------------------------


def test_health_and_version_are_public(api: Backend) -> None:
    health = api.get("/v1/health", expect=200).json()
    assert health["status"] == "ok"
    assert health["time"]

    version = api.get("/v1/version", expect=200).json()
    assert version["service"] == "taiga-sleep"
    assert version["version"]
    # The app refuses a server whose contract revision differs from its own.
    assert isinstance(version["api"], int) and version["api"] > 0


def test_docs_page_and_assets_are_served(api: Backend) -> None:
    """Swagger UI is served by the API itself, with no CDN or extra service."""

    page = api.get("/v1/docs", expect=200).body
    assert b"SwaggerUIBundle(" in page
    assert b'url: "/v1/openapi.json"' in page
    assert b"/v1/docs/swagger-ui.css" in page
    assert b".swagger-ui" in api.get("/v1/docs/swagger-ui.css", expect=200).body
    assert b"SwaggerUIBundle" in api.get("/v1/docs/swagger-ui-bundle.js", expect=200).body
    assert_error(api.get("/v1/docs/not-an-asset"), 404, "not_found")


# ---------------------------------------------------------------------------
# Authentication and roles
# ---------------------------------------------------------------------------


def test_login_gives_a_token_and_the_role_of_the_account(api: Backend) -> None:
    user = api.login(api.user_a.email, PASSWORD, expect=200).json()
    assert user["role"] == "user"
    assert user["user"] == {"id": api.user_a.id, "email": api.user_a.email}
    assert user["token"]

    worker = api.login(api.worker.email, PASSWORD, expect=200).json()
    assert worker["role"] == "worker"
    assert worker["user"] == {"id": api.worker.id, "email": api.worker.email}

    me = api.get("/v1/me", token=user["token"], expect=200).json()
    assert me == {"id": api.user_a.id, "email": api.user_a.email, "role": "user"}
    me = api.get("/v1/me", token=worker["token"], expect=200).json()
    assert me == {"id": api.worker.id, "email": api.worker.email, "role": "worker"}


def test_a_wrong_password_is_401_and_does_not_say_which_part_was_wrong(api: Backend) -> None:
    wrong_password = assert_error(api.login(api.user_a.email, "not-the-password", expect=401), 401, "unauthorized")
    unknown_user = assert_error(api.login("nobody@example.com", PASSWORD, expect=401), 401, "unauthorized")
    assert wrong_password["message"] == unknown_user["message"]

    assert_error(api.post("/v1/auth/login", json={}), 400, "invalid_body")
    assert_error(api.post("/v1/auth/login", data=b"not json"), 400, "invalid_body")
    # PocketBase's own login is closed, so there is one way to get a token.
    api.post(
        "/api/collections/users/auth-with-password",
        json={"identity": api.user_a.email, "password": PASSWORD},
        expect=(403, 404),
    )


def test_repeated_wrong_passwords_are_throttled(api: Backend) -> None:
    victim = api.new_user("throttled")

    for _ in range(10):
        api.login(victim.email, "wrong", expect=401)

    assert_error(api.login(victim.email, "wrong"), 429, "too_many_attempts")
    # Even the right password has to wait, but other accounts are not affected.
    assert_error(api.login(victim.email, PASSWORD), 429, "too_many_attempts")
    api.login(api.user_b.email, PASSWORD, expect=200)


def test_missing_and_invalid_tokens_are_401(api: Backend) -> None:
    assert_error(api.get("/v1/me"), 401, "unauthorized")
    assert_error(api.get("/v1/me", token="garbage"), 401, "unauthorized")
    assert_error(api.get("/v1/recordings"), 401, "unauthorized")
    assert_error(api.post("/v1/analyses/claim", json={}), 401, "unauthorized")
    assert api.head("/v1/recordings/x/files/hr.log").status == 401


def test_a_superuser_token_is_not_an_api_token(api: Backend) -> None:
    assert_error(api.get("/v1/me", token=api.admin_token), 403, "forbidden")
    assert_error(api.get("/v1/recordings", token=api.admin_token), 403, "forbidden")


def test_refresh_gives_a_working_token(api: Backend) -> None:
    account = api.new_user("refresh")

    refreshed = api.post("/v1/auth/refresh", token=account, expect=200).json()

    assert refreshed["role"] == "user"
    assert refreshed["user"]["id"] == account.id
    api.get("/v1/me", token=refreshed["token"], expect=200)
    assert_error(api.post("/v1/auth/refresh"), 401, "unauthorized")


def test_roles_are_enforced(api: Backend) -> None:
    user, worker = api.user_a, api.worker
    recording = api.make_recording(user)
    files = f"/v1/recordings/{recording}/files"

    worker_only = [
        ("PUT", "/v1/workers/me", {"name": "x", "version": "1", "algorithms": []}),
        ("POST", "/v1/analyses/claim", {}),
        ("POST", "/v1/analyses/someid/complete", {"result": {}}),
        ("POST", "/v1/analyses/someid/fail", {"error": "x"}),
    ]
    for method, path, body in worker_only:
        assert_error(api.request(method, path, token=user, json=body), 403, "forbidden")

    user_only = [
        ("GET", "/v1/recordings", None),
        ("PUT", f"/v1/recordings/{recording}", fx.meta(recording)),
        ("PATCH", f"/v1/recordings/{recording}", {"complete": True}),
        ("DELETE", f"/v1/recordings/{recording}", None),
        ("PATCH", f"{files}/hr.log", None),
        ("DELETE", f"{files}/hr.log", None),
        ("POST", f"/v1/recordings/{recording}/analyses", {"algorithm": "baseline"}),
        ("GET", f"/v1/recordings/{recording}/analyses", None),
        ("DELETE", "/v1/analyses/someid", None),
        ("GET", "/v1/algorithms", None),
    ]
    for method, path, body in user_only:
        assert_error(api.request(method, path, token=worker, json=body), 403, "forbidden")

    # Both roles may ask who they are; a worker only reads what it holds a lease for.
    api.get("/v1/me", token=worker, expect=200)
    assert_error(api.get(f"/v1/recordings/{recording}", token=worker), 404, "not_found")


def test_users_cannot_register_themselves(api: Backend) -> None:
    api.post(
        "/api/collections/users/records",
        json={"email": "stranger@example.com", "password": PASSWORD, "passwordConfirm": PASSWORD},
        expect=(400, 403, 404),
    )
    assert api.post("/v1/auth/register", json={"email": "x@example.com", "password": PASSWORD}).status == 404


def test_pocketbase_record_api_is_closed_for_every_client(api: Backend) -> None:
    recording = api.make_recording(api.user_a)
    paths = [
        "/api/collections/recordings/records",
        f"/api/collections/recordings/records/{recording}",
        "/api/collections/analyses/records",
        "/api/collections/workers/records",
        "/api/collections/users/records",
    ]
    for token in (api.user_a, api.user_b, api.worker, None):
        for path in paths:
            assert api.get(path, token=token).status in (400, 403, 404), (path, token)
        api.post(
            "/api/collections/recordings/records",
            token=token,
            json={"id": unique("native"), "owner": api.user_a.id},
            expect=(400, 403, 404),
        )
        api.delete(f"/api/collections/recordings/records/{recording}", token=token, expect=(400, 403, 404))
        api.patch(
            f"/api/collections/recordings/records/{recording}",
            token=token,
            json={"complete": False},
            expect=(400, 403, 404),
        )

    # Nothing happened to the recording, and the operator's admin API still works.
    assert api.get(f"/v1/recordings/{recording}", token=api.user_a, expect=200).json()["complete"] is True
    api.admin("GET", "/api/collections/recordings/records", expect=200)


def test_every_v1_error_has_the_same_shape(api: Backend) -> None:
    user, worker = api.user_a, api.worker
    recording = api.make_recording(user)
    open_recording = unique("open")
    api.put_recording(user, open_recording, state="recording")

    cases: list[tuple[Response, int, str]] = [
        (api.get("/v1/me"), 401, "unauthorized"),
        (api.post("/v1/auth/login", json={}), 400, "invalid_body"),
        (api.get("/v1/recordings", token=worker), 403, "forbidden"),
        (api.get("/v1/recordings/nope", token=user), 404, "not_found"),
        (api.get("/v1/recordings?limit=0", token=user), 400, "invalid_query"),
        (api.put("/v1/recordings/bad%20id", token=user, json=fx.meta("bad-id")), 400, "invalid_id"),
        (
            api.put(f"/v1/recordings/{unique('x')}", token=user, json=fx.meta("another")),
            422,
            "invalid_meta",
        ),
        (
            api.put(f"/v1/recordings/{recording}", token=user, json=fx.meta(recording)),
            409,
            "recording_complete",
        ),
        (
            api.patch(f"/v1/recordings/{open_recording}", token=user, json={"complete": True}),
            409,
            "recording_in_progress",
        ),
        (
            api.patch(f"/v1/recordings/{recording}/files/hr.log", token=user, data=b"x"),
            400,
            "invalid_offset",
        ),
        (
            api.patch(
                f"/v1/recordings/{open_recording}/files/hr.log",
                token=user,
                data=b"x" * (1024 * 1024 + 1),
                headers={"Upload-Offset": "0"},
            ),
            413,
            "chunk_too_large",
        ),
        (api.get(f"/v1/recordings/{recording}/files/ecg.log.bak", token=user), 404, "not_found"),
        (
            api.post(f"/v1/recordings/{recording}/analyses", token=user, json={"algorithm": "no-such"}),
            422,
            "unknown_algorithm",
        ),
        (api.get("/v1/analyses/nope", token=user), 404, "not_found"),
        (api.post("/v1/analyses/claim", token=user, json={}), 403, "forbidden"),
        (
            api.post("/v1/analyses/nope/complete", token=worker, json={"result": {}}),
            404,
            "not_found",
        ),
    ]
    for response, status, code in cases:
        assert_error(response, status, code)

    # A HEAD answer has no body, so the status has to say it.
    assert api.head(f"/v1/recordings/{recording}/files/ecg.log.bak", token=user).status == 404


# ---------------------------------------------------------------------------
# Recordings
# ---------------------------------------------------------------------------


def test_put_creates_a_recording_then_replaces_its_metadata(api: Backend) -> None:
    recording = unique("put")
    created = api.put_recording(api.user_a, recording, fx.meta(recording, state="recording"), expect=201)

    assert created.header("Location") == f"/v1/recordings/{recording}"
    body = created.json()
    assert body["id"] == recording
    assert body["state"] == "recording"
    assert body["device"] == "Polar H10 TEST0001"
    assert body["started_at"] == iso(T0_MS)
    assert body["ended_at"] is None
    assert body["duration_s"] == 0
    assert body["complete"] is False
    assert body["size_bytes"] == 0
    assert body["meta"]["session_id"] == recording
    assert body["meta"]["state"] == "recording"
    assert body["meta"]["config"]["ecg_rate_hz"] == 130
    assert body["meta"]["sensor"]["kind"] == "polar-h10"
    for field in ("created", "updated"):
        datetime.strptime(body[field], "%Y-%m-%dT%H:%M:%SZ")

    # Repeating the request after a lost response is safe.
    again = api.put_recording(api.user_a, recording, fx.meta(recording, state="recording"), expect=200)
    assert again.json()["id"] == recording

    stopped = api.put_recording(
        api.user_a, recording, fx.meta(recording, state="stopped", duration_s=90), expect=200
    ).json()
    assert stopped["state"] == "stopped"
    assert stopped["ended_at"] == iso(T0_MS + 90_000)
    assert stopped["duration_s"] == 90
    assert stopped["meta"]["end_utc_ms"] == T0_MS + 90_000
    assert api.get(f"/v1/recordings/{recording}", token=api.user_a, expect=200).json() == stopped


def test_put_validates_the_id_and_the_metadata(api: Backend) -> None:
    user = api.user_a
    meta = fx.meta("valid-id")

    assert_error(api.put("/v1/recordings/bad%20id", token=user, json=meta), 400, "invalid_id")
    assert_error(api.put("/v1/recordings/" + "a" * 41, token=user, json=fx.meta("a" * 41)), 400, "invalid_id")
    assert_error(api.put("/v1/recordings/has.dot", token=user, json=fx.meta("has.dot")), 400, "invalid_id")
    api.put_recording(user, "a" * 40, expect=201)

    # The id in the path and the one in the metadata must be the same.
    assert_error(api.put(f"/v1/recordings/{unique('p')}", token=user, json=meta), 422, "invalid_meta")

    def invalid(**changes: Any) -> Response:
        broken = fx.meta("valid-id")
        broken.update(changes)
        return api.put("/v1/recordings/valid-id", token=user, json=broken)

    for changes in (
        {"format": "other"},
        {"version": 2},
        {"state": "paused"},
        {"start_utc_ms": 0},
        {"end_utc_ms": T0_MS - 1},
        {"end_utc_ms": None},  # a stopped recording has an end
        {"sensor": {"kind": ""}},
        {"config": {"hr": True}},
    ):
        assert_error(invalid(**changes), 422, "invalid_meta")
    assert_error(api.put("/v1/recordings/valid-id", token=user, data=b"not json"), 422, "invalid_meta")

    padding = {"padding": "x" * 70_000}
    assert_error(invalid(**padding), 413, "meta_too_large")
    assert api.get("/v1/recordings/valid-id", token=user).status == 404


def test_a_recording_id_belongs_to_one_account(api: Backend) -> None:
    recording = api.make_recording(api.user_a, complete=False)

    taken = api.put_recording(api.user_b, recording, expect=409)

    assert_error(taken, 409, "id_taken")
    # The other account sees nothing of it: not found, never forbidden.
    assert_error(api.get(f"/v1/recordings/{recording}", token=api.user_b), 404, "not_found")
    assert api.get(f"/v1/recordings/{recording}", token=api.user_a, expect=200).json()["id"] == recording


def test_recordings_are_private_to_their_owner(api: Backend) -> None:
    recording = api.make_recording(api.user_a, hr_frames=5, complete=False)
    sealed = api.make_recording(api.user_a)
    algorithm, _, analysis = queued_analysis(api, recording=sealed)
    stranger = api.user_b
    base = f"/v1/recordings/{recording}"

    for response in (
        api.get(base, token=stranger),
        api.patch(base, token=stranger, json={"complete": True}),
        api.delete(base, token=stranger),
        api.get(f"{base}/files", token=stranger),
        api.get(f"{base}/files/hr.log", token=stranger),
        api.patch(f"{base}/files/hr.log", token=stranger, data=b"x", headers={"Upload-Offset": "0"}),
        api.delete(f"{base}/files/hr.log", token=stranger),
        api.get(f"{base}/analyses", token=stranger),
        api.post(f"/v1/recordings/{sealed}/analyses", token=stranger, json={"algorithm": algorithm}),
        api.get(f"/v1/analyses/{analysis['id']}", token=stranger),
        api.delete(f"/v1/analyses/{analysis['id']}", token=stranger),
    ):
        assert_error(response, 404, "not_found")
    assert api.head(f"{base}/files/hr.log", token=stranger).status == 404

    listed = api.get("/v1/recordings", token=stranger, expect=200).json()
    assert recording not in [item["id"] for item in listed["items"]]
    analyses = api.get("/v1/analyses", token=stranger, expect=200).json()
    assert analysis["id"] not in [item["id"] for item in analyses["items"]]

    # The owner still has everything.
    assert api.get(f"{base}/files/hr.log", token=api.user_a, expect=200).body
    assert api.analysis(api.user_a, analysis["id"])["id"] == analysis["id"]


def test_list_filters_sorts_and_pages(api: Backend) -> None:
    owner = api.new_user("lister")
    day = 86_400_000
    specs = [  # (state, start, duration in s, sealed)
        ("stopped", T0_MS, 60, False),
        ("stopped", T0_MS + 1 * day, 30, True),
        ("recording", T0_MS + 2 * day, 0, False),
        ("forced_stop", T0_MS + 3 * day, 120, False),
        ("interrupted", T0_MS + 4 * day, 90, False),
    ]
    ids = []
    for state, start, duration, sealed in specs:
        recording = unique("list")
        files = fx.recording(recording, hr_frames=3, state=state)
        files["meta"] = fx.meta(recording, start_utc_ms=start, duration_s=duration, state=state)
        api.make_recording(owner, recording, files=files, complete=sealed)
        ids.append(recording)
        time.sleep(0.02)  # distinct creation times
    first, second, third, fourth, fifth = ids

    def listed(query: str = "") -> dict[str, Any]:
        return api.get(f"/v1/recordings{query}", token=owner, expect=200).json()

    def order(query: str = "") -> list[str]:
        return [item["id"] for item in listed(query)["items"]]

    everything = listed()
    assert (everything["total"], everything["limit"], everything["offset"]) == (5, 50, 0)
    assert order() == [fifth, fourth, third, second, first]  # newest start first
    assert order("?sort=started_at") == [first, second, third, fourth, fifth]
    assert order("?sort=-started_at") == [fifth, fourth, third, second, first]
    assert order("?sort=created") == [first, second, third, fourth, fifth]
    assert order("?sort=-created") == [fifth, fourth, third, second, first]
    assert order("?sort=duration_s") == [third, second, first, fifth, fourth]
    assert order("?sort=-duration_s") == [fourth, fifth, first, second, third]

    assert order("?state=stopped&sort=started_at") == [first, second]
    assert order("?state=recording") == [third]
    assert order("?state=forced_stop") == [fourth]
    assert order("?complete=true") == [second]
    assert sorted(order("?complete=false")) == sorted([first, third, fourth, fifth])
    assert order(f"?from={iso(T0_MS + 2 * day)}&sort=started_at") == [third, fourth, fifth]
    assert order(f"?to={iso(T0_MS + 2 * day)}&sort=started_at") == [first, second, third]
    assert order(f"?from={iso(T0_MS + day)}&to={iso(T0_MS + 3 * day)}&sort=started_at") == [
        second,
        third,
        fourth,
    ]
    assert order(f"?from={iso(T0_MS + 2 * day)[:10]}&sort=started_at") == [
        third,
        fourth,
        fifth,
    ]  # a date
    assert order("?state=stopped&complete=false") == [first]

    page = listed("?limit=2&offset=1&sort=started_at")
    assert [item["id"] for item in page["items"]] == [second, third]
    assert (page["total"], page["limit"], page["offset"]) == (5, 2, 1)
    assert order("?limit=2&offset=4&sort=started_at") == [fifth]
    assert listed("?offset=9")["items"] == [] and listed("?offset=9")["total"] == 5
    assert listed("?state=stopped&limit=1")["total"] == 2

    for query in (
        "?limit=0",
        "?limit=201",
        "?limit=many",
        "?offset=-1",
        "?sort=name",
        "?state=asleep",
        "?complete=maybe",
        "?from=yesterday",
        "?to=tomorrow",
    ):
        assert_error(api.get(f"/v1/recordings{query}", token=owner), 400, "invalid_query")

    # Another account sees none of these.
    others = api.get("/v1/recordings?limit=200", token=api.user_b, expect=200).json()["items"]
    assert not {item["id"] for item in others} & set(ids)


def test_a_recording_is_completed_once_it_is_finished_and_has_data(api: Backend) -> None:
    user = api.user_a
    recording = unique("seal")
    files = fx.recording(recording, hr_frames=5)
    path = f"/v1/recordings/{recording}"

    # Still recording: not yet.
    api.put_recording(user, recording, fx.meta(recording, state="recording"))
    api.upload(user, recording, "hr.log", files["hr.log"])
    assert_error(api.patch(path, token=user, json={"complete": True}), 409, "recording_in_progress")

    # Finished, but a recording without any stream data cannot be sealed.
    empty = unique("empty")
    api.put_recording(user, empty)
    api.upload(user, empty, "events.jsonl", files["events.jsonl"])
    assert_error(api.patch(f"/v1/recordings/{empty}", token=user, json={"complete": True}), 422, "no_data")

    # The body has to say what it means.
    for body in ({}, {"complete": "yes"}, {"state": "stopped"}):
        assert_error(api.patch(path, token=user, json=body), 400, "invalid_body")
    assert api.patch(path, token=user, json={"complete": False}, expect=200).json()["complete"] is False

    api.put_recording(user, recording, files["meta"], expect=200)
    sealed = api.complete_recording(user, recording).json()
    assert sealed["complete"] is True
    assert sealed["size_bytes"] == len(files["hr.log"])

    # Sealing again changes nothing; a sealed recording cannot be unsealed or edited.
    assert api.complete_recording(user, recording).json() == sealed
    assert_error(api.patch(path, token=user, json={"complete": False}), 409, "recording_complete")
    assert_error(api.put_recording(user, recording, files["meta"], expect=409), 409, "recording_complete")


def test_deleting_a_recording_deletes_its_files_and_analyses(api: Backend) -> None:
    recording = api.make_recording(api.user_a)
    algorithm, _, analysis = queued_analysis(api, recording=recording)
    running = api.request_analysis(api.user_a, recording, algorithm, params={"second": 1}, expect=201).json()
    assert api.claim(api.worker, algorithm, expect=200).json()["id"] == analysis["id"]
    assert api.recording_dir(recording).is_dir()

    api.delete(f"/v1/recordings/{recording}", token=api.user_a, expect=204)

    assert_error(api.get(f"/v1/recordings/{recording}", token=api.user_a), 404, "not_found")
    assert_error(api.get(f"/v1/analyses/{analysis['id']}", token=api.user_a), 404, "not_found")
    assert_error(api.get(f"/v1/analyses/{running['id']}", token=api.worker), 404, "not_found")
    assert not api.recording_dir(recording).exists()
    api.claim(api.worker, algorithm, expect=204)
    assert_error(api.delete(f"/v1/recordings/{recording}", token=api.user_a), 404, "not_found")
    # The id can be used again.
    api.put_recording(api.user_a, recording, expect=201)


# ---------------------------------------------------------------------------
# Recording files
# ---------------------------------------------------------------------------


def test_files_are_appended_at_the_upload_offset(api: Backend) -> None:
    user = api.user_a
    recording = unique("files")
    data = fx.recording(recording, hr_frames=20)["hr.log"]
    api.put_recording(user, recording, state="recording")
    path = f"/v1/recordings/{recording}/files/hr.log"

    # A file does not exist until its first chunk; no file is listed before that.
    assert api.head(path, token=user).status == 404
    assert api.get(f"/v1/recordings/{recording}/files", token=user, expect=200).json() == {"items": []}

    first = api.patch(path, token=user, data=data[:100], headers={"Upload-Offset": "0"}, expect=204)
    assert first.header("Upload-Offset") == "100"
    assert first.body == b""
    second = api.patch(path, token=user, data=data[100:], headers={"Upload-Offset": "100"}, expect=204)
    assert second.header("Upload-Offset") == str(len(data))

    head = api.head(path, token=user, expect=200)
    assert head.header("Upload-Offset") == str(len(data))
    assert head.header("Content-Encoding") is None

    listing = api.get(f"/v1/recordings/{recording}/files", token=user, expect=200).json()
    assert listing["items"] == [
        {
            "name": "hr.log",
            "size": len(data),
            "encoding": "identity",
            "uncompressed_size": len(data),
        }
    ]
    assert api.get(path, token=user, expect=200).body == data
    assert api.get(f"/v1/recordings/{recording}", token=user).json()["size_bytes"] == len(data)

    # The files of one recording are independent, and only the four data files exist.
    api.patch(
        f"/v1/recordings/{recording}/files/events.jsonl",
        token=user,
        data=b'{"type":"x"}\n',
        headers={"Upload-Offset": "0"},
        expect=204,
    )
    names = [item["name"] for item in api.get(f"/v1/recordings/{recording}/files", token=user).json()["items"]]
    assert names == ["hr.log", "events.jsonl"]
    for name in ("meta.json", "notes.txt", "hr.log.gz"):
        assert_error(
            api.patch(
                f"/v1/recordings/{recording}/files/{name}",
                token=user,
                data=b"x",
                headers={"Upload-Offset": "0"},
            ),
            404,
            "not_found",
        )
        assert api.get(f"/v1/recordings/{recording}/files/{name}", token=user).status == 404


def test_an_offset_mismatch_names_the_offset_to_resume_from(api: Backend) -> None:
    user = api.user_a
    recording = unique("offset")
    data = fx.recording(recording, hr_frames=20)["hr.log"]
    api.put_recording(user, recording, state="recording")
    path = f"/v1/recordings/{recording}/files/hr.log"

    # A new file starts at 0.
    late = api.patch(path, token=user, data=data[:10], headers={"Upload-Offset": "5"})
    assert_error(late, 409, "offset_mismatch")
    assert late.header("Upload-Offset") == "0"

    api.patch(path, token=user, data=data[:50], headers={"Upload-Offset": "0"}, expect=204)
    # The response to the next chunk is lost, so the sender sends it again from the same offset.
    api.patch(path, token=user, data=data[50:80], headers={"Upload-Offset": "50"}, expect=204)
    repeated = api.patch(path, token=user, data=data[50:80], headers={"Upload-Offset": "50"})
    assert_error(repeated, 409, "offset_mismatch")
    assert repeated.header("Upload-Offset") == "80"
    ahead = api.patch(path, token=user, data=data[80:], headers={"Upload-Offset": "999"})
    assert_error(ahead, 409, "offset_mismatch")
    assert ahead.header("Upload-Offset") == "80"

    # HEAD tells the same, and the upload continues from there.
    offset = int(api.head(path, token=user, expect=200).header("Upload-Offset"))
    assert offset == 80
    api.patch(path, token=user, data=data[offset:], headers={"Upload-Offset": str(offset)}, expect=204)
    assert api.get(path, token=user, expect=200).body == data

    assert_error(api.patch(path, token=user, data=b"x"), 400, "invalid_offset")
    assert_error(
        api.patch(path, token=user, data=b"x", headers={"Upload-Offset": "-1"}),
        400,
        "invalid_offset",
    )
    assert_error(
        api.patch(path, token=user, data=b"x", headers={"Upload-Offset": "ten"}),
        400,
        "invalid_offset",
    )
    assert_error(
        api.patch(path, token=user, data=b"", headers={"Upload-Offset": str(len(data))}),
        400,
        "empty_chunk",
    )


def test_compressed_files_are_gzip_members_that_can_be_resumed(api: Backend) -> None:
    user = api.user_a
    recording = unique("gzip")
    data = fx.recording(recording, hr_frames=60)["hr.log"]
    chunks = fx.chunks(data, 500)
    members = [gzip.compress(chunk, mtime=0) for chunk in chunks]
    api.put_recording(user, recording, state="recording")
    path = f"/v1/recordings/{recording}/files/hr.log"

    api.patch(
        path,
        token=user,
        data=members[0],
        headers={"Upload-Offset": "0", "Content-Encoding": "gzip"},
        expect=204,
    )
    # The offset of a compressed file counts stored (compressed) bytes, which is what HEAD reports.
    head = api.head(path, token=user, expect=200)
    assert head.header("Upload-Offset") == str(len(members[0]))
    assert head.header("Content-Encoding") == "gzip"
    offset = int(head.header("Upload-Offset"))
    for member in members[1:]:
        response = api.patch(
            path,
            token=user,
            data=member,
            headers={"Upload-Offset": str(offset), "Content-Encoding": "gzip"},
            expect=204,
        )
        offset += len(member)
        assert response.header("Upload-Offset") == str(offset)

    listing = api.get(f"/v1/recordings/{recording}/files", token=user).json()["items"]
    assert listing == [{"name": "hr.log", "size": offset, "encoding": "gzip", "uncompressed_size": len(data)}]
    assert api.get(f"/v1/recordings/{recording}", token=user).json()["size_bytes"] == offset

    # Without Accept-Encoding the original bytes come back, whoever asks.
    plain = api.get(path, token=user, expect=200)
    assert plain.body == data
    assert plain.header("Content-Encoding") is None
    # A client that accepts gzip gets the file as stored.
    stored = api.get(path, token=user, headers={"Accept-Encoding": "gzip"}, expect=200)
    assert stored.header("Content-Encoding") == "gzip"
    assert stored.body == b"".join(members)
    assert gzip.decompress(stored.body) == data
    assert api.get(path, token=user, headers={"Accept-Encoding": "br, gzip;q=0.5"}).header("Content-Encoding") == "gzip"
    assert api.get(path, token=user, headers={"Accept-Encoding": "identity"}).body == data


def test_the_encoding_of_a_file_is_fixed_by_its_first_chunk(api: Backend) -> None:
    user = api.user_a
    recording = unique("encoding")
    api.put_recording(user, recording, state="recording")
    gz_path = f"/v1/recordings/{recording}/files/hr.log"
    plain_path = f"/v1/recordings/{recording}/files/acc.log"
    member = gzip.compress(b"abc" * 100, mtime=0)

    api.patch(
        gz_path,
        token=user,
        data=member,
        headers={"Upload-Offset": "0", "Content-Encoding": "gzip"},
        expect=204,
    )
    api.patch(plain_path, token=user, data=b"plain", headers={"Upload-Offset": "0"}, expect=204)

    assert_error(
        api.patch(gz_path, token=user, data=b"plain", headers={"Upload-Offset": str(len(member))}),
        409,
        "encoding_mismatch",
    )
    assert_error(
        api.patch(
            plain_path,
            token=user,
            data=member,
            headers={"Upload-Offset": "5", "Content-Encoding": "gzip"},
        ),
        409,
        "encoding_mismatch",
    )
    # A chunk that says it is gzip has to be one complete gzip member.
    assert_error(
        api.patch(
            gz_path,
            token=user,
            data=member[:-3],
            headers={"Upload-Offset": str(len(member)), "Content-Encoding": "gzip"},
        ),
        400,
        "invalid_gzip",
    )
    assert_error(
        api.patch(
            gz_path,
            token=user,
            data=b"not gzip at all",
            headers={"Upload-Offset": str(len(member)), "Content-Encoding": "gzip"},
        ),
        400,
        "invalid_gzip",
    )
    assert_error(
        api.patch(
            gz_path,
            token=user,
            data=b"x",
            headers={"Upload-Offset": str(len(member)), "Content-Encoding": "br"},
        ),
        400,
        "unsupported_encoding",
    )
    # Nothing of the refused chunks was stored.
    assert api.head(gz_path, token=user).header("Upload-Offset") == str(len(member))
    assert api.get(gz_path, token=user).body == b"abc" * 100
    assert api.get(plain_path, token=user).body == b"plain"


def test_chunks_and_expansion_are_limited(api: Backend) -> None:
    user = api.user_a
    recording = unique("limits")
    api.put_recording(user, recording, state="recording")
    path = f"/v1/recordings/{recording}/files/hr.log"

    too_large = api.patch(path, token=user, data=b"x" * (1024 * 1024 + 1), headers={"Upload-Offset": "0"})
    assert_error(too_large, 413, "chunk_too_large")
    assert api.head(path, token=user).status == 404

    bomb = gzip.compress(b"\x00" * (4 * 1024 * 1024), mtime=0)
    assert len(bomb) < 100 * 1024
    refused = api.patch(path, token=user, data=bomb, headers={"Upload-Offset": "0", "Content-Encoding": "gzip"})
    assert refused.status in (400, 413)
    assert set(refused.json()) == {"code", "message"}
    assert api.head(path, token=user).status == 404


def test_deleting_a_file_lets_it_start_over(api: Backend) -> None:
    user = api.user_a
    recording = unique("restart")
    data = fx.recording(recording, hr_frames=10)["hr.log"]
    api.put_recording(user, recording, state="recording")
    path = f"/v1/recordings/{recording}/files/hr.log"
    api.patch(
        path,
        token=user,
        data=gzip.compress(b"first try", mtime=0),
        headers={"Upload-Offset": "0", "Content-Encoding": "gzip"},
        expect=204,
    )

    api.delete(path, token=user, expect=204)

    assert api.head(path, token=user).status == 404
    assert api.get(f"/v1/recordings/{recording}/files", token=user).json() == {"items": []}
    assert api.get(f"/v1/recordings/{recording}", token=user).json()["size_bytes"] == 0
    assert_error(api.delete(path, token=user), 404, "not_found")
    # The encoding is chosen again with the new first chunk.
    api.upload(user, recording, "hr.log", data)
    assert api.get(path, token=user).body == data
    assert api.get(f"/v1/recordings/{recording}/files", token=user).json()["items"][0]["encoding"] == "identity"


def test_a_complete_recording_cannot_change(api: Backend) -> None:
    user = api.user_a
    recording = api.make_recording(user)
    path = f"/v1/recordings/{recording}/files/hr.log"
    stored = api.get(path, token=user).body
    offset = len(stored)

    assert_error(
        api.patch(path, token=user, data=b"more", headers={"Upload-Offset": str(offset)}),
        409,
        "recording_complete",
    )
    assert_error(
        api.patch(
            f"/v1/recordings/{recording}/files/ecg.log",
            token=user,
            data=b"x",
            headers={"Upload-Offset": "999"},
        ),
        409,
        "recording_complete",
    )
    assert_error(api.delete(path, token=user), 409, "recording_complete")
    assert_error(api.put_recording(user, recording, expect=409), 409, "recording_complete")
    # It can still be read, and deleted as a whole.
    assert api.get(path, token=user).body == stored
    api.delete(f"/v1/recordings/{recording}", token=user, expect=204)


def test_the_backend_stores_bytes_verbatim_and_does_not_read_frames(api: Backend) -> None:
    """Checking frames and CRCs is the worker's job (docs/api.md), not the API's."""

    user = api.user_a
    recording = unique("verbatim")
    api.put_recording(user, recording)
    junk = b"these bytes are no frame log, and the API does not care"

    api.upload(user, recording, "hr.log", junk)
    api.upload(user, recording, "ecg.log", fx.frame_header(2) + b"\x10\x00\x00\x00broken")
    api.upload(user, recording, "events.jsonl", b"not json\n{ half a line")
    api.complete_recording(user, recording)

    assert api.get(f"/v1/recordings/{recording}/files/hr.log", token=user).body == junk
    assert api.get(f"/v1/recordings/{recording}/files/events.jsonl", token=user).body == b"not json\n{ half a line"


# ---------------------------------------------------------------------------
# Algorithms and workers
# ---------------------------------------------------------------------------


def test_algorithms_are_what_workers_announce(api: Backend) -> None:
    both = unique("both")
    only_fake = unique("only")
    first = manifest(both, "1.0.0")
    api.offer(api.worker, first, manifest(both, "1.2.0"), manifest(only_fake, "2.0.0", name="Only here"))
    api.offer(api.other_worker, first)

    listed = api.get("/v1/algorithms", token=api.user_a, expect=200).json()["items"]
    entries = {(item["id"], item["version"]): item for item in listed if item["id"] in (both, only_fake)}

    assert set(entries) == {(both, "1.0.0"), (both, "1.2.0"), (only_fake, "2.0.0")}
    # The manifest is passed on as the worker announced it.
    shared = entries[(both, "1.0.0")]
    for key in (
        "name",
        "description",
        "license",
        "needs",
        "epoch_seconds",
        "stage_scheme",
        "supported_stages",
        "capabilities",
        "default_params",
        "params_schema",
    ):
        assert shared[key] == first[key]
    assert entries[(only_fake, "2.0.0")]["name"] == "Only here"
    # One entry per id and version, naming the workers that offer it.
    assert len(shared["workers"]) == 2
    assert len(entries[(both, "1.2.0")]["workers"]) == 1

    # A worker announces again to change what it offers: what is left out is no longer offered.
    api.offer(api.worker, manifest(both, "1.2.0"))
    after = {
        (item["id"], item["version"]): item
        for item in api.get("/v1/algorithms", token=api.user_a).json()["items"]
        if item["id"] in (both, only_fake)
    }
    assert set(after) == {(both, "1.0.0"), (both, "1.2.0")}
    assert len(after[(both, "1.0.0")]["workers"]) == 1
    recording = api.make_recording(api.user_a)
    assert_error(api.request_analysis(api.user_a, recording, only_fake, expect=422), 422, "unknown_algorithm")


def test_a_worker_announcement_is_validated(api: Backend) -> None:
    def announce(*algorithms: Any, **extra: Any) -> Response:
        return api.put(
            "/v1/workers/me",
            token=api.worker,
            json={"name": "x", "version": "0.0.1", "algorithms": list(algorithms), **extra},
        )

    good = manifest(unique("ok"))
    assert announce(good).status == 204
    for broken in (
        manifest("Not A Valid Id"),
        manifest("-starts-with-dash"),
        manifest(unique("nov"), version=""),
        manifest(unique("noname"), name=""),
        manifest(unique("nolicense"), license=""),
    ):
        assert_error(announce(broken), 400, "invalid_algorithm")
    assert_error(announce(*[manifest(unique("many")) for _ in range(101)]), 400, "too_many_algorithms")
    assert_error(api.put("/v1/workers/me", token=api.worker, data=b"nope"), 400, "invalid_body")
    # A refused announcement leaves the previous one in place.
    assert good["id"] in [item["id"] for item in api.get("/v1/algorithms", token=api.user_a).json()["items"]]


# ---------------------------------------------------------------------------
# Analyses
# ---------------------------------------------------------------------------


def test_requesting_an_analysis_queues_it_and_asking_again_returns_the_same(api: Backend) -> None:
    user = api.user_a
    algorithm = unique("alg")
    api.offer(api.worker, manifest(algorithm, "1.0.0"), manifest(algorithm, "1.2.0"))
    recording = api.make_recording(user)

    created = api.request_analysis(user, recording, algorithm, expect=201)

    analysis = created.json()
    assert created.header("Location") == f"/v1/analyses/{analysis['id']}"
    assert analysis["recording"] == recording
    assert analysis["algorithm"] == algorithm
    assert analysis["version"] == "1.2.0"  # the newest on offer when none is named
    assert analysis["params"] == {}
    assert analysis["status"] == "queued"
    assert analysis["error"] == ""
    assert analysis["attempts"] == 0
    assert analysis["finished_at"] is None
    assert analysis["summary"] is None
    assert analysis.get("result") is None
    for field in ("created", "updated"):
        datetime.strptime(analysis[field], "%Y-%m-%dT%H:%M:%SZ")

    # The same recording, algorithm, version and params is the same analysis.
    assert api.request_analysis(user, recording, algorithm, expect=200).json()["id"] == analysis["id"]
    assert api.request_analysis(user, recording, algorithm, version="1.2.0", expect=200).json()["id"] == analysis["id"]
    assert api.request_analysis(user, recording, algorithm, params={}, expect=200).json()["id"] == analysis["id"]
    # Anything else is another analysis.
    older = api.request_analysis(user, recording, algorithm, version="1.0.0", expect=201).json()
    assert older["version"] == "1.0.0" and older["id"] != analysis["id"]
    tuned = api.request_analysis(user, recording, algorithm, params={"b": 2, "a": 1}, expect=201).json()
    assert tuned["params"] == {"a": 1, "b": 2} and tuned["id"] not in (analysis["id"], older["id"])
    # The order of the params does not make a difference.
    assert (
        api.request_analysis(user, recording, algorithm, params={"a": 1, "b": 2}, expect=200).json()["id"]
        == tuned["id"]
    )
    assert (
        api.request_analysis(user, recording, algorithm, params={"a": 1.0, "b": 2}, expect=200).json()["id"]
        == tuned["id"]
    )

    again = api.get(f"/v1/analyses/{analysis['id']}", token=user, expect=200).json()
    assert again["status"] == "queued"


def test_an_analysis_can_only_be_requested_when_it_makes_sense(api: Backend) -> None:
    user = api.user_a
    algorithm = unique("alg")
    api.offer(api.worker, manifest(algorithm))
    open_recording = api.make_recording(user, complete=False)
    sealed = api.make_recording(user)

    assert_error(
        api.request_analysis(user, open_recording, algorithm, expect=409),
        409,
        "recording_incomplete",
    )
    assert_error(
        api.request_analysis(user, sealed, "nobody-offers-this", expect=422),
        422,
        "unknown_algorithm",
    )
    assert_error(
        api.request_analysis(user, sealed, algorithm, version="9.9.9", expect=422),
        422,
        "unknown_algorithm",
    )
    assert_error(api.request_analysis(user, "no-such-recording", algorithm, expect=404), 404, "not_found")
    assert_error(api.request_analysis(user, sealed, "Not An Id", expect=400), 400, "invalid_algorithm")
    path = f"/v1/recordings/{sealed}/analyses"
    assert_error(api.post(path, token=user, json={}), 400, "invalid_algorithm")
    assert_error(
        api.post(path, token=user, json={"algorithm": algorithm, "params": [1]}),
        400,
        "invalid_params",
    )
    assert_error(
        api.post(path, token=user, json={"algorithm": algorithm, "params": "x"}),
        400,
        "invalid_params",
    )
    assert_error(api.post(path, token=user, data=b"nope"), 400, "invalid_body")
    assert_error(
        api.post(path, token=user, json={"algorithm": algorithm, "version": "v" * 101}),
        400,
        "invalid_version",
    )
    assert api.get(path, token=user, expect=200).json()["total"] == 0

    # Sealing the recording makes it possible.
    api.complete_recording(user, open_recording)
    api.request_analysis(user, open_recording, algorithm, expect=201)


def test_asking_again_for_a_failed_analysis_replaces_it(api: Backend) -> None:
    algorithm, recording, first = queued_analysis(api)
    claimed = api.claim(api.worker, algorithm, expect=200).json()
    api.post(
        f"/v1/analyses/{claimed['id']}/fail",
        token=api.worker,
        json={"error": "it broke"},
        expect=200,
    )
    assert api.analysis(api.user_a, first["id"])["status"] == "failed"

    retry = api.request_analysis(api.user_a, recording, algorithm, expect=201)

    assert retry.json()["id"] != first["id"]
    assert retry.json()["status"] == "queued"
    assert retry.json()["attempts"] == 0
    assert_error(api.get(f"/v1/analyses/{first['id']}", token=api.user_a), 404, "not_found")
    listed = api.get(f"/v1/recordings/{recording}/analyses", token=api.user_a).json()
    assert [item["id"] for item in listed["items"]] == [retry.json()["id"]]
    # A succeeded analysis is never replaced.
    again = api.claim(api.worker, algorithm, expect=200).json()
    api.post(
        f"/v1/analyses/{again['id']}/complete",
        token=api.worker,
        json={"result": result(algorithm)},
        expect=200,
    )
    assert api.request_analysis(api.user_a, recording, algorithm, expect=200).json()["id"] == again["id"]


def test_analyses_are_listed_without_their_result_unless_asked(api: Backend) -> None:
    user = api.user_a
    algorithm = unique("alg")
    api.offer(api.worker, manifest(algorithm))
    recording = api.make_recording(user)
    done = api.request_analysis(user, recording, algorithm, params={"n": 1}, expect=201).json()
    time.sleep(0.02)
    waiting = api.request_analysis(user, recording, algorithm, params={"n": 2}, expect=201).json()
    claimed = api.claim(api.worker, algorithm, expect=200).json()
    assert claimed["id"] == done["id"]
    sent = result(algorithm)
    api.post(f"/v1/analyses/{done['id']}/complete", token=api.worker, json={"result": sent}, expect=200)

    path = f"/v1/recordings/{recording}/analyses"
    plain = api.get(path, token=user, expect=200).json()
    assert (plain["total"], plain["limit"], plain["offset"]) == (2, 50, 0)
    assert [item["id"] for item in plain["items"]] == [waiting["id"], done["id"]]  # newest first
    assert all("result" not in item for item in plain["items"])
    assert plain["items"][1]["summary"]["metrics"] == sent["metrics"]
    assert plain["items"][0]["summary"] is None

    expanded = api.get(f"{path}?expand=result", token=user, expect=200).json()["items"]
    assert expanded[1]["result"] == sent
    assert expanded[0].get("result") is None

    assert [i["id"] for i in api.get(f"{path}?sort=created", token=user).json()["items"]] == [
        done["id"],
        waiting["id"],
    ]
    assert [i["id"] for i in api.get(f"{path}?status=queued", token=user).json()["items"]] == [waiting["id"]]
    assert [i["id"] for i in api.get(f"{path}?status=succeeded&limit=1", token=user).json()["items"]] == [done["id"]]
    page = api.get(f"{path}?limit=1&offset=1", token=user).json()
    assert [i["id"] for i in page["items"]] == [done["id"]] and page["total"] == 2

    everywhere = api.get(f"/v1/analyses?recording={recording}", token=user, expect=200).json()
    assert everywhere["total"] == 2
    assert api.get(f"/v1/analyses?recording={recording}&status=failed", token=user).json()["items"] == []
    full = api.get(f"/v1/analyses/{done['id']}", token=user, expect=200).json()
    assert full["result"] == sent
    assert full["summary"]["stages"]["segments"] == sent["stages"]["segments"]

    for query in ("?status=done", "?sort=name", "?expand=everything", "?limit=0", "?offset=-3"):
        assert_error(api.get(f"/v1/analyses{query}", token=user), 400, "invalid_query")
        assert_error(api.get(f"{path}{query}", token=user), 400, "invalid_query")
    assert_error(api.get("/v1/recordings/nope/analyses", token=user), 404, "not_found")


def test_a_worker_lists_the_analyses_of_everyone_and_a_user_only_their_own(api: Backend) -> None:
    algorithm = unique("alg")
    api.offer(api.worker, manifest(algorithm))
    mine = api.request_analysis(api.user_a, api.make_recording(api.user_a), algorithm, expect=201).json()
    theirs = api.request_analysis(api.user_b, api.make_recording(api.user_b), algorithm, expect=201).json()

    seen_by_worker = api.get("/v1/analyses?status=queued&limit=200", token=api.worker, expect=200).json()["items"]
    assert {mine["id"], theirs["id"]} <= {item["id"] for item in seen_by_worker}
    assert (
        api.get(f"/v1/analyses/{theirs['id']}", token=api.worker, expect=200).json()["recording"] == theirs["recording"]
    )

    for account, own, other in ((api.user_a, mine, theirs), (api.user_b, theirs, mine)):
        ids = {item["id"] for item in api.get("/v1/analyses?limit=200", token=account).json()["items"]}
        assert own["id"] in ids and other["id"] not in ids


def test_deleting_an_analysis_cancels_it(api: Backend) -> None:
    algorithm, recording, analysis = queued_analysis(api)
    claimed = api.claim(api.worker, algorithm, expect=200).json()
    api.get(f"/v1/recordings/{recording}", token=api.worker, expect=200)

    api.delete(f"/v1/analyses/{analysis['id']}", token=api.user_a, expect=204)

    # The worker's next call about it gets 404, and it can no longer read the recording.
    assert_error(
        api.post(
            f"/v1/analyses/{claimed['id']}/complete",
            token=api.worker,
            json={"result": result(algorithm)},
        ),
        404,
        "not_found",
    )
    assert_error(
        api.post(f"/v1/analyses/{claimed['id']}/fail", token=api.worker, json={"error": "x"}),
        404,
        "not_found",
    )
    assert_error(api.get(f"/v1/recordings/{recording}", token=api.worker), 404, "not_found")
    assert_error(api.get(f"/v1/analyses/{analysis['id']}", token=api.user_a), 404, "not_found")
    assert_error(api.delete(f"/v1/analyses/{analysis['id']}", token=api.user_a), 404, "not_found")
    # The recording is untouched, and the analysis can be requested again.
    assert api.get(f"/v1/recordings/{recording}", token=api.user_a).json()["complete"] is True
    assert api.request_analysis(api.user_a, recording, algorithm, expect=201).json()["id"] != analysis["id"]


# ---------------------------------------------------------------------------
# The queue
# ---------------------------------------------------------------------------


def test_claiming_takes_the_oldest_queued_analysis_once(api: Backend) -> None:
    algorithm = unique("alg")
    api.offer(api.worker, manifest(algorithm))
    recording = api.make_recording(api.user_a)
    first = api.request_analysis(api.user_a, recording, algorithm, params={"n": 1}, expect=201).json()
    time.sleep(0.02)
    second = api.request_analysis(api.user_a, recording, algorithm, params={"n": 2}, expect=201).json()

    claimed = api.claim(api.worker, algorithm, lease_seconds=30, expect=200).json()

    assert claimed["id"] == first["id"]
    assert claimed["recording"] == recording
    assert claimed["algorithm"] == algorithm
    assert claimed["version"] == "1.0.0"
    assert claimed["params"] == {"n": 1}
    assert claimed["status"] == "running"
    assert claimed["attempts"] == 1
    assert api.analysis(api.user_a, first["id"])["status"] == "running"

    # Another worker gets the next one, and then there is nothing left.
    assert api.claim(api.other_worker, algorithm, expect=200).json()["id"] == second["id"]
    nothing = api.claim(api.worker, algorithm, expect=204)
    assert nothing.body == b""


def test_claiming_only_takes_what_the_worker_asks_for(api: Backend) -> None:
    wanted = unique("wanted")
    other = unique("other")
    api.offer(api.worker, manifest(wanted, "1.0.0"), manifest(wanted, "2.0.0"), manifest(other))
    recording = api.make_recording(api.user_a)
    of_other = api.request_analysis(api.user_a, recording, other, expect=201).json()
    time.sleep(0.02)
    old = api.request_analysis(api.user_a, recording, wanted, version="1.0.0", expect=201).json()
    time.sleep(0.02)
    new = api.request_analysis(api.user_a, recording, wanted, version="2.0.0", expect=201).json()

    api.claim(api.worker, "nothing-of-this", expect=204)
    api.claim(api.worker, wanted, version="3.0.0", expect=204)
    assert api.claim(api.worker, wanted, version="2.0.0", expect=200).json()["id"] == new["id"]
    # Several algorithms can be asked for at once; the oldest of them is taken.
    body = {"algorithms": [{"id": other}, {"id": wanted}], "lease_seconds": 30}
    taken = api.post("/v1/analyses/claim", token=api.worker, json=body, expect=200).json()
    assert taken["id"] == of_other["id"]
    assert api.post("/v1/analyses/claim", token=api.worker, json=body, expect=200).json()["id"] == old["id"]
    api.post("/v1/analyses/claim", token=api.worker, json=body, expect=204)

    assert_error(
        api.post("/v1/analyses/claim", token=api.worker, json={"algorithms": [{"id": "Not Valid"}]}),
        400,
        "invalid_algorithm",
    )
    assert_error(api.post("/v1/analyses/claim", token=api.worker, data=b"nope"), 400, "invalid_body")


def test_claiming_without_a_filter_takes_anything(api: Backend) -> None:
    api.drain_queue()
    algorithm, _, analysis = queued_analysis(api)

    empty_body = api.post("/v1/analyses/claim", token=api.worker, expect=200)

    assert empty_body.json()["id"] == analysis["id"]
    api.post("/v1/analyses/claim", token=api.worker, expect=204)
    api.post("/v1/analyses/claim", token=api.worker, json={}, expect=204)


def test_a_worker_completes_what_it_claimed(api: Backend) -> None:
    algorithm, recording, analysis = queued_analysis(api)
    claimed = api.claim(api.worker, algorithm, expect=200).json()
    sent = result(algorithm)

    done = api.post(
        f"/v1/analyses/{claimed['id']}/complete",
        token=api.worker,
        json={"result": sent},
        expect=200,
    ).json()

    assert done["id"] == analysis["id"]
    assert done["status"] == "succeeded"
    assert done["error"] == ""
    assert done["attempts"] == 1
    assert done["finished_at"] is not None
    # The result is stored whole, whatever the algorithm put in it.
    stored = api.analysis(api.user_a, analysis["id"])
    assert stored["result"] == sent
    assert stored["result"]["extras"] == {"anything": [1, {"else": True}]}
    assert stored["summary"] == {
        "metrics": sent["metrics"],
        "quality": sent["quality"],
        "sleep_quality": sent["sleep_quality"],
        "stages": {
            "epoch_seconds": 30,
            "start_unix_ms": sent["stages"]["start_unix_ms"],
            "count": 4,
            "segments": sent["stages"]["segments"],
        },
    }
    # It is terminal: a second report is refused and the analysis does not come back.
    assert_error(
        api.post(f"/v1/analyses/{claimed['id']}/complete", token=api.worker, json={"result": sent}),
        409,
        "not_leased",
    )
    assert_error(
        api.post(f"/v1/analyses/{claimed['id']}/fail", token=api.worker, json={"error": "x"}),
        409,
        "not_leased",
    )
    api.claim(api.worker, algorithm, expect=204)
    assert api.request_analysis(api.user_a, recording, algorithm, expect=200).json()["status"] == "succeeded"


def test_only_the_worker_that_holds_the_lease_can_report(api: Backend) -> None:
    algorithm, _, analysis = queued_analysis(api)
    sent = {"result": result(algorithm)}

    # Not claimed yet: nobody holds a lease.
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/complete", token=api.worker, json=sent),
        409,
        "not_leased",
    )
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/fail", token=api.worker, json={"error": "x"}),
        409,
        "not_leased",
    )
    api.claim(api.worker, algorithm, expect=200)

    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/complete", token=api.other_worker, json=sent),
        409,
        "not_leased",
    )
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/fail", token=api.other_worker, json={"error": "x"}),
        409,
        "not_leased",
    )
    assert_error(
        api.post("/v1/analyses/doesnotexist/complete", token=api.worker, json=sent),
        404,
        "not_found",
    )
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/complete", token=api.worker, json={}),
        400,
        "invalid_body",
    )
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/complete", token=api.worker, data=b"nope"),
        400,
        "invalid_body",
    )
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/fail", token=api.worker, data=b"nope"),
        400,
        "invalid_body",
    )
    # None of that disturbed the lease.
    assert api.analysis(api.user_a, analysis["id"])["status"] == "running"
    api.post(f"/v1/analyses/{analysis['id']}/complete", token=api.worker, json=sent, expect=200)


def test_a_worker_reads_a_recording_only_while_it_holds_the_lease(api: Backend) -> None:
    user = api.user_a
    recording = unique("lease")
    files = fx.recording(recording, hr_frames=30)
    api.make_recording(user, recording, files=files)
    algorithm, _, analysis = queued_analysis(api, recording=recording)
    base = f"/v1/recordings/{recording}"

    def denied(worker: Account) -> None:
        for path in (base, f"{base}/files", f"{base}/files/hr.log"):
            assert_error(api.get(path, token=worker), 404, "not_found")
        assert api.head(f"{base}/files/hr.log", token=worker).status == 404

    denied(api.worker)  # queued: no lease yet
    api.claim(api.worker, algorithm, lease_seconds=60, expect=200)
    denied(api.other_worker)  # somebody else holds it

    read = api.get(base, token=api.worker, expect=200).json()
    assert read["id"] == recording and read["complete"] is True
    # The meta is part of the recording; it is no file.
    assert read["meta"]["session_id"] == recording
    assert read["meta"]["config"]["ecg_rate_hz"] == 130
    listing = api.get(f"{base}/files", token=api.worker, expect=200).json()["items"]
    assert [item["name"] for item in listing] == ["hr.log", "ecg.log", "acc.log", "events.jsonl"]
    for name in ("hr.log", "ecg.log", "acc.log", "events.jsonl"):
        assert api.get(f"{base}/files/{name}", token=api.worker, expect=200).body == files[name]
    assert api.head(f"{base}/files/hr.log", token=api.worker).status == 200
    assert_error(api.get(f"{base}/files/meta.json", token=api.worker), 404, "not_found")
    # A worker never writes a recording.
    assert_error(
        api.patch(f"{base}/files/hr.log", token=api.worker, data=b"x", headers={"Upload-Offset": "0"}),
        403,
        "forbidden",
    )

    api.post(
        f"/v1/analyses/{analysis['id']}/complete",
        token=api.worker,
        json={"result": result(algorithm)},
        expect=200,
    )
    denied(api.worker)  # finished: the lease is gone


def test_a_worker_downloads_compressed_files_inflated(api: Backend) -> None:
    recording = unique("inflate")
    files = fx.recording(recording, hr_frames=200)
    api.make_recording(api.user_a, recording, files=files, compress=True, chunk_size=2000)
    algorithm, _, _ = queued_analysis(api, recording=recording)
    api.claim(api.worker, algorithm, expect=200)
    base = f"/v1/recordings/{recording}"

    listing = {item["name"]: item for item in api.get(f"{base}/files", token=api.worker).json()["items"]}
    assert listing["hr.log"]["encoding"] == "gzip"
    assert listing["hr.log"]["size"] < listing["hr.log"]["uncompressed_size"] == len(files["hr.log"])

    # A client that does not ask for gzip, like the worker, gets the original bytes.
    assert api.get(f"{base}/files/hr.log", token=api.worker).body == files["hr.log"]
    stored = api.get(f"{base}/files/hr.log", token=api.worker, headers={"Accept-Encoding": "gzip"})
    assert stored.header("Content-Encoding") == "gzip"
    assert gzip.decompress(stored.body) == files["hr.log"]


def test_a_failure_is_final_unless_the_worker_says_it_may_be_retried(api: Backend) -> None:
    algorithm, _, analysis = queued_analysis(api)
    api.claim(api.worker, algorithm, expect=200)

    failed = api.post(
        f"/v1/analyses/{analysis['id']}/fail",
        token=api.worker,
        json={"error": "bad recording", "retryable": False},
        expect=200,
    ).json()

    assert failed["status"] == "failed"
    assert failed["error"] == "bad recording"
    assert failed["finished_at"] is not None
    assert failed["attempts"] == 1
    api.claim(api.worker, algorithm, expect=204)

    # A retryable failure goes back to the queue, but not at once.
    algorithm, _, analysis = queued_analysis(api)
    api.claim(api.worker, algorithm, expect=200)
    requeued = api.post(
        f"/v1/analyses/{analysis['id']}/fail",
        token=api.worker,
        json={"error": "out of memory", "retryable": True},
        expect=200,
    ).json()
    assert requeued["status"] == "queued"
    assert requeued["error"] == "out of memory"
    assert requeued["attempts"] == 1
    assert requeued["finished_at"] is None
    api.claim(api.worker, algorithm, expect=204)  # it waits before it is offered again
    # Nobody holds a lease on it any more.
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/fail", token=api.worker, json={"error": "x"}),
        409,
        "not_leased",
    )


def test_a_retryable_failure_is_final_when_the_attempts_are_used_up(api: Backend) -> None:
    algorithm, _, analysis = queued_analysis(api)
    for attempt in (1, 2):
        claimed = api.claim(api.worker, algorithm, lease_seconds=1, expect=200).json()
        assert claimed["attempts"] == attempt
        time.sleep(1.3)  # the lease runs out and the analysis goes back to the queue
    last = api.claim(api.worker, algorithm, lease_seconds=60, expect=200).json()
    assert last["attempts"] == 3

    failed = api.post(
        f"/v1/analyses/{analysis['id']}/fail",
        token=api.worker,
        json={"error": "still broken", "retryable": True},
        expect=200,
    ).json()

    assert failed["status"] == "failed"
    assert failed["error"] == "still broken"
    api.claim(api.worker, algorithm, expect=204)


def test_an_expired_lease_puts_the_analysis_back_in_the_queue(api: Backend) -> None:
    algorithm, recording, analysis = queued_analysis(api)
    first = api.claim(api.worker, algorithm, lease_seconds=1, expect=200).json()
    assert first["attempts"] == 1
    time.sleep(1.3)

    # The worker lost the lease: it can neither report nor read the recording.
    assert_error(
        api.post(
            f"/v1/analyses/{analysis['id']}/complete",
            token=api.worker,
            json={"result": result(algorithm)},
        ),
        409,
        "not_leased",
    )
    assert_error(api.get(f"/v1/recordings/{recording}", token=api.worker), 404, "not_found")

    # The backend requeues it itself when anybody claims next.
    second = api.claim(api.other_worker, algorithm, lease_seconds=60, expect=200).json()
    assert second["id"] == analysis["id"]
    assert second["attempts"] == 2
    assert api.get(f"/v1/recordings/{recording}", token=api.other_worker, expect=200).status == 200
    assert_error(
        api.post(f"/v1/analyses/{analysis['id']}/fail", token=api.worker, json={"error": "x"}),
        409,
        "not_leased",
    )


def test_the_backend_sweeps_expired_leases_by_itself(api: Backend) -> None:
    algorithm, _, analysis = queued_analysis(api)
    api.claim(api.worker, algorithm, lease_seconds=1, expect=200)
    time.sleep(1.3)

    crons = {cron["id"] for cron in api.admin("GET", "/api/crons", expect=200).json()}
    assert "taiga_requeue_expired_analyses" in crons
    api.admin("POST", "/api/crons/taiga_requeue_expired_analyses", expect=204)  # runs in the background

    swept = api.wait_for_status(api.user_a, analysis["id"], ("queued",), timeout_s=10)
    assert swept["status"] == "queued"
    assert swept["error"] == "lease expired"


def test_an_analysis_that_keeps_losing_its_lease_fails(api: Backend) -> None:
    algorithm, recording, analysis = queued_analysis(api)
    for attempt in (1, 2, 3):
        claimed = api.claim(api.worker, algorithm, lease_seconds=1, expect=200).json()
        assert claimed["attempts"] == attempt
        time.sleep(1.3)

    api.claim(api.worker, algorithm, expect=204)  # the sweep before this claim ends it

    final = api.analysis(api.user_a, analysis["id"])
    assert final["status"] == "failed"
    assert final["attempts"] == 3
    assert "lease expired" in final["error"]
    assert final["finished_at"] is not None
    # Asking again is how it is retried.
    assert api.request_analysis(api.user_a, recording, algorithm, expect=201).json()["id"] != analysis["id"]


INVALID_RESULTS = {
    "no contract_version": lambda a: {k: v for k, v in result(a).items() if k != "contract_version"},
    "another algorithm": lambda a: result("somebody-else"),
    "another version": lambda a: result(a, "9.9.9"),
    "no model identity": lambda a: result(a, model=""),
    "no stages": lambda a: result(a, stages={"epoch_seconds": 30, "start_unix_ms": 1, "count": 0, "segments": []}),
    "segments that do not cover the epochs": lambda a: result(
        a,
        stages={
            "epoch_seconds": 30,
            "start_unix_ms": 1,
            "count": 9,
            "segments": [{"stage": "wake", "count": 2}],
        },
    ),
    "an hr series of the wrong length": lambda a: result(a, hr={"epoch_seconds": 30, "count": 4, "mean_bpm": [60.0]}),
    "a score above 100": lambda a: result(a, sleep_quality={"score": 101}),
    "no metrics": lambda a: {k: v for k, v in result(a).items() if k != "metrics"},
    "no provenance": lambda a: {k: v for k, v in result(a).items() if k != "provenance"},
    "a result that is no object": lambda a: "a string",
}


def test_a_result_that_breaks_the_contract_fails_the_analysis_for_good(api: Backend) -> None:
    algorithm = unique("alg")
    api.offer(api.worker, manifest(algorithm))
    recording = api.make_recording(api.user_a)

    for index, (name, build) in enumerate(INVALID_RESULTS.items()):
        analysis = api.request_analysis(api.user_a, recording, algorithm, params={"case": index}, expect=201).json()
        claimed = api.claim(api.worker, algorithm, expect=200).json()
        assert claimed["id"] == analysis["id"], name

        refused = api.post(
            f"/v1/analyses/{analysis['id']}/complete",
            token=api.worker,
            json={"result": build(algorithm)},
        )

        assert_error(refused, 422, "invalid_result")
        stored = api.analysis(api.user_a, analysis["id"])
        assert stored["status"] == "failed", name
        assert stored["error"], name
        assert stored.get("result") is None, name
        api.claim(api.worker, algorithm, expect=204)  # a result that is wrong stays wrong, so it is not retried


def test_a_valid_result_may_leave_out_the_optional_parts(api: Backend) -> None:
    algorithm, _, analysis = queued_analysis(api)
    api.claim(api.worker, algorithm, expect=200)
    minimal = {
        k: v
        for k, v in result(algorithm, hr=None, sleep_quality=None).items()
        if k not in ("extras", "warnings", "metric_support")
    }

    api.post(
        f"/v1/analyses/{analysis['id']}/complete",
        token=api.worker,
        json={"result": minimal},
        expect=200,
    )

    stored = api.analysis(api.user_a, analysis["id"])
    assert stored["status"] == "succeeded"
    assert stored["result"] == minimal


# ---------------------------------------------------------------------------
# The real worker on real recordings
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def baseline(api: Backend) -> str:
    """Start the real worker once so that the backend knows its algorithms; returns the baseline version."""

    api.drain_queue()
    log = api.run_worker_once()
    assert "announced" in log
    entries = [
        item
        for item in api.get("/v1/algorithms", token=api.user_a, expect=200).json()["items"]
        if item["id"] == "baseline" and item["workers"] == ["integration-worker"]
    ]
    assert len(entries) == 1
    return entries[0]["version"]


def test_the_worker_announces_its_algorithms(api: Backend, baseline: str) -> None:
    entry = next(
        item
        for item in api.get("/v1/algorithms", token=api.user_a).json()["items"]
        if item["id"] == "baseline" and item["version"] == baseline
    )

    assert entry["name"] and entry["license"]
    assert entry["epoch_seconds"] == 30
    assert entry["stage_scheme"] == "aasm4"
    assert "wake" in entry["supported_stages"]
    assert [need["name"] for need in entry["needs"]][0] == "hr"
    assert entry["params_schema"]["properties"]["smooth_window_epochs"]["type"] == "integer"
    assert entry["default_params"]["smooth_window_epochs"] == 7


def finished(api: Backend, analysis_id: str) -> dict[str, Any]:
    analysis = api.analysis(api.user_a, analysis_id)
    assert analysis["status"] in ("succeeded", "failed"), analysis
    return analysis


def test_the_worker_analyses_a_recording_end_to_end(api: Backend, baseline: str) -> None:
    api.drain_queue()
    recording = api.make_recording(api.user_a, hr_frames=120)
    created = api.request_analysis(api.user_a, recording, "baseline", params={}, expect=201).json()
    assert created["version"] == baseline

    log = api.run_worker_once()

    assert f"claimed analysis {created['id']}" in log
    analysis = finished(api, created["id"])
    assert analysis["status"] == "succeeded", analysis["error"]
    assert analysis["attempts"] == 1
    assert analysis["finished_at"]
    sent = analysis["result"]
    # The shape of the result is the contract of docs/analysis-contract.md.
    for key in (
        "contract_version",
        "model",
        "model_version",
        "params",
        "params_hash",
        "stages",
        "hr",
        "metrics",
        "metric_support",
        "items",
        "quality",
        "sleep_quality",
        "capabilities",
        "provenance",
        "extras",
        "warnings",
    ):
        assert key in sent, key
    assert sent["model"] == "baseline" and sent["model_version"] == baseline
    assert sent["stages"]["count"] == 4 and sent["stages"]["epoch_seconds"] == 30
    assert sum(segment["count"] for segment in sent["stages"]["segments"]) == 4
    assert sent["hr"]["count"] == 4
    assert sent["quality"]["score"] is not None
    assert sent["provenance"]["worker"] == "integration-worker"
    assert sent["provenance"]["recording"]["session_id"] == recording
    assert analysis["summary"]["stages"]["segments"] == sent["stages"]["segments"]
    assert analysis["summary"]["metrics"] == sent["metrics"]
    # The worker read the recording through its lease, which it no longer holds.
    assert_error(api.get(f"/v1/recordings/{recording}", token=api.workers["real"]), 404, "not_found")
    # And the result is what comes back for the same request.
    assert api.request_analysis(api.user_a, recording, "baseline", params={}, expect=200).json()["id"] == created["id"]


def test_plain_and_compressed_uploads_give_the_same_analysis(api: Backend, baseline: str) -> None:
    api.drain_queue()
    plain = api.make_recording(api.user_a, hr_frames=120, chunk_size=1000)
    compressed = api.make_recording(api.user_a, hr_frames=120, compress=True, chunk_size=1000)
    # The recording ids differ, so the analyses can only differ in what says so.
    ids = [
        api.request_analysis(api.user_a, recording, "baseline", expect=201).json()["id"]
        for recording in (plain, compressed)
    ]

    api.run_worker_once()
    api.run_worker_once()

    first, second = (finished(api, analysis_id) for analysis_id in ids)
    assert first["status"] == second["status"] == "succeeded", (first["error"], second["error"])
    for key in ("stages", "hr", "metrics", "quality", "sleep_quality"):
        assert first["result"][key] == second["result"][key], key
    source = first["result"]["provenance"]["recording"]["source"]
    assert source["encoding"] == "identity"
    assert source["files"]["hr.log"]["frames"] == 120
    assert source["compression_ratio"] == 1
    gz_source = second["result"]["provenance"]["recording"]["source"]
    assert gz_source["encoding"] == "gzip"
    assert gz_source["files"]["hr.log"]["frames"] == 120
    assert gz_source["files"]["hr.log"]["stored_bytes"] < gz_source["files"]["hr.log"]["uncompressed_bytes"]
    assert gz_source["compression_ratio"] > 1


@pytest.mark.parametrize("compress", [False, True], ids=["plain", "gzip"])
def test_the_worker_cuts_a_corrupt_tail_off_whatever_the_encoding(api: Backend, baseline: str, compress: bool) -> None:
    api.drain_queue()
    recording = unique("tail")
    files = fx.recording(recording, hr_frames=120)
    good_hr = files["hr.log"]
    files["hr.log"] = good_hr + b"\x10\x00\x00\x00broken"
    files["events.jsonl"] = files["events.jsonl"] + b'{"phone_ns":1,"type":"st'
    api.make_recording(api.user_a, recording, files=files, compress=compress)
    created = api.request_analysis(api.user_a, recording, "baseline", expect=201).json()

    api.run_worker_once()

    analysis = finished(api, created["id"])
    assert analysis["status"] == "succeeded", analysis["error"]
    source = analysis["result"]["provenance"]["recording"]["source"]
    assert source["files"]["hr.log"]["frames"] == 120
    assert source["files"]["hr.log"]["valid_bytes"] == len(good_hr)
    assert source["files"]["hr.log"]["truncated_tail"] is True
    assert source["files"]["events.jsonl"]["truncated_tail"] is True
    assert source["files"]["ecg.log"]["truncated_tail"] is False
    warnings = analysis["result"]["warnings"]
    assert any(item.startswith("hr.log: truncated frame") for item in warnings)
    assert any(item.startswith("events.jsonl:") for item in warnings)
    assert analysis["result"]["stages"]["count"] == 4


@pytest.mark.parametrize("compress", [False, True], ids=["plain", "gzip"])
def test_a_recording_without_a_valid_frame_fails_the_analysis_for_good(
    api: Backend, baseline: str, compress: bool
) -> None:
    api.drain_queue()
    recording = unique("noframes")
    files = fx.recording(recording, hr_frames=1, include_ecg=False, include_acc=False)
    files["hr.log"] = fx.frame_header(1) + b"\x04\x00\x00\x00oops"  # a header and a frame that is no frame
    api.make_recording(api.user_a, recording, files=files, compress=compress)
    created = api.request_analysis(api.user_a, recording, "baseline", expect=201).json()

    api.run_worker_once()

    analysis = finished(api, created["id"])
    assert analysis["status"] == "failed"
    assert "no valid frames" in analysis["error"]
    assert analysis["attempts"] == 1
    assert analysis.get("result") is None
    api.claim(api.workers["real"], "baseline", expect=204)  # failed for good, not queued again
    # The user can ask again, which is a retry.
    assert api.request_analysis(api.user_a, recording, "baseline", expect=201).json()["id"] != created["id"]
    api.drain_queue()


def test_params_reach_the_algorithm_and_come_back_in_the_result(api: Backend, baseline: str) -> None:
    api.drain_queue()
    recording = api.make_recording(api.user_a, hr_frames=120)
    # 0.00001 is "1e-05" in Python and "0.00001" in Go: the hash the worker reports must be the API's.
    params = {"wake_movement_g": 0.00001, "smooth_window_epochs": 5}
    created = api.request_analysis(api.user_a, recording, "baseline", params=params, expect=201).json()
    time.sleep(0.02)
    bad = api.request_analysis(api.user_a, recording, "baseline", params={"no_such_parameter": 1}, expect=201).json()

    api.run_worker_once()
    api.run_worker_once()

    good = finished(api, created["id"])
    assert good["status"] == "succeeded", good["error"]
    assert good["result"]["params"]["smooth_window_epochs"] == 5
    assert good["result"]["params"]["wake_movement_g"] == 0.00001
    assert good["result"]["params_hash"]
    refused = finished(api, bad["id"])
    assert refused["status"] == "failed"
    assert "no_such_parameter" in refused["error"]


def test_the_worker_asks_only_for_what_it_can_run(api: Backend, baseline: str) -> None:
    """An analysis of an algorithm another worker offers is left alone, not failed."""

    api.drain_queue()
    recording = api.make_recording(api.user_a, hr_frames=120)
    algorithm = unique("foreign")
    api.offer(api.worker, manifest(algorithm))
    foreign = api.request_analysis(api.user_a, recording, algorithm, expect=201).json()
    created = api.request_analysis(api.user_a, recording, "baseline", expect=201).json()

    api.run_worker_once()

    assert finished(api, created["id"])["status"] == "succeeded"
    assert api.analysis(api.user_a, foreign["id"])["status"] == "queued"
    api.drain_queue()


def test_the_running_worker_polls_works_and_stops_on_sigterm(api: Backend, baseline: str) -> None:
    api.drain_queue()
    process = subprocess.Popen(
        [sys.executable, "-m", "taiga_worker"],
        cwd=WORKER_DIR,
        env=api.worker_env(TAIGA_POLL_INTERVAL_S="0.2"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        # It was started before there was any work and picks it up on its own.
        time.sleep(1.0)
        recording = api.make_recording(api.user_a, hr_frames=120)
        created = api.request_analysis(api.user_a, recording, "baseline", expect=201).json()
        analysis = api.wait_for_status(api.user_a, created["id"], ("succeeded", "failed"), timeout_s=90)
        assert analysis["status"] == "succeeded", analysis["error"]
        assert analysis["result"]["provenance"]["worker"] == "integration-worker"
    finally:
        process.send_signal(signal.SIGTERM)
        try:
            _, stderr = process.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            _, stderr = process.communicate()
            pytest.fail("the worker did not stop on SIGTERM")
    assert process.returncode == 0, stderr
    assert "worker stopped" in stderr


# ---------------------------------------------------------------------------
# A worker that is woken by events instead of polling
# ---------------------------------------------------------------------------

# An hour: whatever the worker does within the test, it does not do it because it polled.
LONG_POLL = "3600"


def assert_stopped_cleanly(worker: Any) -> None:
    assert worker.returncode == 0, worker.log
    assert "worker stopped" in worker.log
    assert worker.stop_seconds is not None and worker.stop_seconds < 5, "SIGTERM took too long"


def test_a_worker_with_a_long_poll_interval_claims_new_work_within_seconds(api: Backend, baseline: str) -> None:
    api.drain_queue()
    with api.running_worker(TAIGA_POLL_INTERVAL_S=LONG_POLL) as worker:
        # Idle and listening: its first claim found nothing and the stream is open.
        worker.wait_for_log("event stream connected")
        worker.wait_for_log("waiting for an event")

        recording = api.make_recording(api.user_a, hr_frames=120)
        created = api.request_analysis(api.user_a, recording, "baseline", expect=201).json()

        worker.wait_for_log(f"claimed analysis {created['id']}", timeout_s=10)
        analysis = api.wait_for_status(api.user_a, created["id"], ("succeeded", "failed"), timeout_s=90)
        assert analysis["status"] == "succeeded", analysis["error"]
        assert analysis["result"]["provenance"]["worker"] == "integration-worker"

        # It is idle again and ready for the next one, which it also takes at once.
        worker.wait_for_log("waiting for an event", times=2)
        second = api.request_analysis(api.user_a, recording, "baseline", params={"smooth_window_epochs": 5}, expect=201).json()
        worker.wait_for_log(f"claimed analysis {second['id']}", timeout_s=10)
        assert api.wait_for_status(api.user_a, second["id"], ("succeeded", "failed"), timeout_s=90)["status"] == "succeeded"
    assert_stopped_cleanly(worker)


def test_a_worker_started_with_work_queued_claims_it_without_waiting_for_the_poll_interval(
    api: Backend, baseline: str
) -> None:
    api.drain_queue()
    recording = api.make_recording(api.user_a, hr_frames=120)
    created = api.request_analysis(api.user_a, recording, "baseline", expect=201).json()

    with api.running_worker(TAIGA_POLL_INTERVAL_S=LONG_POLL) as worker:
        worker.wait_for_log(f"claimed analysis {created['id']}", timeout_s=30)
        analysis = api.wait_for_status(api.user_a, created["id"], ("succeeded", "failed"), timeout_s=90)
        assert analysis["status"] == "succeeded", analysis["error"]
        worker.wait_for_log("event stream connected")
    assert_stopped_cleanly(worker)


def test_a_worker_whose_stream_dropped_claims_what_it_missed_once_it_is_connected_again(
    api: Backend, baseline: str
) -> None:
    api.drain_queue()
    proxy = CuttableProxy(int(api.base_url.rsplit(":", 1)[1]))
    try:
        with api.running_worker(TAIGA_POLL_INTERVAL_S=LONG_POLL, TAIGA_API_URL=proxy.url) as worker:
            worker.wait_for_log("event stream connected")
            worker.wait_for_log("waiting for an event")

            proxy.cut()
            # Its stream is gone and every try to open it again is refused.
            worker.wait_for_log("cannot listen for new analyses")
            recording = api.make_recording(api.user_a, hr_frames=120)
            created = api.request_analysis(api.user_a, recording, "baseline", expect=201).json()
            # Nobody told the worker, and it does not poll for an hour.
            time.sleep(1.5)
            assert api.analysis(api.user_a, created["id"])["status"] == "queued"
            assert f"claimed analysis {created['id']}" not in worker.log

            proxy.restore()
            worker.wait_for_log("listening for new analyses again", timeout_s=30)
            worker.wait_for_log(f"claimed analysis {created['id']}", timeout_s=10)
            analysis = api.wait_for_status(api.user_a, created["id"], ("succeeded", "failed"), timeout_s=90)
            assert analysis["status"] == "succeeded", analysis["error"]
        assert_stopped_cleanly(worker)
    finally:
        proxy.close()


# ---------------------------------------------------------------------------
# The OpenAPI document
# ---------------------------------------------------------------------------

HTTP_METHODS = ("get", "put", "post", "patch", "delete")


def concrete(path: str, operation: dict[str, Any]) -> str:
    """A path with its parameters filled in with something that matches its schema."""

    values = {"id": "x", "name": "hr.log", "asset": "swagger-ui.css"}
    for parameter in operation.get("parameters", []):
        if parameter["in"] == "path":
            enum = parameter.get("schema", {}).get("enum")
            if enum:
                values[parameter["name"]] = enum[0]
    return re.sub(r"\{(\w+)\}", lambda match: values[match.group(1)], path)


def test_the_openapi_document_and_the_served_routes_agree(api: Backend) -> None:
    document = api.get("/v1/openapi.json", expect=200).json()
    assert document["openapi"] == "3.1.0"
    paths = document["paths"]
    documented = {
        (method, path) for path, item in paths.items() for method in item if method in HTTP_METHODS or method == "head"
    }
    # The paths the contract promises (docs/api.md) are in it.
    for method, path in (
        ("get", "/v1/health"),
        ("post", "/v1/auth/login"),
        ("put", "/v1/recordings/{id}"),
        ("patch", "/v1/recordings/{id}/files/{name}"),
        ("post", "/v1/recordings/{id}/analyses"),
        ("post", "/v1/analyses/claim"),
        ("post", "/v1/analyses/{id}/complete"),
        ("put", "/v1/workers/me"),
    ):
        assert (method, path) in documented
    assert not [path for path in paths if not path.startswith("/v1/")]

    operation_ids = set()
    for path, item in paths.items():
        for method, operation in item.items():
            assert operation["operationId"] not in operation_ids
            operation_ids.add(operation["operationId"])
            assert operation["responses"]
            url = concrete(path, operation)
            secured = bool(operation.get("security"))

            # Every documented operation is served: without a token it reaches its access check
            # (or, for the public ones, its handler); an unknown route would be a 404.
            response = api.request(method.upper(), url)
            if secured:
                assert response.status == 401, (method, path)
                if method != "head":
                    assert_error(response, 401, "unauthorized")
            elif (method, path) == ("post", "/v1/auth/login"):
                assert_error(api.post(url, json={}), 400, "invalid_body")
            else:
                assert response.status == 200, (method, path)

    # And the other way round: a method the document does not list is not served.
    for path, item in paths.items():
        listed = set(item)
        for method in HTTP_METHODS:
            if method in listed:
                continue
            response = api.request(method.upper(), concrete(path, next(iter(item.values()))), token=api.user_a)
            assert response.status in (404, 405), (method, path, response.status)

    # The document describes shapes only: it carries no credentials.
    text = json.dumps(document)
    assert api.user_a.token not in text and api.worker.token not in text and PASSWORD not in text
