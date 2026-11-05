from __future__ import annotations

import json
import logging
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Callable, Mapping

from . import __version__
from .api import ApiClient, ApiError
from .config import Config
from .runner import JobRunner, Outcome
from .upload import RECORDING_FILES, STREAM_IDS, verify_recording_file

# The API treats a worker it has not heard from for a while as gone, so the
# announcement is repeated at least this often.
ANNOUNCE_INTERVAL_S = 300.0


class Worker:
    def __init__(
        self,
        config: Config,
        api: ApiClient,
        runner: JobRunner,
        *,
        logger: logging.Logger | None = None,
        sleep: Callable[[float], None] | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.config = config
        self.api = api
        self.runner = runner
        self.log = logger or logging.getLogger("taiga_worker")
        self._stop = threading.Event()
        self._sleep = sleep or self._stop.wait
        self._clock = clock or time.monotonic
        self._capabilities: list[dict[str, Any]] | None = None
        self._algorithm_ids: list[str] = []
        self._announced_at: float | None = None

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
        # A new token always comes with a fresh announcement.
        self._announced_at = None
        try:
            self._announce()
        except ApiError as exc:
            self.log.warning("cannot announce algorithms yet: %s", exc)

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

    def _discover(self) -> None:
        if self._capabilities is not None:
            return
        try:
            from .analysis import discover

            registry = discover(self.config.algorithms)
            self._capabilities = registry.capabilities()
            self._algorithm_ids = [str(item["id"]) for item in self._capabilities if item.get("id")]
            if self.config.job_memory_mb and self.config.job_memory_mb < 1536:
                heavy = [name for name in ("wav2sleep", "sleep-ecg") if name in self._algorithm_ids]
                if heavy:
                    self.log.warning(
                        "%s needs about 1.5 GiB of address space; TAIGA_JOB_MEMORY_MB is %s",
                        " and ".join(heavy),
                        self.config.job_memory_mb,
                    )
            if registry.errors:
                self.log.warning("algorithm discovery errors: %s", "; ".join(registry.errors))
        except Exception:  # noqa: BLE001 - keep the supervisor alive
            self.log.exception("cannot discover algorithms")
            self._capabilities = []
            self._algorithm_ids = []
        if not self._capabilities:
            self.log.warning("no algorithms are installed; this worker will not claim anything")

    def _announce(self) -> None:
        self._discover()
        self.api.announce(self.config.worker_id, __version__, self._capabilities or [])
        first = self._announced_at is None
        self._announced_at = self._clock()
        self.log.log(
            logging.INFO if first else logging.DEBUG,
            "announced %s algorithm(s) to the API",
            len(self._capabilities or []),
        )

    def _announce_if_due(self) -> None:
        if not self.api.token:
            self._login()
        if (
            self._announced_at is not None
            and self._clock() - self._announced_at < ANNOUNCE_INTERVAL_S
        ):
            return
        try:
            self._call(self._announce)
        except ApiError as exc:
            self.log.warning("cannot announce algorithms yet: %s", exc)

    def run_once(self) -> bool:
        """Claim and process at most one analysis. Returns True when one was processed."""

        self._announce_if_due()
        if not self._algorithm_ids:
            return False
        # Ask for exactly the versions this worker has, so it is never handed
        # an analysis it cannot run (which would fail it for good).
        offered = [
            {"id": str(item["id"]), "version": str(item["version"])}
            for item in self._capabilities or []
            if item.get("id") and item.get("version")
        ]
        analysis = self._call(lambda: self.api.claim(offered, self.config.lease_seconds))
        if not analysis:
            return False

        self.log.info(
            "claimed analysis %s (algorithm=%s version=%s recording=%s attempt=%s)",
            analysis.get("id"),
            analysis.get("algorithm"),
            analysis.get("version"),
            analysis.get("recording"),
            analysis.get("attempts"),
        )

        outcome = self._process(analysis)

        if self.stopping:
            # The lease will expire and the API will requeue this analysis, so a
            # restart never loses or permanently fails an interrupted one.
            self.log.info(
                "shutting down; leaving analysis %s leased for requeue", analysis.get("id")
            )
            return True

        self._report(analysis, outcome)
        return True

    def _process(self, analysis: Mapping[str, Any]) -> Outcome:
        analysis_id = str(analysis.get("id") or "")
        recording_id = str(analysis.get("recording") or "")
        algorithm = str(analysis.get("algorithm") or "")
        if not analysis_id or not recording_id or not algorithm:
            return Outcome(
                ok=False,
                error="analysis is missing id, recording or algorithm",
                retryable=False,
            )

        # The lease on the analysis is what lets this worker read the recording.
        try:
            recording = self._call(lambda: self.api.recording(recording_id))
            entries = self._call(lambda: self.api.files(recording_id))
        except ApiError as exc:
            return Outcome(
                ok=False,
                error=f"cannot fetch the recording: {exc}",
                retryable=True,
            )

        meta = recording.get("meta")
        if not isinstance(meta, dict) or not meta:
            return Outcome(ok=False, error="recording has no meta", retryable=False)
        entries = [entry for entry in entries if entry.get("name") in RECORDING_FILES]
        if not entries:
            return Outcome(ok=False, error="recording has no files", retryable=False)

        with tempfile.TemporaryDirectory(prefix="taiga-recording-") as workdir:
            root = Path(workdir)
            # The analysis reads a recording directory, so the recording's meta
            # goes back to being a meta.json.
            (root / "meta.json").write_text(json.dumps(meta), encoding="utf-8")

            source: dict[str, Any] = {
                "files": {},
                "warnings": [],
                "stored_bytes": 0,
                "uncompressed_bytes": 0,
            }
            frames = 0
            for entry in entries:
                name = str(entry["name"])
                destination = root / name

                def download(name: str = name, destination: Path = destination) -> None:
                    self.api.download_file(recording_id, name, destination)

                try:
                    self._call(download)
                except ApiError as exc:
                    return Outcome(
                        ok=False,
                        error=f"cannot download {name}: {exc}",
                        retryable=True,
                    )

                # The API stores what the phone sent, so every file is checked,
                # compressed or not, and cut back to its valid prefix.
                check = verify_recording_file(destination, name)
                if check.truncated and check.error:
                    source["warnings"].append(f"{name}: {check.error}")
                if name in STREAM_IDS:
                    frames += check.frames
                stored_bytes = int(entry.get("size") or check.size)
                source["stored_bytes"] += stored_bytes
                source["uncompressed_bytes"] += check.size
                source["files"][name] = {
                    "encoding": str(entry.get("encoding") or "identity"),
                    "stored_bytes": stored_bytes,
                    "uncompressed_bytes": check.size,
                    "frames": check.frames,
                    "valid_bytes": check.valid_bytes,
                    "truncated_tail": check.truncated,
                    "error": check.error,
                }

            encodings = {item["encoding"] for item in source["files"].values()}
            source["encoding"] = encodings.pop() if len(encodings) == 1 else "mixed"
            if source["stored_bytes"] > 0:
                source["compression_ratio"] = round(
                    source["uncompressed_bytes"] / source["stored_bytes"], 3
                )
            else:
                source["compression_ratio"] = None
            if frames == 0:
                return Outcome(
                    ok=False,
                    error="recording has no valid frames",
                    retryable=False,
                )

            params = analysis.get("params")
            params = params if isinstance(params, dict) else {}
            outcome = self.runner.run(
                {
                    "id": analysis_id,
                    "recording_dir": workdir,
                    "model": algorithm,
                    "model_version": analysis.get("version"),
                    "params": params,
                    "algorithms": self._algorithm_ids,
                    "worker": self.config.worker_id,
                    "source": source,
                }
            )

        if not outcome.ok:
            return outcome
        # The subprocess answers {"analysis": {...}}; the API stores what is inside.
        result = outcome.result.get("analysis") if outcome.result else None
        if not isinstance(result, dict):
            return Outcome(ok=False, error="analysis produced no result", retryable=False)
        return Outcome(ok=True, result=result)

    def _report(self, analysis: Mapping[str, Any], outcome: Outcome) -> None:
        analysis_id = str(analysis["id"])
        if outcome.ok and outcome.result is None:
            outcome = Outcome(ok=False, error="analysis produced no result", retryable=False)
        error = outcome.error or "analysis failed"

        delay = 1.0
        for attempt in range(1, 4):
            try:
                if outcome.ok:
                    self._call(lambda: self.api.complete(analysis_id, outcome.result or {}))
                    self.log.info("analysis %s succeeded", analysis_id)
                else:
                    self._call(lambda: self.api.fail(analysis_id, error, bool(outcome.retryable)))
                    self.log.warning("analysis %s failed: %s", analysis_id, error)
                return
            except ApiError as exc:
                if exc.status in (404, 409):
                    # The lease ran out, or the user deleted the analysis.
                    self.log.warning(
                        "analysis %s is no longer leased to this worker (%s); dropping the work",
                        analysis_id,
                        exc,
                    )
                    return
                if exc.status is not None and exc.status < 500 and exc.status != 429:
                    # Retrying would only repeat it, e.g. 422 invalid_result.
                    self.log.error(
                        "the API refused the report of analysis %s (%s); dropping the work",
                        analysis_id,
                        exc,
                    )
                    return
                if attempt == 3:
                    self.log.error(
                        "could not report analysis %s after %s attempts (%s); "
                        "it will be requeued when the lease expires",
                        analysis_id,
                        attempt,
                        exc,
                    )
                    return
                self.log.warning(
                    "could not report analysis %s (%s); retrying in %ss", analysis_id, exc, delay
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
