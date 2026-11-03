#!/usr/bin/env python3
"""Measure gzip upload compression on one local recording directory.

This is the benchmark behind docs/upload-compression.md. It only reads a local
recording and writes temporary copies; nothing is uploaded and no recording is
committed. Run it on your own machine and keep the numbers outside the repo.

    cd backend
    uv run --project worker --frozen python scripts/benchmark_compression.py /path/to/recording

The real-phone side (CPU and upload time) is measured on the device: see the
document for the procedure.
"""

from __future__ import annotations

import argparse
import gzip
import resource
import sys
import tempfile
import time
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR / "worker"))

from taiga_worker.upload import (  # noqa: E402
    RECORDING_FILES,
    decompress_gzip,
    verify_recording_file,
)

STREAMS = ("hr.log", "ecg.log", "acc.log", "events.jsonl")


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def compress(data: bytes, chunk_bytes: int, level: int) -> tuple[bytes, int, float]:
    started = time.perf_counter()
    members = [
        gzip.compress(data[index : index + chunk_bytes], compresslevel=level, mtime=0)
        for index in range(0, len(data), chunk_bytes)
    ]
    elapsed = time.perf_counter() - started
    return b"".join(members), len(members), elapsed


def measure(
    recording: Path, levels: list[int], chunk_bytes: int
) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with tempfile.TemporaryDirectory(prefix="taiga-compression-") as workdir:
        for level in levels:
            for name in STREAMS:
                source = recording / name
                if not source.is_file() or source.stat().st_size == 0:
                    continue
                data = source.read_bytes()
                compressed, members, compress_s = compress(data, chunk_bytes, level)

                stored = Path(workdir) / f"{name}.{level}.gz"
                stored.write_bytes(compressed)
                output = Path(workdir) / f"{name}.{level}"
                started = time.perf_counter()
                stats = decompress_gzip(
                    stored,
                    output,
                    max_bytes=len(data) + 1,
                    max_members=1_000_000,
                    max_ratio=10_000,
                )
                decompress_s = time.perf_counter() - started
                verify_s = 0.0
                check = None
                if name != "meta.json":
                    started = time.perf_counter()
                    check = verify_recording_file(output, name)
                    verify_s = time.perf_counter() - started
                    if output.read_bytes() != data:
                        raise SystemExit(f"{name}: decompressed data differs")
                rows.append(
                    {
                        "level": level,
                        "file": name,
                        "raw": len(data),
                        "stored": len(compressed),
                        "ratio": len(data) / len(compressed),
                        "members": members,
                        "compress_ms": compress_s * 1000,
                        "decompress_ms": decompress_s * 1000,
                        "verify_ms": verify_s * 1000,
                        "frames": check.frames if check else 0,
                        "peak_rss_mb": peak_rss_mb(),
                        "stats_members": stats.members,
                    }
                )
                stored.unlink()
                output.unlink()
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path, help="a local recording directory")
    parser.add_argument("--chunk-mb", type=float, default=2.0, help="uncompressed chunk size")
    parser.add_argument(
        "--levels",
        default="1,6,9",
        help="comma-separated gzip levels; the app uses zlib level 6",
    )
    parser.add_argument("--json", action="store_true", help="print JSON instead of a table")
    args = parser.parse_args()

    recording = args.recording
    if not recording.is_dir():
        parser.error(f"not a directory: {recording}")
    missing = [name for name in RECORDING_FILES if not (recording / name).exists()]
    if "meta.json" in missing:
        parser.error(f"not a recording: {recording} has no meta.json")

    levels = [int(value) for value in args.levels.split(",") if value.strip()]
    rows = measure(recording, levels, int(args.chunk_mb * 1024 * 1024))

    if args.json:
        import json

        print(json.dumps(rows, indent=2))
        return 0

    for level in levels:
        selected = [row for row in rows if row["level"] == level]
        if not selected:
            continue
        raw = sum(int(row["raw"]) for row in selected)
        stored = sum(int(row["stored"]) for row in selected)
        print(f"\nlevel {level}: {raw} bytes raw -> {stored} bytes stored ({raw / stored:.2f}x)")
        print(f"{'file':<14}{'raw':>12}{'stored':>12}{'ratio':>8}{'members':>9}"
              f"{'compress ms':>13}{'decompress ms':>15}{'verify ms':>11}")
        for row in selected:
            print(
                f"{row['file']:<14}{int(row['raw']):>12}{int(row['stored']):>12}"
                f"{float(row['ratio']):>8.2f}{int(row['members']):>9}"
                f"{float(row['compress_ms']):>13.1f}{float(row['decompress_ms']):>15.1f}"
                f"{float(row['verify_ms']):>11.1f}"
            )
        peak = max(float(row["peak_rss_mb"]) for row in selected)
        print(f"peak RSS (including the Python interpreter): {peak:.1f} MiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
