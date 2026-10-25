from __future__ import annotations

import pytest

from taiga_worker.analysis.contract import (
    AASM4_STAGES,
    WAKE_SLEEP_STAGES,
    AlgorithmInfo,
    AnalysisError,
    Stager,
    StagingResult,
    validate_staging_result,
)


def _info(**overrides):
    data = {
        "id": "test-algo",
        "version": "1.0.0",
        "name": "Test",
        "description": "Test algorithm",
        "license": "MIT",
        "supported_stages": AASM4_STAGES,
    }
    data.update(overrides)
    return AlgorithmInfo(**data)


def test_valid_info() -> None:
    info = _info()
    assert info.id == "test-algo"
    assert info.epoch_seconds == 30
    assert "stages" in info.capabilities


def test_invalid_id_is_rejected() -> None:
    with pytest.raises(ValueError):
        _info(id="Not Valid")


def test_supported_stages_must_include_unknown() -> None:
    with pytest.raises(ValueError):
        _info(supported_stages=("wake", "light"))


def test_stage_scheme_cannot_declare_foreign_stages() -> None:
    with pytest.raises(ValueError):
        _info(stage_scheme="wake-sleep", supported_stages=("wake", "sleep", "light", "unknown"))
    with pytest.raises(ValueError):
        _info(stage_scheme="wake-rem-nrem", supported_stages=("wake", "rem", "nrem", "deep", "unknown"))


def test_wake_sleep_scheme_is_valid() -> None:
    info = _info(stage_scheme="wake-sleep", supported_stages=WAKE_SLEEP_STAGES)
    assert info.stage_scheme == "wake-sleep"


def test_stager_abc_requires_info_and_stage() -> None:
    with pytest.raises(TypeError):
        Stager()  # abstract

    with pytest.raises(TypeError):

        class MissingInfo(Stager):
            def stage(self, recording, params):
                return StagingResult(stages=[])

    class ValidStager(Stager):
        info = _info()

        def stage(self, recording, params):
            return StagingResult(stages=[])

    assert isinstance(ValidStager(), Stager)

    class MissingStage(Stager):
        info = _info()

    with pytest.raises(TypeError):
        MissingStage()  # abstract


def test_result_length_mismatch_is_rejected() -> None:
    with pytest.raises(AnalysisError) as context:
        validate_staging_result(
            StagingResult(stages=["light"]),
            epoch_count=2,
            info=_info(),
        )
    assert context.value.code == "invalid_result"


def test_unsupported_stage_is_rejected() -> None:
    with pytest.raises(AnalysisError) as context:
        validate_staging_result(
            StagingResult(stages=["nrem"]),
            epoch_count=1,
            info=_info(),
        )
    assert context.value.code == "invalid_result"


def test_optional_field_lengths_are_checked() -> None:
    with pytest.raises(AnalysisError):
        validate_staging_result(
            StagingResult(stages=["light"], confidence=[0.5, 0.5]),
            epoch_count=1,
            info=_info(),
        )
    with pytest.raises(AnalysisError):
        validate_staging_result(
            StagingResult(stages=["light"], artifacts=[False, True]),
            epoch_count=1,
            info=_info(),
        )


def test_beats_must_be_sorted() -> None:
    with pytest.raises(AnalysisError):
        validate_staging_result(
            StagingResult(stages=["light"], beats_s=[2.0, 1.0]),
            epoch_count=1,
            info=_info(),
        )


def test_valid_optional_fields_are_accepted() -> None:
    validate_staging_result(
        StagingResult(
            stages=["light"],
            confidence=[0.5],
            artifacts=[False],
            probabilities={"light": [0.5], "wake": [0.5]},
            beats_s=[0.8, 1.6],
        ),
        epoch_count=1,
        info=_info(),
    )


def test_supported_stages_can_only_narrow_the_manifest() -> None:
    info = _info(supported_stages=AASM4_STAGES)
    narrowed = StagingResult(
        stages=["wake", "light", "deep", "rem"],
        supported_stages=("wake", "light", "unknown"),
    )
    with pytest.raises(AnalysisError) as context:
        validate_staging_result(narrowed, epoch_count=4, info=info)
    assert context.value.code == "invalid_result"

    outside = StagingResult(
        stages=["wake", "wake", "wake", "wake"],
        supported_stages=("wake", "sleep", "unknown"),
    )
    with pytest.raises(AnalysisError) as context:
        validate_staging_result(outside, epoch_count=4, info=info)
    assert context.value.code == "invalid_result"
