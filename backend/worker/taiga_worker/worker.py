from __future__ import annotations

import logging
import tempfile
import threading
from pathlib import Path
from typing import Any, Callable, Mapping

from . import __version__
from .api import ApiClient, ApiError
from .config import Config
from .runner import JobRunner, Outcome

RECORDING_FILES = {"meta.json", "events.jsonl", "hr.log", "ecg.log", "acc.log"}


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
        self._capabilities: list[dict[str, Any]] | None = None
        self._capabilities_reported = False
        self._algorithm_ids: list[str] = []

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

    def _ensure_capabilities(self) -> None:
        if self._capabilities is None:
            try:
                from .analysis import discover

                registry = discover(self.config.algorithms)
                self._capabilities = registry.capabilities()
                self._algorithm_ids = [
                    str(item["id"]) for item in self._capabilities if item.get("id")
                ]
                if registry.errors:
                    self.log.warning("algorithm discovery errors: %s", "; ".join(registry.errors))
            except Exception:  # noqa: BLE001 - keep the supervisor alive
                self.log.exception("cannot discover algorithms; claiming without a model filter")
                self._capabilities = []
                self._algorithm_ids = []

        if self._capabilities_reported:
            return
        try:
            self._call(
                lambda: self.api.report_capabilities(
                    self.config.worker_id,
                    __version__,
                    self._capabilities or [],
                )
            )
            self._capabilities_reported = True
            self.log.info("reported %s algorithm(s) to the API", len(self._capabilities or []))
        except ApiError as exc:
            self.log.warning("cannot report algorithms yet: %s", exc)

    def run_once(self) -> bool:
        """Claim and process at most one job. Returns True when a job was processed."""

        self._ensure_capabilities()
        job = self._call(
            lambda: self.api.claim(
                self.config.worker_id,
                self.config.lease_seconds,
                self._algorithm_ids,
            )
        )
        if not job:
            return False

        self.log.info(
            "claimed job %s (kind=%s model=%s version=%s attempt=%s)",
            job.get("id"),
            job.get("kind"),
            job.get("model"),
            job.get("model_version"),
            job.get("attempts"),
        )

        outcome = self._process(job)

        if self.stopping:
            # The lease will expire and the cron job will requeue this job, so a
            # restart never loses or permanently fails an interrupted job.
            self.log.info("shutting down; leaving job %s leased for requeue", job.get("id"))
            return True

        self._report(job, outcome)
        return True

    def _process(self, job: Mapping[str, Any]) -> Outcome:
        job_id = str(job.get("id") or "")
        session = str(job.get("session") or "")
        analysis = str(job.get("analysis") or "")
        if not job_id or not session or not analysis:
            return Outcome(
                ok=False,
                error="job is missing id, session or analysis",
                retryable=False,
            )

        try:
            manifest = self._call(
                lambda: self.api.manifest(session, job_id, self.config.worker_id)
            )
        except ApiError as exc:
            return Outcome(
                ok=False,
                error=f"cannot fetch recording manifest: {exc}",
                retryable=True,
            )

        upload = manifest.get("upload") if isinstance(manifest, dict) else None
        files = upload.get("files") if isinstance(upload, dict) else None
        if not isinstance(files, dict) or not files:
            return Outcome(ok=False, error="recording has no files", retryable=False)

        with tempfile.TemporaryDirectory(prefix="taiga-recording-") as workdir:
            for name in files:
                if name not in RECORDING_FILES:
                    continue
                destination = Path(workdir) / name

                def download(name: str = name, destination: Path = destination) -> None:
                    self.api.download_file(
                        session,
                        name,
                        destination,
                        job_id,
                        self.config.worker_id,
                    )

                try:
                    self._call(download)
                except ApiError as exc:
                    return Outcome(
                        ok=False,
                        error=f"cannot download {name}: {exc}",
                        retryable=True,
                    )

            run_job = dict(job)
            run_job["recording_dir"] = workdir
            run_job["params"] = job.get("params") or {}
            run_job["params_hash"] = job.get("params_hash")
            run_job["model_version"] = job.get("model_version")
            run_job["algorithms"] = self._algorithm_ids
            run_job["worker"] = self.config.worker_id
            return self.runner.run(run_job)

    def _report(self, job: Mapping[str, Any], outcome: Outcome) -> None:
        job_id = str(job["id"])
        if outcome.ok and outcome.result is None:
            outcome = Outcome(ok=False, error="job produced no result", retryable=False)
        status = "done" if outcome.ok else "failed"
        error = None if outcome.ok else (outcome.error or "job failed")
        retryable = None if outcome.ok else bool(outcome.retryable)

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
                        retryable=retryable,
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
