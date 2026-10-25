#!/usr/bin/env python3
"""Benchmark one stager on a local recording without the API.

Run it with the worker environment:

    cd backend
    uv run --project worker --frozen python scripts/benchmark_analysis.py \
        /path/to/recording --model baseline

The script only reads the recording directory and prints a summary. Use
``--out`` to write the full analysis result somewhere local; never commit a
recording or a result containing health data.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

WORKER_DIR = Path(__file__).resolve().parents[1] / "worker"
sys.path.insert(0, str(WORKER_DIR))

from taiga_worker.runner import JobRunner  # noqa: E402


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path, help="Recording directory (meta.json + frame logs)")
    parser.add_argument("--model", default="baseline", help="Stager id (default: baseline)")
    parser.add_argument("--version", help="Exact algorithm version; default is latest")
    parser.add_argument("--params", default="{}", help="JSON object of algorithm params")
    parser.add_argument("--memory-mb", type=int, default=1024, help="Subprocess address-space limit")
    parser.add_argument("--timeout", type=float, default=3600.0, help="Subprocess timeout in seconds")
    parser.add_argument("--out", type=Path, help="Write the full analysis JSON to this path")
    parser.add_argument("--json", action="store_true", help="Print the full analysis JSON")
    return parser


def _stage_seconds(metrics: dict) -> dict:
    names = ("wake", "rem", "nrem", "light", "deep", "sleep")
    result = {name: metrics.get(f"time_in_{name}_s") for name in names}
    result["unscored"] = metrics.get("unscored_s")
    return result


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    recording = args.recording.expanduser().resolve()
    if not (recording / "meta.json").is_file():
        print(f"not a recording directory: {recording}", file=sys.stderr)
        return 2

    try:
        params = json.loads(args.params)
    except ValueError as exc:
        print(f"--params is not valid JSON: {exc}", file=sys.stderr)
        return 2
    if not isinstance(params, dict):
        print("--params must be a JSON object", file=sys.stderr)
        return 2

    job = {
        "id": "benchmark",
        "recording_dir": str(recording),
        "model": args.model,
        "model_version": args.version or "latest",
        "params": params,
        "params_hash": "benchmark",
        "worker": "benchmark",
        "algorithms": [args.model],
    }
    runner = JobRunner(memory_mb=args.memory_mb, timeout_s=args.timeout)
    started = time.monotonic()
    outcome = runner.run(job)
    wall_time = time.monotonic() - started

    if not outcome.ok:
        print(f"analysis failed: {outcome.error}", file=sys.stderr)
        return 1

    analysis = (outcome.result or {}).get("analysis")
    if not isinstance(analysis, dict):
        print("analysis produced no result", file=sys.stderr)
        return 1

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(analysis, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {args.out}")

    if args.json:
        print(json.dumps(analysis, indent=2))
        return 0

    provenance = analysis.get("provenance") or {}
    metrics = analysis.get("metrics") or {}
    input_bytes = sum(
        path.stat().st_size for path in recording.iterdir() if path.is_file()
    )
    summary = {
        "model": analysis.get("model"),
        "model_version": analysis.get("model_version"),
        "params": analysis.get("params"),
        "recording": provenance.get("recording"),
        "input_bytes": input_bytes,
        "analysis_runtime_s": provenance.get("runtime_s"),
        "wall_time_s": round(wall_time, 3),
        "peak_rss_mb": provenance.get("peak_rss_mb"),
        "stage_seconds": _stage_seconds(metrics),
        "quality": analysis.get("quality"),
        "warnings": analysis.get("warnings"),
        "metric_support": analysis.get("metric_support"),
    }
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
