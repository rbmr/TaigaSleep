from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from taiga_worker.analysis.algorithms import baseline
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.derive import MOVEMENT_THRESHOLD_G, derive
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
    # Only the three sleep epochs contribute, 30 one-second samples with three
    # intervals each; the wake epoch is excluded.
    assert metrics["hrv_rr_count"] == 270
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

    sleep_quality = derived.sleep_quality
    assert sleep_quality["components_version"] == 1
    assert sleep_quality["components"]["sleep_efficiency"]["value"] == 0.75
    assert sleep_quality["components"]["waso"]["value"] == 1.0
    assert sleep_quality["components"]["awakenings_per_hour"]["value"] == 1.0
    assert sleep_quality["components"]["restorative_sleep_share"]["value"] == 1.0
    assert sleep_quality["notes"] == []
    assert sleep_quality["score"] == pytest.approx(92.4, abs=0.1)


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
    assert derived.metrics["awakenings"] == 0
    assert derived.metrics["final_awakening_s"] == 0.0


def test_only_wake_periods_longer_than_two_minutes_are_awakenings(
    tmp_path: Path,
) -> None:
    recording = _recording(tmp_path, duration_s=210)
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["light"] + ["wake"] * 5 + ["light"],
        baseline.INFO,
        confidence=[1.0] * 7,
    )

    assert derived.metrics["waso_s"] == 150.0
    assert derived.metrics["awakenings"] == 1


def test_quality_renormalizes_unavailable_confidence(tmp_path: Path) -> None:
    recording = _recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(recording, grid, ["light"] * 4, baseline.INFO, confidence=None)

    assert derived.quality["score"] == 100.0
    assert not derived.quality["components"]["mean_stage_confidence"]["available"]
    assert any("mean_stage_confidence" in note for note in derived.quality["notes"])


def test_sleep_score_renormalizes_without_split_deep_and_rem(tmp_path: Path) -> None:
    from taiga_worker.analysis.algorithms import sleepecg

    recording = _recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["wake", "rem", "nrem", "unknown"],
        sleepecg.INFO,
        supported_stages=("wake", "rem", "nrem", "unknown"),
    )

    components = derived.sleep_quality["components"]
    assert not components["restorative_sleep_share"]["available"]
    assert derived.sleep_quality["score"] is not None
    assert any("restorative_sleep_share" in note for note in derived.sleep_quality["notes"])


def test_sleep_score_without_sleep_has_no_deceptive_value(tmp_path: Path) -> None:
    recording = _recording(tmp_path)
    grid = recording.epochs(30)
    derived = derive(recording, grid, ["wake"] * 4, baseline.INFO)

    assert derived.metrics["total_sleep_s"] == 0.0
    assert derived.sleep_quality["score"] == 0.0
    assert derived.sleep_quality["components"]["restorative_sleep_share"]["available"] is False
    assert derived.sleep_quality["components"]["sleep_latency"]["available"] is False


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


def test_a_block_of_unknown_epochs_does_not_change_efficiency(tmp_path: Path) -> None:
    without = _recording(tmp_path / "without", duration_s=180)
    with_block = _recording(tmp_path / "with-block", duration_s=240)

    without_derived = derive(
        without,
        without.epochs(30),
        ["wake", "wake", "light", "light", "light", "light"],
        baseline.INFO,
    )
    with_block_derived = derive(
        with_block,
        with_block.epochs(30),
        ["wake", "wake", "light", "light", "unknown", "unknown", "light", "light"],
        baseline.INFO,
    )

    assert without_derived.metrics["total_sleep_s"] == 120.0
    assert with_block_derived.metrics["total_sleep_s"] == 120.0
    assert without_derived.metrics["scored_duration_s"] == 180.0
    assert with_block_derived.metrics["scored_duration_s"] == 180.0
    assert without_derived.metrics["sleep_efficiency"] == pytest.approx(2 / 3, abs=1e-4)
    assert with_block_derived.metrics["sleep_efficiency"] == without_derived.metrics["sleep_efficiency"]
    assert with_block_derived.support["sleep_efficiency"]["status"] == "supported"


def test_efficiency_is_unavailable_when_less_than_half_the_recording_is_scored(
    tmp_path: Path,
) -> None:
    recording = _recording(tmp_path, duration_s=180)
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["light", "unknown", "unknown", "unknown", "unknown", "light"],
        baseline.INFO,
    )

    assert derived.metrics["scored_duration_s"] == 60.0
    assert derived.metrics["sleep_efficiency"] is None
    assert derived.support["sleep_efficiency"]["status"] == "insufficient_data"
    assert derived.support["sleep_efficiency"]["reason"] == "scored_time_below_half_of_recording"


def test_efficiency_stays_available_at_exactly_half_scored_time(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=180)
    grid = recording.epochs(30)
    derived = derive(
        recording,
        grid,
        ["light", "light", "light", "unknown", "unknown", "unknown"],
        baseline.INFO,
    )

    assert derived.metrics["sleep_efficiency"] == 1.0
    assert derived.support["sleep_efficiency"]["status"] == "supported"


def test_leading_unknown_epochs_do_not_change_latency(tmp_path: Path) -> None:
    without = _recording(tmp_path / "without", duration_s=150)
    with_leading = _recording(tmp_path / "with-leading", duration_s=210)

    without_derived = derive(
        without,
        without.epochs(30),
        ["wake", "light", "light", "light", "light"],
        baseline.INFO,
    )
    with_leading_derived = derive(
        with_leading,
        with_leading.epochs(30),
        ["unknown", "unknown", "wake", "light", "light", "light", "light"],
        baseline.INFO,
    )

    assert without_derived.metrics["sleep_latency_s"] == 30.0
    assert with_leading_derived.metrics["sleep_latency_s"] == 30.0


def _rmssd(values: list[int]) -> float:
    diffs = np.diff(np.asarray(values, dtype=np.float64))
    return float(np.sqrt(np.mean(diffs**2)))


def test_rr_data_with_a_gap_combines_the_halves_instead_of_spanning_the_gap(
    tmp_path: Path,
) -> None:
    first = [(1000, 980)] * 35
    second = [(820, 800)] * 35
    recording = _recording(
        tmp_path,
        duration_s=80,
        rr_per_sample=first + [()] * 10 + second,
    )
    grid = recording.epochs(30)
    derived = derive(recording, grid, ["light"] * grid.count, baseline.INFO)

    first_rr = [value for pair in first for value in pair]
    second_rr = [value for pair in second for value in pair]
    expected = (
        len(first_rr) * _rmssd(first_rr) + len(second_rr) * _rmssd(second_rr)
    ) / (len(first_rr) + len(second_rr))

    assert derived.metrics["hrv_rr_count"] == len(first_rr) + len(second_rr)
    assert derived.metrics["hrv_rmssd_ms"] == pytest.approx(expected, abs=0.05)
    # A successive difference taken across the gap would report a larger figure.
    assert _rmssd(first_rr + second_rr) > expected + 1.0


def test_a_constant_accelerometer_offset_does_not_change_the_movement_classification(
    tmp_path: Path,
) -> None:
    plain = _recording(
        tmp_path / "plain",
        movement_windows=[(0, 30)],
        movement_mg=340,
    )
    offset = _recording(
        tmp_path / "offset",
        movement_windows=[(0, 30)],
        movement_mg=340,
        acc_offset_mg=30,
    )
    stages = ["light"] * 4

    plain_grid = plain.epochs(30)
    offset_grid = offset.epochs(30)
    plain_moving = plain_grid.movement_g > MOVEMENT_THRESHOLD_G
    offset_moving = offset_grid.movement_g > MOVEMENT_THRESHOLD_G

    # The moved epoch is gentle enough that measuring it from a fixed 1 g would
    # call it movement once the offset is added; from the night's median it is not.
    assert not np.any(plain_moving)
    assert list(offset_moving) == list(plain_moving)
    assert abs(float(np.median(offset.acc.magnitude_g[: 30 * 50])) - 1.0) > MOVEMENT_THRESHOLD_G

    plain_derived = derive(plain, plain_grid, stages, baseline.INFO)
    offset_derived = derive(offset, offset_grid, stages, baseline.INFO)
    assert offset_derived.metrics["movement_epochs"] == plain_derived.metrics["movement_epochs"]
    assert offset_derived.metrics["movement_index"] == plain_derived.metrics["movement_index"]
    assert offset_grid.movement_g[1] < 0.001
