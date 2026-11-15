from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest
from taiga_worker.analysis.contract import (
    AASM4_STAGES,
    WAKE_SLEEP_STAGES,
    AlgorithmInfo,
    HrStream,
    RegisteredAlgorithm,
    Stager,
    StagingResult,
    StreamNeed,
)
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.derive import derive
from taiga_worker.analysis.hrv import ARTIFACTS, MOVEMENT, TOO_LITTLE_DATA, hrv_metrics
from taiga_worker.analysis.pipeline import run_analysis
from taiga_worker.analysis.registry import Registry

from tests import analysis_fixture as fx

DURATION_S = 600
WINDOW_S = 300


def _resting_rr(
    *,
    seed: int = 7,
    count: int = 590,
    mean_ms: float = 1000.0,
    spread_ms: float = 30.0,
) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return np.round(mean_ms + rng.normal(0.0, spread_ms, count)).astype(int)


def _recording(tmp_path: Path, rr_ms: np.ndarray, **kwargs):
    fx.write_recording(
        tmp_path,
        duration_s=DURATION_S,
        rr_series_ms=[int(value) for value in rr_ms],
        include_ecg=False,
        **kwargs,
    )
    return load_recording(tmp_path)


def _packets(rr_ms: np.ndarray) -> tuple[dict[int, float], dict[int, list[int]]]:
    """The frames of a whole R-R series: one per second, timed at its last beat."""

    ends_s = np.cumsum(rr_ms) / 1000.0
    times: dict[int, float] = {}
    batches: dict[int, list[int]] = {}
    for interval, end_s in zip(rr_ms, ends_s, strict=True):
        second = int(end_s)
        if second >= DURATION_S:
            break
        times[second] = float(end_s)
        batches.setdefault(second, []).append(int(interval))
    return times, batches


def _recording_from_packets(tmp_path: Path, times: dict[int, float], batches: dict[int, list[int]]):
    """A recording whose HR frames carry exactly these packets, so a hole can be modelled."""

    fx.write_recording(
        tmp_path,
        duration_s=DURATION_S,
        rr_per_sample=[()] * DURATION_S,
        include_ecg=False,
        include_acc=False,
    )
    recording = load_recording(tmp_path)
    seconds = sorted(times)
    frame_times = np.asarray([times[second] for second in seconds], dtype=np.float64)
    hr = HrStream(
        t_s=frame_times,
        hr_bpm=np.full(frame_times.size, 60.0),
        contact_supported=np.ones(frame_times.size, dtype=bool),
        contact=np.ones(frame_times.size, dtype=bool),
        valid=np.ones(frame_times.size, dtype=bool),
        rr_ms=tuple(np.asarray(batches[second], dtype=np.float64) for second in seconds),
        phone_ns=(recording.start_unix_ns + frame_times * 1_000_000_000).astype(np.int64),
    )
    return replace(recording, hr=hr)


def _window_values(rr_ms: np.ndarray, index: int) -> tuple[float, float]:
    """The closed-form RMSSD and SDNN of one fixed window, by interval end time."""

    ends_s = np.cumsum(rr_ms) / 1000.0
    values = rr_ms[(ends_s >= index * WINDOW_S) & (ends_s < (index + 1) * WINDOW_S)].astype(float)
    differences = np.diff(values)
    return float(np.sqrt(np.mean(differences**2))), float(np.std(values, ddof=1))


def test_each_window_matches_the_closed_form_and_the_summary_is_their_median(
    tmp_path: Path,
) -> None:
    rr_ms = _resting_rr()
    result = hrv_metrics(_recording(tmp_path, rr_ms))

    ends_s = np.cumsum(rr_ms) / 1000.0
    assert len(result.windows) == 2
    for index, window in enumerate(result.windows):
        rmssd, sdnn = _window_values(rr_ms, index)
        values = rr_ms[(ends_s >= index * WINDOW_S) & (ends_s < (index + 1) * WINDOW_S)]
        assert window.rmssd_ms == pytest.approx(rmssd, abs=1e-6)
        assert window.sdnn_ms == pytest.approx(sdnn, abs=1e-6)
        assert window.ln_rmssd == pytest.approx(float(np.log(rmssd)), abs=1e-9)
        assert window.mean_hr_bpm == pytest.approx(60000.0 / float(np.mean(values)), abs=1e-9)
        assert window.artifact_share == 0.0
        assert window.included and window.reason == ""

    assert result.metrics["hrv_window_count"] == 2
    assert result.metrics["hrv_rr_count"] == rr_ms.size
    assert result.metrics["hrv_artifact_share"] == 0.0
    assert result.metrics["hrv_rmssd_ms"] == pytest.approx(
        round(float(np.median([_window_values(rr_ms, index)[0] for index in (0, 1)])), 1),
        abs=1e-9,
    )
    assert result.metrics["hrv_sdnn_median_ms"] == pytest.approx(
        round(float(np.median([_window_values(rr_ms, index)[1] for index in (0, 1)])), 1),
        abs=1e-9,
    )
    assert result.support["hrv_rmssd_ms"]["status"] == "supported"


def _cleaned_window_values(
    rr_ms: np.ndarray, excluded: set[int], index: int
) -> tuple[float, float]:
    """The closed-form RMSSD and SDNN of a window after removing some intervals.

    A difference is only used between two intervals that both survive and are adjacent
    in the original series, which is what "never across a removed beat" means.
    """

    ends_s = np.cumsum(rr_ms) / 1000.0
    indices = [
        position
        for position in range(rr_ms.size)
        if position not in excluded
        and index * WINDOW_S <= ends_s[position] < (index + 1) * WINDOW_S
    ]
    values = rr_ms[indices].astype(float)
    differences = [
        float(rr_ms[indices[step + 1]] - rr_ms[indices[step]])
        for step in range(len(indices) - 1)
        if indices[step + 1] == indices[step] + 1
    ]
    return (
        float(np.sqrt(np.mean(np.asarray(differences) ** 2))) if differences else 0.0,
        float(np.std(values, ddof=1)),
    )


def test_an_ectopic_pair_does_not_move_rmssd_or_sdnn(tmp_path: Path) -> None:
    rr_ms = _resting_rr()
    clean = hrv_metrics(_recording(tmp_path / "clean", rr_ms))

    corrupted_rr = rr_ms.copy()
    corrupted_rr[300] = 600
    corrupted_rr[301] = 1400
    corrupted = hrv_metrics(_recording(tmp_path / "ectopic", corrupted_rr))

    assert corrupted.metrics["hrv_artifact_share"] == pytest.approx(2 / rr_ms.size, abs=1e-4)
    for index, window in enumerate(corrupted.windows):
        rmssd, sdnn = _cleaned_window_values(corrupted_rr, {300, 301}, index)
        assert window.rmssd_ms == pytest.approx(rmssd, abs=1e-6)
        assert window.sdnn_ms == pytest.approx(sdnn, abs=1e-6)
    assert corrupted.metrics["hrv_rmssd_ms"] == pytest.approx(
        clean.metrics["hrv_rmssd_ms"], abs=1.0
    )
    assert corrupted.metrics["hrv_sdnn_median_ms"] == pytest.approx(
        clean.metrics["hrv_sdnn_median_ms"], abs=1.0
    )


def test_a_missed_beat_does_not_move_rmssd_or_sdnn(tmp_path: Path) -> None:
    rr_ms = _resting_rr()
    clean = hrv_metrics(_recording(tmp_path / "clean", rr_ms))

    corrupted_rr = rr_ms.copy()
    corrupted_rr[300] = 2 * corrupted_rr[300]
    corrupted = hrv_metrics(_recording(tmp_path / "missed", corrupted_rr))

    assert corrupted.metrics["hrv_artifact_share"] == pytest.approx(1 / rr_ms.size, abs=1e-4)
    for index, window in enumerate(corrupted.windows):
        rmssd, sdnn = _cleaned_window_values(corrupted_rr, {300}, index)
        assert window.rmssd_ms == pytest.approx(rmssd, abs=1e-6)
        assert window.sdnn_ms == pytest.approx(sdnn, abs=1e-6)
    assert corrupted.metrics["hrv_rmssd_ms"] == pytest.approx(
        clean.metrics["hrv_rmssd_ms"], abs=1.0
    )
    assert corrupted.metrics["hrv_sdnn_median_ms"] == pytest.approx(
        clean.metrics["hrv_sdnn_median_ms"], abs=1.0
    )


def test_a_gap_is_not_a_beat_to_beat_difference(tmp_path: Path) -> None:
    rr_ms = _resting_rr()
    times, batches = _packets(rr_ms)
    for second in range(300, 310):
        times.pop(second, None)
        batches.pop(second, None)
    result = hrv_metrics(_recording_from_packets(tmp_path, times, batches))

    # The beats on either side of the hole are next to each other in the series but ten
    # seconds apart in time, so no difference is taken between them.
    ends_s = np.cumsum(rr_ms) / 1000.0
    survived = ~((ends_s >= 300) & (ends_s < 310))
    assert result.metrics["hrv_rr_count"] == int(np.count_nonzero(survived))
    assert len(result.windows) == 2

    expected = []
    for index in range(2):
        values = rr_ms[
            survived & (ends_s >= index * WINDOW_S) & (ends_s < (index + 1) * WINDOW_S)
        ].astype(float)
        expected.append(float(np.sqrt(np.mean(np.diff(values) ** 2))))
        assert result.windows[index].rmssd_ms == pytest.approx(expected[index], abs=1e-6)
    # A difference taken across the hole would be about ten seconds and lift RMSSD far
    # above the beat-to-beat variation of the rest of the recording.
    assert result.metrics["hrv_rmssd_ms"] < 60.0
    assert result.metrics["hrv_rmssd_ms"] == pytest.approx(
        round(float(np.median(expected)), 1), abs=1e-9
    )


def test_the_artifact_share_is_reported_per_window_and_overall(tmp_path: Path) -> None:
    rr_ms = _resting_rr()
    corrupted_rr = rr_ms.copy()
    for index in range(40, 540, 30):
        corrupted_rr[index] = 600
        corrupted_rr[index + 1] = 1400
    result = hrv_metrics(_recording(tmp_path, corrupted_rr))

    series = [window.to_dict() for window in result.windows]
    assert len(series) == 2
    assert all(entry["artifact_share"] > 0.0 for entry in series)
    real = sum(entry["intervals"] / (1.0 - entry["artifact_share"]) for entry in series)
    flagged = sum(
        entry["artifact_share"] * entry["intervals"] / (1.0 - entry["artifact_share"])
        for entry in series
    )
    assert result.metrics["hrv_artifact_share"] == pytest.approx(flagged / real, abs=1e-4)
    assert 0.0 < result.metrics["hrv_artifact_share"] < 1.0


def test_a_window_with_too_little_data_is_excluded(tmp_path: Path) -> None:
    # The recording is ten minutes long but the strap reported beats for two.
    rr_ms = _resting_rr(count=120)
    result = hrv_metrics(_recording(tmp_path, rr_ms))

    assert len(result.windows) == 2
    assert all(window.included is False for window in result.windows)
    assert all(window.reason == TOO_LITTLE_DATA for window in result.windows)
    assert result.metrics["hrv_window_count"] == 0
    assert result.metrics["hrv_rmssd_ms"] is None
    assert result.support["hrv_rmssd_ms"]["reason"] == "no_valid_hrv_windows"


def test_a_window_with_more_than_five_percent_artifacts_is_excluded(tmp_path: Path) -> None:
    rr_ms = _resting_rr()
    corrupted_rr = rr_ms.copy()
    for index in range(40, 280, 15):
        corrupted_rr[index] = 600
        corrupted_rr[index + 1] = 1400
    result = hrv_metrics(_recording(tmp_path, corrupted_rr))

    first, second = result.windows
    assert first.included is False
    assert first.reason == ARTIFACTS
    assert second.included is True
    assert result.metrics["hrv_window_count"] == 1
    assert result.metrics["hrv_rmssd_ms"] == pytest.approx(round(second.rmssd_ms, 1), abs=1e-9)


def test_a_window_with_movement_is_excluded(tmp_path: Path) -> None:
    rr_ms = _resting_rr()
    result = hrv_metrics(_recording(tmp_path, rr_ms, movement_windows=[(0, 90)]))

    first, second = result.windows
    assert first.included is False
    assert first.reason == MOVEMENT
    assert second.included is True
    # Only the calm window feeds the summary.
    assert result.metrics["hrv_window_count"] == 1
    assert result.metrics["hrv_rmssd_ms"] == pytest.approx(round(second.rmssd_ms, 1), abs=1e-9)


def test_a_recording_shorter_than_one_window_has_no_hrv(tmp_path: Path) -> None:
    fx.write_recording(
        tmp_path,
        duration_s=240,
        rr_series_ms=[int(value) for value in _resting_rr(count=230)],
        include_ecg=False,
        include_acc=False,
    )
    recording = load_recording(tmp_path)
    derived = derive(
        recording,
        recording.epochs(30),
        ["sleep"] * 8,
        AlgorithmInfo(
            id="test-wake-sleep",
            version="1.0.0",
            name="Test wake/sleep",
            description="Wake and sleep only.",
            license="MIT",
            needs=(StreamNeed("hr", required=True),),
            stage_scheme="wake-sleep",
            supported_stages=WAKE_SLEEP_STAGES,
        ),
    )

    assert derived.metrics["hrv_rmssd_ms"] is None
    assert derived.support["hrv_rmssd_ms"]["reason"] == "recording_shorter_than_one_hrv_window"
    assert derived.hrv_windows is None


STAGE_PROBES = {
    "all-deep": (
        AlgorithmInfo(
            id="probe-all-deep",
            version="1.0.0",
            name="All deep",
            description="Every interval deep sleep.",
            license="MIT",
            needs=(StreamNeed("hr", required=True),),
            stage_scheme="aasm4",
            supported_stages=AASM4_STAGES,
        ),
        "deep",
    ),
    "no-stages": (
        AlgorithmInfo(
            id="probe-no-stages",
            version="1.0.0",
            name="No stages",
            description="Every interval unscored.",
            license="MIT",
            needs=(StreamNeed("hr", required=True),),
            stage_scheme="wake-sleep",
            supported_stages=WAKE_SLEEP_STAGES,
        ),
        "unknown",
    ),
}


class _FixedStager(Stager):
    info = STAGE_PROBES["all-deep"][0]

    def stage(self, recording, params):
        grid = recording.epochs(self.info.epoch_seconds)
        return StagingResult(stages=[self.label] * grid.count)


def _fixed_stager(name: str) -> Stager:
    info, label = STAGE_PROBES[name]

    class StagerFor(_FixedStager):
        pass

    StagerFor.info = info
    StagerFor.label = label
    return StagerFor()


def _hrv_items(payload: dict) -> list[dict]:
    return [
        item for item in payload["items"] if item["id"] == "hrv" or item["id"].startswith("hrv_")
    ]


def test_the_hrv_items_do_not_depend_on_the_stager(tmp_path: Path) -> None:
    fx.write_recording(
        tmp_path,
        duration_s=DURATION_S,
        rr_series_ms=[int(value) for value in _resting_rr()],
        include_ecg=False,
    )
    algorithms = {
        name: RegisteredAlgorithm(info=info, stager=_fixed_stager(name), source="test")
        for name, (info, _) in STAGE_PROBES.items()
    }
    registry = Registry(algorithms=algorithms)

    baseline = _hrv_items(run_analysis(tmp_path, "baseline", {}).to_dict())
    other = _hrv_items(run_analysis(tmp_path, "probe-all-deep", {}, registry=registry).to_dict())
    none = _hrv_items(run_analysis(tmp_path, "probe-no-stages", {}, registry=registry).to_dict())

    assert baseline
    assert other == baseline
    assert none == baseline
