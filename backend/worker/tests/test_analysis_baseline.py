from __future__ import annotations

from pathlib import Path

import pytest

from taiga_worker.analysis.algorithms import baseline
from taiga_worker.analysis.contract import AnalysisError
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.pipeline import run_analysis
from tests import analysis_fixture as fx

WAKE_SLEEP_UNKNOWN = {"wake", "sleep", "unknown"}


def _profile() -> list[int]:
    return [70] * 30 + [50] * 30 + [55, 65] * 15 + [60] * 30


def test_reference_rules_and_determinism(tmp_path: Path) -> None:
    fx.write_recording(
        tmp_path,
        duration_s=120,
        hr_values=_profile(),
        movement_windows=[(0, 30)],
    )
    recording = load_recording(tmp_path)

    first = baseline.STAGER.stage(recording, {})
    second = baseline.STAGER.stage(recording, {})
    assert first.stages == ["wake", "sleep", "sleep", "sleep"]
    assert first.stages == second.stages
    assert first.confidence is None
    assert first.artifacts == [False, False, False, False]
    assert first.extras["night_median_hr_bpm"] == 60.0


def test_the_stager_declares_the_split_stages_unsupported() -> None:
    assert baseline.INFO.stage_scheme == "wake-sleep"
    assert set(baseline.INFO.supported_stages) == WAKE_SLEEP_UNKNOWN


def test_the_result_only_contains_wake_sleep_and_unknown(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=240, movement_windows=[(0, 30)])
    payload = run_analysis(tmp_path, "baseline", {}).to_dict()

    present = {segment["stage"] for segment in payload["stages"]["segments"]}
    assert present <= WAKE_SLEEP_UNKNOWN
    assert payload["capabilities"]["supported_stages"] == ["wake", "sleep", "unknown"]
    assert payload["capabilities"]["stage_scheme"] == "wake-sleep"
    assert payload["capabilities"]["confidence"] is False
    assert payload["stages"]["confidence"] is None


def test_no_contact_produces_unknown_epochs_not_a_crash(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120, contact=False)
    recording = load_recording(tmp_path)

    result = baseline.STAGER.stage(recording, {})
    assert result.stages == ["unknown"] * 4
    assert result.confidence is None
    assert result.artifacts == [True] * 4
    assert any("no valid HR" in warning for warning in result.warnings)


def test_missing_acc_disables_movement_rules(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120, hr_values=_profile(), include_acc=False)
    recording = load_recording(tmp_path)

    result = baseline.STAGER.stage(recording, {})
    assert len(result.stages) == 4
    assert not result.extras["has_acc"]
    assert any("no ACC" in warning for warning in result.warnings)


def test_invalid_params_are_rejected(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    recording = load_recording(tmp_path)

    with pytest.raises(AnalysisError) as context:
        baseline.STAGER.stage(recording, {"not_a_parameter": 1})
    assert context.value.code == "invalid_params"

    # The deep and REM rules are gone, so their parameters are not accepted.
    with pytest.raises(AnalysisError) as context:
        baseline.STAGER.stage(recording, {"deep_hr_ratio": 0.9})
    assert context.value.code == "invalid_params"

    with pytest.raises(AnalysisError) as context:
        baseline.STAGER.stage(recording, {"smooth_window_epochs": 0})
    assert context.value.code == "invalid_params"
