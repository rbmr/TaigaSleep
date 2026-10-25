from __future__ import annotations

import unittest

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


class ContractTest(unittest.TestCase):
    def test_valid_info(self) -> None:
        info = _info()
        self.assertEqual(info.id, "test-algo")
        self.assertEqual(info.epoch_seconds, 30)
        self.assertIn("stages", info.capabilities)

    def test_invalid_id_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            _info(id="Not Valid")

    def test_supported_stages_must_include_unknown(self) -> None:
        with self.assertRaises(ValueError):
            _info(supported_stages=("wake", "light"))

    def test_stage_scheme_cannot_declare_foreign_stages(self) -> None:
        with self.assertRaises(ValueError):
            _info(stage_scheme="wake-sleep", supported_stages=("wake", "sleep", "light", "unknown"))
        with self.assertRaises(ValueError):
            _info(stage_scheme="wake-rem-nrem", supported_stages=("wake", "rem", "nrem", "deep", "unknown"))

    def test_wake_sleep_scheme_is_valid(self) -> None:
        info = _info(stage_scheme="wake-sleep", supported_stages=WAKE_SLEEP_STAGES)
        self.assertEqual(info.stage_scheme, "wake-sleep")

    def test_stager_abc_requires_info_and_stage(self) -> None:
        with self.assertRaises(TypeError):
            Stager()  # abstract

        with self.assertRaises(TypeError):
            class MissingInfo(Stager):
                def stage(self, recording, params):
                    return StagingResult(stages=[])

        class ValidStager(Stager):
            info = _info()

            def stage(self, recording, params):
                return StagingResult(stages=[])

        self.assertIsInstance(ValidStager(), Stager)

        class MissingStage(Stager):
            info = _info()

        with self.assertRaises(TypeError):
            MissingStage()  # abstract

    def test_result_length_mismatch_is_rejected(self) -> None:
        with self.assertRaises(AnalysisError) as context:
            validate_staging_result(
                StagingResult(stages=["light"]),
                epoch_count=2,
                info=_info(),
            )
        self.assertEqual(context.exception.code, "invalid_result")

    def test_unsupported_stage_is_rejected(self) -> None:
        with self.assertRaises(AnalysisError) as context:
            validate_staging_result(
                StagingResult(stages=["nrem"]),
                epoch_count=1,
                info=_info(),
            )
        self.assertEqual(context.exception.code, "invalid_result")

    def test_optional_field_lengths_are_checked(self) -> None:
        with self.assertRaises(AnalysisError):
            validate_staging_result(
                StagingResult(stages=["light"], confidence=[0.5, 0.5]),
                epoch_count=1,
                info=_info(),
            )
        with self.assertRaises(AnalysisError):
            validate_staging_result(
                StagingResult(stages=["light"], artifacts=[False, True]),
                epoch_count=1,
                info=_info(),
            )

    def test_beats_must_be_sorted(self) -> None:
        with self.assertRaises(AnalysisError):
            validate_staging_result(
                StagingResult(stages=["light"], beats_s=[2.0, 1.0]),
                epoch_count=1,
                info=_info(),
            )

    def test_valid_optional_fields_are_accepted(self) -> None:
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


if __name__ == "__main__":
    unittest.main()
