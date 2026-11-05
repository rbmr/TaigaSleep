from __future__ import annotations

import gzip
from pathlib import Path
from typing import Any, Mapping

import pytest

from taiga_worker.api import ApiError
from taiga_worker.runner import Outcome
from taiga_worker.upload import verify_recording_file
from taiga_worker.worker import Worker
from tests import analysis_fixture as fx
from tests.test_worker import FakeApi, FakeRunner, analysis, config


def test_verify_keeps_only_the_valid_prefix(tmp_path: Path) -> None:
    path = tmp_path / "hr.log"
    good = fx.hr_log(duration_s=3)
    path.write_bytes(good + b"\x10\x00\x00\x00broken")

    check = verify_recording_file(path, "hr.log")

    assert check.frames == 3
    assert check.valid_bytes == len(good)
    assert check.size == len(good) + 10
    assert check.truncated
    assert "truncated frame" in (check.error or "")
    assert path.read_bytes() == good


def test_verify_stops_at_the_first_bad_crc(tmp_path: Path) -> None:
    path = tmp_path / "hr.log"
    good = fx.hr_log(duration_s=2)
    broken = fx.frame(fx.hr_payload(fx.START_NS, 0, 60), bad_crc=True)
    after = fx.frame(fx.hr_payload(fx.START_NS + 1_000_000_000, 1_000_000_000, 61))
    path.write_bytes(good + broken + after)

    check = verify_recording_file(path, "hr.log")

    assert check.frames == 2
    assert check.truncated
    assert "CRC" in (check.error or "")
    assert path.read_bytes() == good


def test_verify_rejects_a_log_with_the_wrong_header(tmp_path: Path) -> None:
    path = tmp_path / "ecg.log"
    path.write_bytes(fx.hr_log(duration_s=2))  # an HR log under the ECG name

    check = verify_recording_file(path, "ecg.log")

    assert check.frames == 0
    assert check.valid_bytes == 0
    assert check.truncated
    assert path.read_bytes() == b""


def test_verify_events_truncates_a_partial_line(tmp_path: Path) -> None:
    path = tmp_path / "events.jsonl"
    line = fx.event_line(fx.START_NS, 0, "start", "started")
    path.write_bytes(line + b'{"phone_ns":1,"type":"st')

    check = verify_recording_file(path, "events.jsonl")

    assert check.lines == 1
    assert check.valid_bytes == len(line)
    assert check.truncated
    assert path.read_bytes() == line


def test_verify_leaves_a_valid_file_alone(tmp_path: Path) -> None:
    path = tmp_path / "hr.log"
    good = fx.hr_log(duration_s=5)
    path.write_bytes(good)

    check = verify_recording_file(path, "hr.log")

    assert (check.frames, check.valid_bytes, check.truncated, check.error) == (
        5,
        len(good),
        False,
        None,
    )
    assert path.read_bytes() == good


class InflatingApi(FakeApi):
    """Reports stored sizes like the API does: compressed files are smaller than
    the bytes that a download delivers, because the API inflates them."""

    def files(self, recording_id: str) -> list[dict[str, Any]]:
        entries = super().files(recording_id)
        for entry in entries:
            if entry["encoding"] == "gzip":
                entry["size"] = len(gzip.compress(self.files_by_name[entry["name"]], mtime=0))
        return entries


class InspectRunner(FakeRunner):
    def __init__(self) -> None:
        super().__init__(Outcome(ok=True, result={"analysis": {"ok": True}}))

    @property
    def seen(self) -> dict[str, bytes]:
        return self.recordings[0]

    @property
    def source(self) -> Mapping[str, Any]:
        return self.jobs[0]["source"]


def recording_bytes(tmp_path: Path, **kwargs: Any) -> dict[str, bytes]:
    directory = fx.write_recording(tmp_path / "recording", **kwargs)
    return {
        name: (directory / name).read_bytes()
        for name in ("hr.log", "ecg.log", "acc.log", "events.jsonl")
        if (directory / name).exists()
    }


def run_worker(api: FakeApi, runner: FakeRunner | None = None) -> FakeRunner:
    runner = runner or InspectRunner()
    worker = Worker(config(), api, runner, sleep=lambda _: None)
    assert worker.run_once()
    return runner


@pytest.mark.parametrize("encoding", ["identity", "gzip"])
def test_worker_hands_the_downloaded_files_to_the_analysis(tmp_path: Path, encoding: str) -> None:
    bodies = recording_bytes(tmp_path)
    api = InflatingApi([analysis()], files=bodies, encodings={name: encoding for name in bodies})

    runner = run_worker(api)

    assert isinstance(runner, InspectRunner)
    assert sorted(runner.seen) == ["acc.log", "ecg.log", "events.jsonl", "hr.log", "meta.json"]
    for name, body in bodies.items():
        assert runner.seen[name] == body
    source = runner.source
    assert source["encoding"] == encoding
    assert source["files"]["hr.log"]["encoding"] == encoding
    assert source["files"]["hr.log"]["frames"] == 120
    assert source["uncompressed_bytes"] == sum(len(body) for body in bodies.values())
    assert source["warnings"] == []
    if encoding == "gzip":
        assert source["stored_bytes"] < source["uncompressed_bytes"]
        assert source["compression_ratio"] > 1
    else:
        assert source["stored_bytes"] == source["uncompressed_bytes"]
        assert source["compression_ratio"] == 1.0
    assert len(api.completed) == 1


def test_worker_reports_mixed_encodings(tmp_path: Path) -> None:
    bodies = recording_bytes(tmp_path)
    api = InflatingApi([analysis()], files=bodies, encodings={"ecg.log": "gzip"})

    runner = run_worker(api)

    assert runner.source["encoding"] == "mixed"
    assert runner.source["files"]["ecg.log"]["encoding"] == "gzip"
    assert runner.source["files"]["hr.log"]["encoding"] == "identity"


@pytest.mark.parametrize("encoding", ["identity", "gzip"])
def test_a_corrupt_tail_is_cut_off_whatever_the_encoding(tmp_path: Path, encoding: str) -> None:
    bodies = recording_bytes(tmp_path)
    good_hr = bodies["hr.log"]
    bodies["hr.log"] = good_hr + b"\x10\x00\x00\x00broken"
    bodies["events.jsonl"] += b'{"phone_ns":1,"type":"st'
    api = InflatingApi([analysis()], files=bodies, encodings={name: encoding for name in bodies})

    runner = run_worker(api)

    assert runner.seen["hr.log"] == good_hr
    assert runner.seen["events.jsonl"].endswith(b"\n")
    hr = runner.source["files"]["hr.log"]
    assert hr["frames"] == 120
    assert hr["valid_bytes"] == len(good_hr)
    assert hr["truncated_tail"]
    assert runner.source["files"]["events.jsonl"]["truncated_tail"]
    assert any("hr.log: truncated frame" in item for item in runner.source["warnings"])
    assert any("events.jsonl" in item for item in runner.source["warnings"])
    assert not runner.source["files"]["ecg.log"]["truncated_tail"]
    assert len(api.completed) == 1


def test_a_bad_crc_in_a_plain_file_ends_the_stream_there(tmp_path: Path) -> None:
    bodies = recording_bytes(tmp_path, duration_s=60)
    good = fx.hr_log(duration_s=10)
    bad = fx.frame(fx.hr_payload(fx.START_NS, 0, 60), bad_crc=True)
    later = fx.frame(fx.hr_payload(fx.START_NS + 11_000_000_000, 11_000_000_000, 60))
    bodies["hr.log"] = good + bad + later
    api = FakeApi([analysis()], files=bodies)

    runner = run_worker(api)

    assert runner.seen["hr.log"] == good
    assert runner.source["files"]["hr.log"]["frames"] == 10
    assert any("CRC" in item for item in runner.source["warnings"])


@pytest.mark.parametrize("encoding", ["identity", "gzip"])
def test_a_recording_without_a_valid_frame_fails_for_good(encoding: str) -> None:
    bodies = {
        "hr.log": fx.frame_header(1),
        "events.jsonl": fx.event_line(fx.START_NS, 0, "start", "started"),
    }
    api = InflatingApi([analysis()], files=bodies, encodings={name: encoding for name in bodies})
    runner = FakeRunner()

    run_worker(api, runner)

    assert runner.jobs == []
    assert api.failed[0]["retryable"] is False
    assert "no valid frames" in api.failed[0]["error"]


def test_a_stream_that_is_not_a_frame_log_counts_for_nothing() -> None:
    api = FakeApi([analysis()], files={"hr.log": b"this is not a frame log at all"})
    runner = FakeRunner()

    run_worker(api, runner)

    assert runner.jobs == []
    assert "no valid frames" in api.failed[0]["error"]


def test_a_recording_without_files_fails_for_good() -> None:
    api = FakeApi([analysis()], files={})
    runner = FakeRunner()

    run_worker(api, runner)

    assert runner.jobs == []
    assert api.failed[0]["retryable"] is False
    assert "no files" in api.failed[0]["error"]


def test_only_the_data_files_are_downloaded(tmp_path: Path) -> None:
    bodies = recording_bytes(tmp_path)
    bodies["meta.json"] = b"{}"
    bodies["notes.txt"] = b"hello"
    api = FakeApi([analysis()], files=bodies)

    runner = run_worker(api)

    assert sorted(api.downloads) == ["acc.log", "ecg.log", "events.jsonl", "hr.log"]
    # meta.json is the recording's meta, never a downloaded file.
    assert runner.seen["meta.json"] != b"{}"
    assert "notes.txt" not in runner.seen


def test_a_failed_download_is_retryable() -> None:
    class BrokenDownload(FakeApi):
        def download_file(self, recording_id: str, name: str, destination: Path) -> None:
            raise ApiError("connection reset", status=None)

    api = BrokenDownload([analysis()], files={"hr.log": fx.hr_log(duration_s=5)})
    runner = FakeRunner()

    run_worker(api, runner)

    assert runner.jobs == []
    assert api.failed[0]["retryable"] is True
    assert "cannot download hr.log" in api.failed[0]["error"]
