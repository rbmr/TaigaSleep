from __future__ import annotations

from pathlib import Path

import pytest

from taiga_worker.analysis.algorithms import baseline
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.derive import derive
from tests import analysis_fixture as fx


def _recording(tmp_path: Path, **kwargs):
    kwargs.setdefault("duration_s", 120)
    fx.write_recording(tmp_path, **kwargs)
    return load_recording(tmp_path)


def test_metrics_from_a_known_stage_timeline(tmp_path: Path) -> None:
    recording = _recording(
        tmp_path,
        movement_windows=[(0, 30)],
    )
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["wake", "light", "deep", "rem"],
        baseline.INFO,
        confidence=[0.8, 0.8, 0.8, 0.8],
        artifacts=[False, False, False, False],
    )

    metrics = derived.metrics
    assert metrics["total_duration_s"] == 120.0
    assert metrics["total_sleep_s"] == 90.0
    assert metrics["sleep_efficiency"] == 0.75
    assert metrics["sleep_latency_s"] == 30.0
    assert metrics["waso_s"] == 0.0
    assert metrics["awakenings"] == 0
    assert metrics["final_awakening_s"] == 0.0
    assert metrics["time_in_wake_s"] == 30.0
    assert metrics["time_in_light_s"] == 30.0
    assert metrics["time_in_deep_s"] == 30.0
    assert metrics["time_in_rem_s"] == 30.0
    assert metrics["time_in_nrem_s"] is None
    assert metrics["hr_mean_bpm"] == 60.0
    assert metrics["hr_min_bpm"] == 60.0
    assert metrics["hr_max_bpm"] == 60.0
    assert metrics["hrv_rr_count"] == 360
    assert metrics["hrv_rmssd_ms"] > 0.0
    assert metrics["hrv_sdnn_ms"] > 0.0
    assert metrics["movement_index"] == 0.25
    assert metrics["movement_mean_g"] > 0.02
    assert metrics["movement_epochs"] == 1

    assert derived.support["time_in_nrem_s"]["status"] == "unsupported_algorithm"
    assert derived.support["hr_mean_bpm"]["status"] == "supported"

    quality = derived.quality
    assert quality["score"] == 95.0
    assert quality["components"]["mean_stage_confidence"]["value"] == 0.8
    assert quality["notes"] == []


def test_wake_after_sleep_onset_and_awakenings(tmp_path: Path) -> None:
    recording = _recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["light", "wake", "light", "deep"],
        baseline.INFO,
        confidence=[1.0, 1.0, 1.0, 1.0],
    )

    assert derived.metrics["sleep_latency_s"] == 0.0
    assert derived.metrics["waso_s"] == 30.0
    assert derived.metrics["awakenings"] == 1
    assert derived.metrics["final_awakening_s"] == 0.0


def test_quality_renormalizes_unavailable_confidence(tmp_path: Path) -> None:
    recording = _recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(recording, grid, ["light"] * 4, baseline.INFO, confidence=None)

    assert derived.quality["score"] == 100.0
    assert not derived.quality["components"]["mean_stage_confidence"]["available"]
    assert any("mean_stage_confidence" in note for note in derived.quality["notes"])


def test_missing_acc_is_an_unsupported_metric_not_a_failure(tmp_path: Path) -> None:
    recording = _recording(tmp_path, include_acc=False)
    grid = recording.epochs(30)
    derived = derive(recording, grid, ["light"] * 4, baseline.INFO, confidence=[1.0] * 4)

    assert derived.metrics["movement_index"] is None
    assert derived.support["movement_index"]["status"] == "unsupported_device"
    assert derived.quality["score"] is not None


def test_missing_hr_is_unsupported_and_quality_is_renormalized(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    (tmp_path / "hr.log").unlink()
    recording = load_recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(recording, grid, ["light"] * 4, baseline.INFO, confidence=[1.0] * 4)

    assert derived.metrics["hr_mean_bpm"] is None
    assert derived.support["hr_mean_bpm"]["status"] == "unsupported_device"
    assert not derived.quality["components"]["hr_coverage"]["available"]
    assert derived.quality["score"] is not None


def test_narrowed_supported_stages_remove_unused_metrics(tmp_path: Path) -> None:
    from taiga_worker.analysis.algorithms import sleepecg

    recording = _recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["wake", "rem", "nrem", "unknown"],
        sleepecg.INFO,
        confidence=[0.8, 0.8, 0.8, None],
        artifacts=[False, False, False, True],
        supported_stages=("wake", "rem", "nrem", "unknown"),
    )

    assert derived.metrics["time_in_nrem_s"] == 30.0
    assert derived.metrics["time_in_sleep_s"] is None
    assert derived.metrics["time_in_deep_s"] is None
    assert derived.support["time_in_nrem_s"]["status"] == "supported"
    assert derived.support["time_in_sleep_s"]["status"] == "unsupported_algorithm"
    assert derived.support["time_in_deep_s"]["status"] == "unsupported_algorithm"


def test_two_class_supported_stages_mark_nrem_unsupported(tmp_path: Path) -> None:
    from taiga_worker.analysis.algorithms import sleepecg

    recording = _recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["wake", "sleep", "wake", "sleep"],
        sleepecg.INFO,
        supported_stages=("wake", "sleep", "unknown"),
    )

    assert derived.metrics["time_in_sleep_s"] == 60.0
    assert derived.metrics["time_in_nrem_s"] is None
    assert derived.support["time_in_sleep_s"]["status"] == "supported"
    assert derived.support["time_in_nrem_s"]["status"] == "unsupported_algorithm"
