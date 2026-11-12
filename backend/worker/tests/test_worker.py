from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any, Iterator, Mapping

import pytest

from taiga_worker.api import ApiClient, ApiError
from taiga_worker.config import Config
from taiga_worker.runner import Outcome
from taiga_worker.worker import ANNOUNCE_INTERVAL_S, Worker
from tests import analysis_fixture as fx
from tests.realtime_server import RealtimeServer as EventServer, change, queued, wait_until


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
        renew_status: int | None = None,
        lease_renewal: bool = True,
    ) -> None:
        self.token: str | None = None
        self.analyses = list(analyses or [])
        self.files_by_name = dict(files if files is not None else {"hr.log": b"x"})
        self.encodings = dict(encodings or {})
        self.announce_failures = announce_failures
        self.report_failures = report_failures
        self.report_status = report_status
        self.renew_status = renew_status
        self.lease_renewal = lease_renewal
        self.expire_token = False
        self.logins = 0
        self.claims: list[dict[str, Any]] = []
        self.renewals: list[dict[str, Any]] = []
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

    def renew(self, analysis_id: str, lease_seconds: int) -> dict[str, Any]:
        self.renewals.append({"id": analysis_id, "lease_seconds": lease_seconds})
        if self.renew_status is not None:
            raise ApiError("refused", status=self.renew_status)
        return {}

    def supports_lease_renewal(self) -> bool:
        return self.lease_renewal

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


def test_startup_logs_the_offered_algorithms(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level("INFO", logger="taiga_worker")
    api = FakeApi()
    worker = Worker(config(algorithms=("baseline",)), api, FakeRunner(), sleep=lambda _: None)

    worker.run_once()

    assert "offering algorithms: baseline" in caplog.text


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


def test_a_job_that_outlives_its_lease_keeps_renewing_and_completes() -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording())

    def outlive_the_lease() -> None:
        deadline = time.monotonic() + 15
        while len(api.renewals) < 3 and time.monotonic() < deadline:
            time.sleep(0.01)

    runner = FakeRunner(on_run=outlive_the_lease)
    worker = Worker(config(lease_seconds=1), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    assert len(api.renewals) >= 3
    assert all(item["id"] == "an1" and item["lease_seconds"] == 1 for item in api.renewals)
    assert api.completed == [{"id": "an1", "result": {"model": "m"}}]
    assert api.failed == []


@pytest.mark.parametrize("status", [404, 409])
def test_a_refused_renewal_stops_the_job_and_drops_the_work(status: int) -> None:
    api = FakeApi(analyses=[analysis()], files=hr_recording(), renew_status=status)
    release = threading.Event()

    class HangingRunner(FakeRunner):
        def run(self, job: Mapping[str, Any]) -> Outcome:
            self.jobs.append(job)
            release.wait(10)
            return self.outcome

        def terminate(self) -> None:
            self.terminated = True
            release.set()

    runner = HangingRunner()
    worker = Worker(config(lease_seconds=1), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    assert runner.terminated
    assert len(api.renewals) == 1
    assert api.completed == [] and api.failed == []


def test_a_long_job_warns_when_the_api_cannot_renew_leases(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level("WARNING", logger="taiga_worker")
    api = FakeApi(lease_renewal=False)
    worker = Worker(
        config(lease_seconds=60, job_timeout_s=120), api, FakeRunner(), sleep=lambda _: None
    )

    worker.run_once()

    assert "cannot renew a lease" in caplog.text


def test_a_long_job_on_a_renewing_api_does_not_warn(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level("WARNING", logger="taiga_worker")
    worker = Worker(
        config(lease_seconds=60, job_timeout_s=120), FakeApi(), FakeRunner(), sleep=lambda _: None
    )

    worker.run_once()

    assert "cannot renew a lease" not in caplog.text


def test_a_job_that_fits_its_lease_does_not_warn_on_an_older_api(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level("WARNING", logger="taiga_worker")
    worker = Worker(
        config(lease_seconds=60, job_timeout_s=60),
        FakeApi(lease_renewal=False),
        FakeRunner(),
        sleep=lambda _: None,
    )

    worker.run_once()

    assert "cannot renew a lease" not in caplog.text


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


class IdleWake(threading.Event):
    """The wake event of a worker that is stopped when it has waited ``waits`` times.

    Nothing really waits, so a test sees what the worker did between two idle moments.
    """

    def __init__(self, waits: int = 1) -> None:
        super().__init__()
        self.waits = waits
        self.timeouts: list[float | None] = []
        self.set_when_waiting: list[bool] = []
        self.worker: Worker | None = None

    def wait(self, timeout: float | None = None) -> bool:
        self.timeouts.append(timeout)
        self.set_when_waiting.append(self.is_set())
        if len(self.timeouts) >= self.waits and self.worker is not None:
            self.worker.request_stop()
        return self.is_set()


def idle_worker(api: FakeApi, *, waits: int = 1, **changes: Any) -> tuple[Worker, IdleWake]:
    wake = IdleWake(waits)
    worker = Worker(
        config(**changes), api, FakeRunner(), sleep=lambda _: None, events=False, wake=wake
    )
    wake.worker = worker
    return worker, wake


def test_an_idle_worker_waits_for_an_event_for_the_poll_interval() -> None:
    api = FakeApi()
    worker, wake = idle_worker(api, poll_interval_s=42)

    worker.run_forever()

    assert wake.timeouts == [42]
    assert len(api.claims) == 1


def test_the_worker_claims_back_to_back_while_there_is_work() -> None:
    api = FakeApi(
        analyses=[analysis(id="an1"), analysis(id="an2"), analysis(id="an3")], files=hr_recording()
    )
    worker, wake = idle_worker(api, poll_interval_s=42)

    worker.run_forever()

    assert [item["id"] for item in api.completed] == ["an1", "an2", "an3"]
    assert len(api.claims) == 4  # three analyses and the empty queue
    assert wake.timeouts == [42]  # it only waited once the queue was empty


def test_an_event_that_came_before_the_claim_does_not_make_the_worker_claim_twice() -> None:
    api = FakeApi()
    worker, wake = idle_worker(api)
    wake.set()  # rung before the loop starts, e.g. by a connection

    worker.run_forever()

    assert len(api.claims) == 1
    assert wake.set_when_waiting == [False]  # cleared before claiming, so it waits


def test_a_stop_request_ends_the_wait_of_an_idle_worker() -> None:
    api = FakeApi()
    worker = Worker(config(poll_interval_s=3600), api, FakeRunner(), events=False)
    thread = threading.Thread(target=worker.run_forever, daemon=True)
    thread.start()
    assert wait_until(lambda: len(api.claims) == 1)
    time.sleep(0.1)

    started = time.monotonic()
    worker.request_stop()
    thread.join(5)

    assert not thread.is_alive()
    assert time.monotonic() - started < 2


def test_an_event_wakes_an_idle_worker_long_before_the_poll_interval() -> None:
    api = FakeApi(files=hr_recording())
    worker = Worker(config(poll_interval_s=3600), api, FakeRunner(), events=False)
    thread = threading.Thread(target=worker.run_forever, daemon=True)
    thread.start()
    try:
        assert wait_until(lambda: len(api.claims) == 1)
        time.sleep(0.1)  # idle now

        api.analyses.append(analysis(id="an9"))
        worker.wake.set()

        assert wait_until(lambda: [item["id"] for item in api.completed] == ["an9"], 5)
    finally:
        worker.request_stop()
        thread.join(5)
    assert not thread.is_alive()


def test_run_forever_recovers_from_api_errors() -> None:
    class FlakyApi(FakeApi):
        def claim(self, algorithms: list[dict[str, str]], lease_seconds: int) -> dict[str, Any] | None:
            if not self.claims:
                self.claims.append({})
                raise ApiError("down", status=502)
            return super().claim(algorithms, lease_seconds)

    api = FlakyApi(analyses=[analysis()], files=hr_recording())
    sleeps: list[float] = []
    worker, _ = idle_worker(api)
    worker._sleep = sleeps.append

    worker.run_forever()

    assert api.completed[0]["id"] == "an1"
    assert sleeps == [1.0]


# --- the worker with its realtime stream, against a stub of the API ---


@pytest.fixture()
def api_stub() -> Iterator[EventServer]:
    stub = EventServer()
    try:
        yield stub
    finally:
        stub.close()


class Listening(threading.Event):
    """A wake event that counts its waits, to know when the worker is idle."""

    def __init__(self) -> None:
        super().__init__()
        self.waits = 0

    def wait(self, timeout: float | None = None) -> bool:
        self.waits += 1
        return super().wait(timeout)


def settled(read: Any, quiet_s: float = 0.4) -> Any:
    """The value of ``read()`` once it has stayed the same for ``quiet_s``."""

    value, since = read(), time.monotonic()
    while time.monotonic() - since < quiet_s:
        time.sleep(0.02)
        current = read()
        if current != value:
            value, since = current, time.monotonic()
    return value


@pytest.fixture()
def listening_worker(api_stub: EventServer) -> Iterator[tuple[Worker, Listening, threading.Thread]]:
    """A worker that waits an hour between claims, listening to the stub."""

    wake = Listening()
    api = ApiClient(api_stub.url, timeout_s=5)
    worker = Worker(config(api_url=api_stub.url, poll_interval_s=3600), api, FakeRunner(), wake=wake)
    thread = threading.Thread(target=worker.run_forever, daemon=True)
    thread.start()
    try:
        yield worker, wake, thread
    finally:
        worker.request_stop()
        thread.join(10)
        assert not thread.is_alive()


def test_the_worker_claims_when_connected_and_when_an_analysis_is_queued(api_stub, listening_worker) -> None:
    worker, wake, _ = listening_worker
    (connection,) = api_stub.wait_for_connections(1)
    assert wait_until(lambda: wake.waits >= 1)
    claims = settled(lambda: len(api_stub.claims))
    assert claims >= 1  # at least the one it makes on connecting

    # Changes that are not about new work leave it idle.
    connection.send(change("update", id="a", recording="r", status="running"))
    connection.send(change("update", id="a", recording="r", status="succeeded"))
    assert settled(lambda: len(api_stub.claims)) == claims

    connection.send(queued())

    assert wait_until(lambda: len(api_stub.claims) == claims + 1, 5)


def test_the_worker_claims_after_every_reconnect(api_stub, listening_worker) -> None:
    worker, wake, _ = listening_worker
    (first,) = api_stub.wait_for_connections(1)
    assert wait_until(lambda: wake.waits >= 1)
    claims = settled(lambda: len(api_stub.claims))

    first.cut()  # whatever is queued from here on is announced to nobody

    api_stub.wait_for_connections(2)
    assert wait_until(lambda: len(api_stub.claims) == claims + 1, 5)
    assert first.gone.is_set()


def test_a_rejected_token_is_replaced_once_for_the_stream_and_the_worker(api_stub, listening_worker) -> None:
    worker, wake, _ = listening_worker
    (first,) = api_stub.wait_for_connections(1)
    assert wait_until(lambda: wake.waits >= 1)
    claims = settled(lambda: len(api_stub.claims))
    assert api_stub.logins == 1

    api_stub.revoked.add("token-1")
    first.cut()

    api_stub.wait_for_connections(2)
    assert wait_until(lambda: len(api_stub.claims) == claims + 1, 5)
    assert api_stub.logins == 2
    assert api_stub.requests[-1]["token"] == "token-2"
    assert api_stub.claims[-1] == "token-2"


def test_stopping_closes_the_stream_and_ends_the_worker_at_once(api_stub, listening_worker) -> None:
    worker, wake, thread = listening_worker
    (connection,) = api_stub.wait_for_connections(1)
    assert wait_until(lambda: wake.waits >= 1)
    time.sleep(0.1)

    started = time.monotonic()
    worker.request_stop()
    thread.join(5)

    assert not thread.is_alive()
    assert time.monotonic() - started < 2
    assert connection.gone.wait(2)
