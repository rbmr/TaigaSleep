from __future__ import annotations

import unittest
from typing import Any, Mapping

from taiga_worker.api import ApiError
from taiga_worker.config import Config
from taiga_worker.runner import Outcome
from taiga_worker.worker import Worker


def config() -> Config:
    return Config(
        api_url="http://api",
        email="worker@example.com",
        password="secret",
        worker_id="worker-1",
        poll_interval_s=0.01,
        lease_seconds=60,
        job_memory_mb=256,
        job_timeout_s=10,
        once=False,
        log_level="INFO",
        algorithms=None,
    )


class FakeApi:
    def __init__(
        self,
        jobs: list[dict[str, Any]] | None = None,
        finish_failures: int = 0,
    ) -> None:
        self.token: str | None = None
        self.jobs = list(jobs or [])
        self.finish_failures = finish_failures
        self.logins = 0
        self.claims = 0
        self.finished: list[dict[str, Any]] = []
        self.expire_token = False
        self.reported: list[dict[str, Any]] = []
        self.manifests: list[str] = []
        self.downloads: list[str] = []

    def login(self, email: str, password: str) -> str:
        self.logins += 1
        self.token = f"token-{self.logins}"
        return self.token

    def report_capabilities(
        self,
        worker: str,
        version: str,
        algorithms: list[dict[str, Any]],
    ) -> dict[str, Any]:
        self.reported.append(
            {"worker": worker, "version": version, "algorithms": algorithms}
        )
        return {"ok": True}

    def claim(
        self,
        worker: str,
        lease_seconds: int,
        algorithms: list[str] | None = None,
    ) -> dict[str, Any] | None:
        self.claims += 1
        if self.expire_token:
            self.expire_token = False
            self.token = None
            raise ApiError("expired", status=401)
        return self.jobs.pop(0) if self.jobs else None

    def manifest(self, session_id: str, job_id: str, worker: str) -> dict[str, Any]:
        self.manifests.append(session_id)
        return {"upload": {"files": {"meta.json": {"length": 1}}}}

    def download_file(self, session_id: str, name: str, destination, job_id: str, worker: str) -> None:
        self.downloads.append(name)
        destination.write_bytes(b"{}")

    def finish(
        self,
        job_id: str,
        worker: str,
        status: str,
        result: Mapping[str, Any] | None = None,
        error: str | None = None,
        retryable: bool | None = None,
    ) -> dict[str, Any]:
        if self.finish_failures > 0:
            self.finish_failures -= 1
            raise ApiError("temporary", status=503)
        self.finished.append(
            {
                "job_id": job_id,
                "worker": worker,
                "status": status,
                "result": result,
                "error": error,
                "retryable": retryable,
            }
        )
        return {}


class FakeRunner:
    def __init__(self, outcome: Outcome | None = None, on_run=None) -> None:
        self.outcome = outcome or Outcome(ok=True, result={"ok": True})
        self.on_run = on_run
        self.jobs: list[Mapping[str, Any]] = []
        self.terminated = False

    def run(self, job: Mapping[str, Any]) -> Outcome:
        self.jobs.append(job)
        if self.on_run:
            self.on_run()
        return self.outcome

    def terminate(self) -> None:
        self.terminated = True


class WorkerTest(unittest.TestCase):
    def test_empty_queue_returns_false(self) -> None:
        api = FakeApi()
        worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)
        self.assertFalse(worker.run_once())
        self.assertEqual(api.claims, 1)  # one login, one empty claim

    def test_success_reports_done(self) -> None:
        api = FakeApi(jobs=[{"id": "job1", "kind": "analysis", "model": "m", "session": "s1", "analysis": "a1"}])
        runner = FakeRunner(Outcome(ok=True, result={"ok": True}))
        worker = Worker(config(), api, runner, sleep=lambda _: None)

        self.assertTrue(worker.run_once())

        self.assertEqual(runner.jobs[0]["id"], "job1")
        self.assertEqual(
            api.finished,
            [
                {
                    "job_id": "job1",
                    "worker": "worker-1",
                    "status": "done",
                    "result": {"ok": True},
                    "error": None,
                    "retryable": None,
                }
            ],
        )

    def test_failure_reports_failed(self) -> None:
        api = FakeApi(jobs=[{"id": "job1", "session": "s1", "analysis": "a1"}])
        runner = FakeRunner(Outcome(ok=False, error="bad recording"))
        worker = Worker(config(), api, runner, sleep=lambda _: None)

        self.assertTrue(worker.run_once())

        self.assertEqual(api.finished[0]["status"], "failed")
        self.assertEqual(api.finished[0]["error"], "bad recording")

    def test_401_while_claiming_triggers_one_relogin(self) -> None:
        api = FakeApi(jobs=[{"id": "job1", "session": "s1", "analysis": "a1"}])
        api.expire_token = True
        worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

        self.assertTrue(worker.run_once())
        self.assertGreaterEqual(api.logins, 2)

    def test_report_is_retried(self) -> None:
        api = FakeApi(jobs=[{"id": "job1", "session": "s1", "analysis": "a1"}], finish_failures=2)
        worker = Worker(config(), api, FakeRunner(), sleep=lambda _: None)

        self.assertTrue(worker.run_once())
        self.assertEqual(api.finished[0]["job_id"], "job1")

    def test_stop_during_job_leaves_it_leased(self) -> None:
        api = FakeApi(jobs=[{"id": "job1", "session": "s1", "analysis": "a1"}])
        holder: dict[str, Worker] = {}
        runner = FakeRunner(on_run=lambda: holder["worker"].request_stop())
        worker = Worker(config(), api, runner, sleep=lambda _: None)
        holder["worker"] = worker

        self.assertTrue(worker.run_once())
        self.assertEqual(api.finished, [])
        self.assertTrue(runner.terminated)

    def test_run_forever_polls_then_stops(self) -> None:
        api = FakeApi()
        holder: dict[str, Worker] = {}
        sleeps: list[float] = []

        def fake_sleep(seconds: float) -> None:
            sleeps.append(seconds)
            holder["worker"].request_stop()

        worker = Worker(config(), api, FakeRunner(), sleep=fake_sleep)
        holder["worker"] = worker
        worker.run_forever()

        self.assertEqual(sleeps, [config().poll_interval_s])

    def test_run_forever_recovers_from_api_errors(self) -> None:
        api = FakeApi(jobs=[{"id": "job1", "session": "s1", "analysis": "a1"}])

        class FlakyApi(FakeApi):
            def claim(
                self,
                worker: str,
                lease_seconds: int,
                algorithms: list[str] | None = None,
            ) -> dict[str, Any] | None:
                if self.claims == 0:
                    self.claims += 1
                    raise ApiError("down", status=502)
                return super().claim(worker, lease_seconds, algorithms)

        api = FlakyApi(jobs=[{"id": "job1", "session": "s1", "analysis": "a1"}])
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

        self.assertEqual(api.finished[0]["job_id"], "job1")
        self.assertEqual(sleeps[0], 1.0)


if __name__ == "__main__":
    unittest.main()
