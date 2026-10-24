from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

# Single-threaded numeric libraries. A sleep analysis run should not fan out
# into a dozen BLAS/OpenMP threads on a small server.
SINGLE_THREAD_ENV = {
    "OMP_NUM_THREADS": "1",
    "OPENBLAS_NUM_THREADS": "1",
    "MKL_NUM_THREADS": "1",
    "NUMEXPR_NUM_THREADS": "1",
    "VECLIB_MAXIMUM_THREADS": "1",
    "BLIS_NUM_THREADS": "1",
    "MALLOC_ARENA_MAX": "2",
}


@dataclass(frozen=True)
class Outcome:
    ok: bool
    result: Mapping[str, Any] | None = None
    error: str | None = None


class JobRunner:
    """Run exactly one job in a fresh, resource-limited subprocess."""

    def __init__(
        self,
        memory_mb: int = 1024,
        timeout_s: float = 3600.0,
        *,
        command: Sequence[str] | None = None,
        python: str | None = None,
    ) -> None:
        self.memory_mb = memory_mb
        self.timeout_s = timeout_s
        self.command = list(command) if command else [python or sys.executable, "-m", "taiga_worker.job"]
        self._process: subprocess.Popen[str] | None = None

    def _preexec(self) -> None:  # pragma: no cover - exercised via the real subprocess
        if os.name != "posix":
            return
        import resource

        if self.memory_mb > 0:
            limit = self.memory_mb * 1024 * 1024
            resource.setrlimit(resource.RLIMIT_AS, (limit, limit))
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))

    def run(self, job: Mapping[str, Any]) -> Outcome:
        env = os.environ.copy()
        env.update(SINGLE_THREAD_ENV)
        env["PYTHONUNBUFFERED"] = "1"

        # The child runs inside a temporary working directory, so make the
        # worker package importable even when it is not pip-installed (for
        # example when running straight from a checkout).
        package_root = str(Path(__file__).resolve().parent.parent)
        existing_path = env.get("PYTHONPATH")
        env["PYTHONPATH"] = (
            package_root if not existing_path else package_root + os.pathsep + existing_path
        )

        payload = json.dumps(dict(job))
        with tempfile.TemporaryDirectory(prefix="taiga-job-") as workdir:
            try:
                process = subprocess.Popen(
                    self.command,
                    cwd=workdir,
                    env=env,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    start_new_session=(os.name == "posix"),
                    preexec_fn=self._preexec if os.name == "posix" else None,
                )
            except OSError as exc:
                return Outcome(ok=False, error=f"cannot start job subprocess: {exc}")

            self._process = process
            try:
                stdout, stderr = process.communicate(payload, timeout=self.timeout_s)
            except subprocess.TimeoutExpired:
                self._kill(process)
                stdout, stderr = process.communicate()
                return Outcome(
                    ok=False,
                    error=f"job timed out after {self.timeout_s:g}s",
                )
            finally:
                self._process = None

        if process.returncode != 0:
            detail = (stderr or stdout or "").strip()[-2000:]
            return Outcome(
                ok=False,
                error=detail or f"job subprocess exited with code {process.returncode}",
            )

        return self._parse_output(stdout, stderr)

    @staticmethod
    def _parse_output(stdout: str, stderr: str) -> Outcome:
        lines = [line for line in (stdout or "").splitlines() if line.strip()]
        if not lines:
            detail = (stderr or "").strip()[-2000:]
            return Outcome(ok=False, error=detail or "job produced no output")

        try:
            result = json.loads(lines[-1])
        except ValueError:
            detail = (stderr or stdout or "").strip()[-2000:]
            return Outcome(
                ok=False,
                error=f"job output was not valid JSON: {detail}" if detail else "job output was not valid JSON",
            )

        if not isinstance(result, dict):
            return Outcome(ok=False, error="job result must be a JSON object")
        if result.get("status") == "failed":
            return Outcome(ok=False, error=str(result.get("error") or "job reported failure"))

        return Outcome(ok=True, result=result)

    def _kill(self, process: subprocess.Popen[str]) -> None:
        if process.poll() is not None:
            return
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            else:  # pragma: no cover - Windows is not a supported worker target
                process.kill()
        except (ProcessLookupError, PermissionError):
            pass

    def terminate(self) -> None:
        """Ask the active child to stop (called from the signal handler)."""

        process = self._process
        if process is None or process.poll() is not None:
            return
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGTERM)
            else:  # pragma: no cover
                process.terminate()
        except (ProcessLookupError, PermissionError):
            return

        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self._kill(process)


def run_with_limits(job: Mapping[str, Any], memory_mb: int, timeout_s: float) -> Outcome:
    """Convenience wrapper used by tests and future algorithm code."""

    return JobRunner(memory_mb=memory_mb, timeout_s=timeout_s).run(job)


__all__ = ["JobRunner", "Outcome", "SINGLE_THREAD_ENV", "run_with_limits"]
