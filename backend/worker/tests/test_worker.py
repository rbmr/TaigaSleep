from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Mapping

from taiga_worker.api import ApiError
from taiga_worker.config import Config
from taiga_worker.runner import Outcome
from taiga_worker.worker import ANNOUNCE_INTERVAL_S, Worker
from tests import analysis_fixture as fx


def config(**changes: Any) -> Config:
    values: dict[str, Any] = {
        "api_url": "http://api",
        "email": "worker@example.com",
        "password": "secret",
        "worker_id": "worker-1",
        "poll_interval_s": 0.01,
        "lease_seconds": 60,
        "job_memory_mb": 256,
        "job_timeout_s": 10,
        "once": False,
        "log_level": "INFO",
        "algorithms": None,
    }
    values.update(changes)
    return Config(**values)


def analysis(**changes: Any) -> dict[str, Any]:
    values: dict[str, Any] = {
        "id": "an1",
        "recording": "rec1",
        "algorithm": "baseline",
        "version": "1.1.0",
        "params": {},
        "status": "running",
        "attempts": 1,
    }
    values.update(changes)
    return values


class FakeApi:
    """The worker's side of API v1, answering from memory."""

    def __init__(
        self,
        analyses: list[dict[str, Any]] | None = None,
        *,
        files: Mapping[str, bytes] | None = None,
        encodings: Mapping[str, str] | None = None,
        announce_failures: int = 0,
        report_failures: int = 0,
        report_status: int | None = None,
    ) -> None:
        self.token: str | None = None
        self.analyses = list(analyses or [])
        self.files_by_name = dict(files if files is not None else {"hr.log": b"x"})
        self.encodings = dict(encodings or {})
        self.announce_failures = announce_failures
        self.report_failures = report_failures
        self.report_status = report_status
        self.expire_token = False
        self.logins = 0
        self.claims: list[dict[str, Any]] = []
        self.announced: list[dict[str, Any]] = []
        self.completed: list[dict[str, Any]] = []
        self.failed: list[dict[str, Any]] = []
        self.report_calls = 0
        self.fetched: list[str] = []
        self.downloads: list[str] = []

    def login(self, email: str, password: str) -> str:
        self.logins += 1
        self.token = f"token-{self.logins}"
        return self.token

    def announce(self, name: str, version: str, algorithms: list[dict[str, Any]]) -> None:
        if self.announce_failures > 0:
            self.announce_failures -= 1
            raise ApiError("down", status=503)
        self.announced.append({"name": name, "version": version, "algorithms": algorithms})

    def claim(self, algorithms: list[dict[str, str]], lease_seconds: int) -> dict[str, Any] | None:
        self.claims.append({"algorithms": algorithms, "lease_seconds": lease_seconds})
        if self.expire_token:
            self.expire_token = False
            self.token = None
            raise ApiError("expired", status=401, code="unauthorized")
        return self.analyses.pop(0) if self.analyses else None

    def recording(self, recording_id: str) -> dict[str, Any]:
        self.fetched.append(recording_id)
        return {"id": recording_id, "meta": fx.meta(recording_id)}

    def files(self, recording_id: str) -> list[dict[str, Any]]:
        return [
            {
                "name": name,
                "size": len(body),
                "encoding": self.encodings.get(name, "identity"),
                "uncompressed_size": len(body),
            }
            for name, body in self.files_by_name.items()
        ]

    def download_file(self, recording_id: str, name: str, destination: Path) -> None:
        self.downloads.append(name)
        destination.write_bytes(self.files_by_name[name])

    def _report(self, bucket: list[dict[str, Any]], entry: dict[str, Any]) -> dict[str, Any]:
        self.report_calls += 1
        if self.report_status is not None:
            raise ApiError("refused", status=self.report_status)
        if self.report_failures > 0:
            self.report_failures -= 1
            raise ApiError("temporary", status=503)
        bucket.append(entry)
        return {}

    def complete(self, analysis_id: str, result: Mapping[str, Any]) -> dict[str, Any]:
        return self._report(self.completed, {"id": analysis_id, "result": result})

    def fail(self, analysis_id: str, error: str, retryable: bool) -> dict[str, Any]:
        return self._report(self.failed, {"id": analysis_id, "error": error, "retryable": retryable})


class FakeRunner:
    def __init__(self, outcome: Outcome | None = None, on_run=None) -> None:
        self.outcome = outcome or Outcome(ok=True, result={"analysis": {"model": "m"}})
        self.on_run = on_run
        self.jobs: list[Mapping[str, Any]] = []
        self.recordings: list[dict[str, bytes]] = []
        self.terminated = False

    def run(self, job: Mapping[str, Any]) -> Outcome:
        self.jobs.append(job)
        root = Path(str(job["recording_dir"]))
        self.recordings.append({p.name: p.read_bytes() for p in root.iterdir() if p.is_file()})
        if self.on_run:
            self.on_run()
        return self.outcome

    def terminate(self) -> None:
        self.terminated = True


class Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


def hr_recording() -> dict[str, bytes]:
    return {"hr.log": fx.hr_log(duration_s=60)}


def test_empty_queue_returns_false() -> None:
    api = FakeApi()
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)
    assert not worker.run_once()
    assert len(api.claims) == 1  # one login, one empty claim
    assert api.claims[0]["lease_seconds"] == 60


def test_login_announces_the_installed_algorithms() -> None:
    api = FakeApi()
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

    worker.run_once()

    assert len(api.announced) == 1
    announced = api.announced[0]
    assert announced["name"] == "worker-1"
    assert announced["version"]
    ids = {item["id"] for item in announced["algorithms"]}
    assert "baseline" in ids
    # What the API needs to validate an announcement.
    for item in announced["algorithms"]:
        assert item["version"] and item["name"] and item["license"]


def test_claim_asks_for_the_installed_versions_only() -> None:
    api = FakeApi()
    worker = Worker(config(algorithms=("baseline",)), api, FakeRunner(), sleep=lambda _: None)

    worker.run_once()

    offered = api.claims[0]["algorithms"]
    assert [item["id"] for item in offered] == ["baseline"]
    assert offered[0]["version"] == api.announced[0]["algorithms"][0]["version"]


def test_a_worker_without_algorithms_claims_nothing() -> None:
    api = FakeApi(analyses=[analysis()])
    worker = Worker(config(algorithms=("no-such-algorithm",)), api, FakeRunner(), sleep=lambda _: None)

    assert not worker.run_once()
    assert api.claims == []
    assert api.announced[0]["algorithms"] == []


def test_announcement_is_repeated_before_the_api_forgets_the_worker() -> None:
    api = FakeApi()
    clock = Clock()
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None, clock=clock)

    worker.run_once()
    clock.now = ANNOUNCE_INTERVAL_S - 1
    worker.run_once()
    assert len(api.announced) == 1

    clock.now = ANNOUNCE_INTERVAL_S + 1
    worker.run_once()
    assert len(api.announced) == 2
    clock.now = ANNOUNCE_INTERVAL_S * 2
    worker.run_once()
    assert len(api.announced) == 2


def test_a_failed_announcement_does_not_stop_the_worker_and_is_retried() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording(), announce_failures=2)
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

    assert worker.run_once()
    assert api.announced == []
    assert len(api.completed) == 1

    worker.run_once()
    assert len(api.announced) == 1


def test_success_completes_with_the_analysis_result() -> None:
    api = FakeApi(analyses=[analysis(params={"smooth_window_epochs": 5})], files=hr_recording())
    runner = FakeRunner(Outcome(ok=True, result={"analysis": {"model": "baseline", "x": 1}}))
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    # The API stores what is inside the {"analysis": ...} the subprocess prints.
    assert api.completed == [{"id": "an1", "result": {"model": "baseline", "x": 1}}]
    assert api.failed == []
    job = runner.jobs[0]
    assert job["id"] == "an1"
    assert job["model"] == "baseline"
    assert job["model_version"] == "1.1.0"
    assert job["params"] == {"smooth_window_epochs": 5}
    assert job["worker"] == "worker-1"
    assert api.fetched == ["rec1"]


def test_the_recording_meta_becomes_a_meta_json() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording())
    runner = FakeRunner()
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    recording = runner.recordings[0]
    assert sorted(recording) == ["hr.log", "meta.json"]
    assert json.loads(recording["meta.json"]) == fx.meta("rec1")
    assert recording["hr.log"] == hr_recording()["hr.log"]


def test_failure_reports_the_error_and_whether_to_retry() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording())
    runner = FakeRunner(Outcome(ok=False, error="bad recording", retryable=True))
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    assert api.completed == []
    assert api.failed == [{"id": "an1", "error": "bad recording", "retryable": True}]


def test_a_result_without_an_analysis_fails_for_good() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording())
    runner = FakeRunner(Outcome(ok=True, result={"unexpected": True}))
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    assert api.completed == []
    assert api.failed[0]["retryable"] is False
    assert "no result" in api.failed[0]["error"]


def test_an_unreadable_recording_fails_as_retryable() -> None:
    class DownApi(FakeApi):
        def recording(self, recording_id: str) -> dict[str, Any]:
            raise ApiError("not found", status=404, code="not_found")

    api = DownApi(analyses=[analysis()])
    runner = FakeRunner()
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    assert runner.jobs == []
    assert api.failed[0]["retryable"] is True
    assert "cannot fetch the recording" in api.failed[0]["error"]


def test_401_while_claiming_triggers_one_relogin_and_a_new_announcement() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording())
    api.expire_token = True
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

    assert worker.run_once()

    assert api.logins == 2
    assert len(api.announced) == 2
    assert len(api.completed) == 1


def test_report_is_retried_on_a_server_error() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording(), report_failures=2)
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

    assert worker.run_once()

    assert api.report_calls == 3
    assert api.completed[0]["id"] == "an1"


def test_a_report_that_keeps_failing_is_given_up() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording(), report_failures=10)
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

    assert worker.run_once()

    assert api.report_calls == 3
    assert api.completed == []


def test_a_lost_lease_drops_the_work_without_retrying() -> None:
    for status in (404, 409):
        api = FakeApi(analyses=[analysis()], files=hr_recording(), report_status=status)
        worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

        assert worker.run_once()

        assert api.report_calls == 1


def test_a_rejected_result_is_not_sent_again() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording(), report_status=422)
    worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

    assert worker.run_once()

    assert api.report_calls == 1


def test_stop_during_analysis_leaves_it_leased() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording())
    holder: dict[str, Worker] = {}
    runner = FakeRunner(on_run=lambda: holder["worker"].request_stop())
    worker = Worker(config(), api, runner, sleep=lambda _: None)
    holder["worker"] = worker

    assert worker.run_once()
    assert api.completed == [] and api.failed == []
    assert runner.terminated


def test_run_forever_polls_then_stops() -> None:
    api = FakeApi()
    holder: dict[str, Worker] = {}
    sleeps: list[float] = []

    def fake_sleep(seconds: float) -> None:
        sleeps.append(seconds)
        holder["worker"].request_stop()

    worker = Worker(config(), api, FakeRunner(), sleep=fake_sleep)
    holder["worker"] = worker
    worker.run_forever()

    assert sleeps == [config().poll_interval_s]


def test_run_forever_recovers_from_api_errors() -> None:
    class FlakyApi(FakeApi):
        def claim(self, algorithms: list[dict[str, str]], lease_seconds: int) -> dict[str, Any] | None:
            if not self.claims:
                self.claims.append({})
                raise ApiError("down", status=502)
            return super().claim(algorithms, lease_seconds)

    api = FlakyApi(analyses=[analysis()], files=hr_recording())
    holder: dict[str, Worker] = {}
    sleeps: list[float] = []

    def fake_sleep(seconds: float) -> None:
        sleeps.append(seconds)
        if len(sleeps) == 1:
            return
        holder["worker"].request_stop()

    worker = Worker(config(), api, FakeRunner(), sleep=fake_sleep)
    holder["worker"] = worker
    worker.run_forever()

    assert api.completed[0]["id"] == "an1"
    assert sleeps[0] == 1.0
