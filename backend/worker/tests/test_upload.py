from __future__ import annotations

import gzip
import json
from pathlib import Path
from typing import Any, Mapping

import pytest

from taiga_worker.runner import Outcome
from taiga_worker.upload import (
    UploadError,
    decompress_gzip,
    verify_recording_file,
)
from taiga_worker.worker import Worker
from tests import analysis_fixture as fx
from tests.test_worker import FakeApi, FakeRunner, config


def member(data: bytes) -> bytes:
    return gzip.compress(data, mtime=0)


def compress_chunks(data: bytes, size: int) -> bytes:
    return b"".join(member(data[i : i + size]) for i in range(0, len(data), size))


def test_concatenated_members_are_decompressed(tmp_path: Path) -> None:
    source = tmp_path / "hr.log.gz"
    original = fx.hr_log(duration_s=6)
    source.write_bytes(compress_chunks(original, 100))
    destination = tmp_path / "hr.log"

    stats = decompress_gzip(
        source,
        destination,
        max_bytes=1_000_000,
        max_members=100,
        max_ratio=200,
    )

    assert destination.read_bytes() == original
    assert stats.members == (len(original) + 99) // 100
    assert stats.stored_bytes == source.stat().st_size
    assert stats.uncompressed_bytes == len(original)


def test_truncated_member_is_rejected(tmp_path: Path) -> None:
    source = tmp_path / "hr.log.gz"
    source.write_bytes(member(fx.hr_log(duration_s=3))[:-4])

    with pytest.raises(UploadError):
        decompress_gzip(
            source,
            tmp_path / "out",
            max_bytes=1_000_000,
            max_members=10,
            max_ratio=200,
        )


def test_expansion_ratio_and_size_limits_are_enforced(tmp_path: Path) -> None:
    source = tmp_path / "hr.log.gz"
    source.write_bytes(member(b"\x00" * 100_000))

    with pytest.raises(UploadError, match="ratio"):
        decompress_gzip(
            source,
            tmp_path / "out",
            max_bytes=1_000_000,
            max_members=10,
            max_ratio=2,
        )
    with pytest.raises(UploadError, match="limit"):
        decompress_gzip(
            source,
            tmp_path / "out",
            max_bytes=1000,
            max_members=10,
            max_ratio=10_000,
        )


def test_member_count_limit_is_enforced(tmp_path: Path) -> None:
    source = tmp_path / "hr.log.gz"
    source.write_bytes(member(b"a") + member(b"b"))

    with pytest.raises(UploadError, match="members"):
        decompress_gzip(
            source,
            tmp_path / "out",
            max_bytes=1000,
            max_members=1,
            max_ratio=1000,
        )


def test_verify_keeps_only_the_valid_prefix(tmp_path: Path) -> None:
    path = tmp_path / "hr.log"
    good = fx.hr_log(duration_s=3)
    path.write_bytes(good + b"\x10\x00\x00\x00broken")

    check = verify_recording_file(path, "hr.log")

    assert check.frames == 3
    assert check.valid_bytes == len(good)
    assert check.truncated
    assert "truncated frame" in (check.error or "")
    assert path.read_bytes() == good


def test_verify_events_truncates_a_partial_line(tmp_path: Path) -> None:
    path = tmp_path / "events.jsonl"
    line = fx.event_line(fx.START_NS, 0, "start", "started")
    path.write_bytes(line + b'{"phone_ns":1,"type":"st')

    check = verify_recording_file(path, "events.jsonl")

    assert check.lines == 1
    assert check.valid_bytes == len(line)
    assert check.truncated
    assert path.read_bytes() == line


class GzipApi(FakeApi):
    """A manifest and downloads for one session with compressed streams."""

    def __init__(self, files: Mapping[str, bytes | None], *, encoding: str = "gzip") -> None:
        super().__init__(jobs=[job()])
        self.bodies = dict(files)
        self.raw_downloads: dict[str, bytes] = {}
        self.encoding = encoding
        self.limits = {
            "max_uncompressed_file_bytes": 10_000_000,
            "max_uncompressed_total_bytes": 20_000_000,
            "max_members": 10000,
            "max_expansion_ratio": 200,
            "max_chunk_bytes": 8 * 1024 * 1024,
        }

    def manifest(self, session_id: str, job_id: str, worker: str) -> dict[str, Any]:
        entries: dict[str, Any] = {}
        for name, body in self.bodies.items():
            compressed = name != "meta.json" and self.encoding == "gzip"
            entries[name] = {
                "length": len(body or b""),
                "encoding": "gzip" if compressed else "identity",
                "stored_name": name + ".gz" if compressed else name,
                "state": "ok",
            }
        return {
            "upload": {
                "encoding": self.encoding,
                "files": entries,
                "limits": self.limits,
            }
        }

    def download_file(self, session_id: str, name: str, destination, job_id: str, worker: str) -> None:
        self.downloads.append(name)
        if name in self.raw_downloads:
            destination.write_bytes(self.raw_downloads[name])
            return
        body = self.bodies[name.removesuffix(".gz")]
        if name.endswith(".gz") and name != "meta.json":
            destination.write_bytes(compress_chunks(body, 4096))
        else:
            destination.write_bytes(body)


def job() -> dict[str, Any]:
    return {
        "id": "job1",
        "kind": "analysis",
        "model": "baseline",
        "session": "s1",
        "analysis": "a1",
    }


class InspectRunner:
    def __init__(self) -> None:
        self.jobs: list[Mapping[str, Any]] = []
        self.seen: dict[str, Any] = {}

    def run(self, run_job: Mapping[str, Any]) -> Outcome:
        self.jobs.append(run_job)
        root = Path(str(run_job["recording_dir"]))
        self.seen["files"] = sorted(p.name for p in root.iterdir() if p.is_file())
        self.seen["source"] = run_job["source"]
        self.seen["hr"] = (root / "hr.log").read_bytes()
        return Outcome(ok=True, result={"ok": True})


def test_worker_extracts_a_compressed_recording(tmp_path: Path) -> None:
    directory = fx.write_recording(tmp_path / "recording")
    bodies = {
        name: (directory / name).read_bytes()
        for name in ("meta.json", "hr.log", "ecg.log", "acc.log", "events.jsonl")
    }
    api = GzipApi(bodies)
    runner = InspectRunner()
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()

    seen = runner.seen
    assert seen["files"] == ["acc.log", "ecg.log", "events.jsonl", "hr.log", "meta.json"]
    assert seen["hr"] == bodies["hr.log"]
    source = seen["source"]
    assert source["encoding"] == "gzip"
    assert source["files"]["hr.log"]["encoding"] == "gzip"
    assert source["files"]["hr.log"]["members"] > 0
    assert source["files"]["hr.log"]["frames"] == 120
    assert source["uncompressed_bytes"] == sum(len(body) for body in bodies.values())
    assert source["stored_bytes"] < source["uncompressed_bytes"]
    assert source["compression_ratio"] > 1


def test_worker_rejects_a_compressed_recording_without_frames() -> None:
    bodies = {
        "meta.json": json.dumps(fx.meta("s1")).encode(),
        "hr.log": fx.frame_header(1),
        "events.jsonl": fx.event_line(fx.START_NS, 0, "start", "started"),
    }
    api = GzipApi(bodies)
    runner = FakeRunner()
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()
    assert runner.jobs == []
    assert api.finished[0]["status"] == "failed"
    assert "no valid frames" in api.finished[0]["error"]


def test_worker_reports_a_decompression_error() -> None:
    bodies = {
        "meta.json": json.dumps(fx.meta("s1")).encode(),
        "hr.log": fx.hr_log(duration_s=3),
    }
    api = GzipApi(bodies)
    api.raw_downloads["hr.log.gz"] = b"not a gzip member"
    runner = FakeRunner()
    worker = Worker(config(), api, runner, sleep=lambda _: None)

    assert worker.run_once()
    assert runner.jobs == []
    assert api.finished[0]["status"] == "failed"
    assert "gzip" in api.finished[0]["error"]
