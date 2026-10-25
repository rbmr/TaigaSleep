"""Analysis subprocess entry point.

The supervisor downloads the recording and sends one job JSON document on
stdin. The subprocess loads the normalized recording, runs the selected stager,
derives metrics/quality and prints one JSON result line. It never talks to the
API or the database.
"""

from __future__ import annotations

import json
import sys
from typing import Any, Mapping

from .analysis import AnalysisError, run_analysis

RETRYABLE = {"algorithm_failed"}


def analyze(job: Mapping[str, Any]) -> dict[str, Any]:
    recording_dir = job.get("recording_dir")
    if not recording_dir:
        raise AnalysisError("missing_recording", "job payload is missing recording_dir")
    output = run_analysis(
        str(recording_dir),
        str(job.get("model") or ""),
        job.get("params") or {},
        version=job.get("model_version"),
        allowlist=job.get("algorithms"),
        worker=job.get("worker"),
        params_hash_override=job.get("params_hash"),
    )
    return output.to_dict()


def _failure(error: str, retryable: bool) -> dict[str, Any]:
    return {"status": "failed", "error": error[:4000], "retryable": retryable}


def main() -> int:
    try:
        job = json.load(sys.stdin)
        if not isinstance(job, dict):
            raise ValueError("job payload must be a JSON object")
        if not job.get("id"):
            raise ValueError("job payload is missing id")
        result = analyze(job)
        print(json.dumps({"analysis": result}))
        return 0
    except AnalysisError as exc:
        print(
            json.dumps(
                _failure(f"{exc.code}: {exc}", exc.code in RETRYABLE)
            )
        )
        return 0
    except Exception as exc:  # noqa: BLE001 - report a clean job failure
        print(json.dumps(_failure(f"{type(exc).__name__}: {exc}", True)))
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
