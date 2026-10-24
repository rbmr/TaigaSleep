"""Placeholder analysis job.

Issue #10 replaces the body of `analyze()` with the real sleep staging
pipeline. Everything around it already has the shape the real job needs: the
worker sends one job JSON document on stdin, the subprocess runs with a memory
limit and single-threaded numeric libraries, and the last stdout line is a JSON
result object.
"""

from __future__ import annotations

import json
import sys
from typing import Any, Mapping


def analyze(job: Mapping[str, Any]) -> dict[str, Any]:
    """Run the (not yet implemented) analysis for one job."""

    return {
        "ok": True,
        "noop": True,
        "reason": "analysis pipeline not implemented yet",
        "job_id": job.get("id"),
        "kind": job.get("kind"),
        "model": job.get("model"),
        "session": job.get("session"),
    }


def main() -> int:
    try:
        job = json.load(sys.stdin)
        if not isinstance(job, dict):
            raise ValueError("job payload must be a JSON object")
        if not job.get("id"):
            raise ValueError("job payload is missing id")

        print(json.dumps(analyze(job)))
        return 0
    except Exception as exc:  # noqa: BLE001 - the exit code is the signal
        print(f"taiga job failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
