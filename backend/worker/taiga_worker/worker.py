from __future__ import annotations

import logging
import threading
from typing import Any, Callable, Mapping

from .api import ApiClient, ApiError
from .config import Config
from .runner import JobRunner, Outcome


class Worker:
    def __init__(
        self,
        config: Config,
        api: ApiClient,
        runner: JobRunner,
        *,
        logger: logging.Logger | None = None,
        sleep: Callable[[float], None] | None = None,
    ) -> None:
        self.config = config
        self.api = api
        self.runner = runner
        self.log = logger or logging.getLogger("taiga_worker")
        self._stop = threading.Event()
        self._sleep = sleep or self._stop.wait

    def request_stop(self) -> None:
        self._stop.set()
        self.runner.terminate()

    @property
    def stopping(self) -> bool:
        return self._stop.is_set()

    def _wait(self, seconds: float) -> None:
        if seconds > 0:
            self._sleep(seconds)

    def _login(self) -> None:
        self.api.login(self.config.email, self.config.password)
        self.log.info("authenticated to %s as %s", self.config.api_url, self.config.email)

    def login(self) -> None:
        """Authenticate eagerly (used by --once to surface bad credentials)."""

        self._login()

    def _call(self, fn: Callable[[], Any]) -> Any:
        if not self.api.token:
            self._login()
        try:
            return fn()
        except ApiError as exc:
            if exc.status != 401:
                raise
            self.log.info("token rejected; logging in again")
            self.api.token = None
            self._login()
            return fn()

    def run_once(self) -> bool:
        """Claim at most one job. Returns True when a job was processed."""

        job = self._call(lambda: self.api.claim(self.config.worker_id, self.config.lease_seconds))
        if not job:
            return False

        self.log.info(
            "claimed job %s (kind=%s model=%s attempt=%s)",
            job.get("id"),
            job.get("kind"),
            job.get("model"),
            job.get("attempts"),
        )

        outcome = self.runner.run(job)

        if self.stopping:
            # The lease will expire and the cron job will requeue this job, so a
            # restart never loses or permanently fails an interrupted job.
            self.log.info("shutting down; leaving job %s leased for requeue", job.get("id"))
            return True

        self._report(job, outcome)
        return True

    def _report(self, job: Mapping[str, Any], outcome: Outcome) -> None:
        job_id = str(job["id"])
        status = "done" if outcome.ok else "failed"
        error = None if outcome.ok else (outcome.error or "job failed")

        delay = 1.0
        for attempt in range(1, 4):
            try:
                self._call(
                    lambda: self.api.finish(
                        job_id,
                        self.config.worker_id,
                        status,
                        result=outcome.result if outcome.ok else None,
                        error=error,
                    )
                )
                if outcome.ok:
                    self.log.info("job %s done", job_id)
                else:
                    self.log.warning("job %s failed: %s", job_id, error)
                return
            except ApiError as exc:
                if attempt == 3:
                    self.log.error(
                        "could not report job %s after %s attempts (%s); "
                        "it will be requeued when the lease expires",
                        job_id,
                        attempt,
                        exc,
                    )
                    return
                self.log.warning(
                    "could not report job %s (%s); retrying in %ss", job_id, exc, delay
                )
                self._wait(delay)
                delay = min(delay * 2, 10.0)

    def run_forever(self) -> None:
        backoff = 1.0
        while not self.stopping:
            try:
                worked = self.run_once()
                backoff = 1.0
            except ApiError as exc:
                self.log.warning("API error: %s; retrying in %ss", exc, backoff)
                self._wait(backoff)
                backoff = min(backoff * 2, 60.0)
                continue
            except Exception:  # noqa: BLE001 - keep the supervisor alive
                self.log.exception("unexpected worker error; retrying in %ss", backoff)
                self._wait(backoff)
                backoff = min(backoff * 2, 60.0)
                continue

            if not worked:
                self._wait(self.config.poll_interval_s)

        self.log.info("worker stopped")
