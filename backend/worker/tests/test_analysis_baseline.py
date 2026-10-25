from __future__ import annotations

import tempfile
import unittest

from tests import analysis_fixture as fx
from taiga_worker.analysis.algorithms import baseline
from taiga_worker.analysis.contract import AnalysisError
from taiga_worker.analysis.decode import load_recording


def _profile() -> list[int]:
    return [70] * 30 + [50] * 30 + [55, 65] * 15 + [60] * 30


class BaselineTest(unittest.TestCase):
    def test_reference_rules_and_determinism(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(
                tmp,
                duration_s=120,
                hr_values=_profile(),
                movement_windows=[(0, 30)],
            )
            recording = load_recording(tmp)

        first = baseline.STAGER.stage(recording, {})
        second = baseline.STAGER.stage(recording, {})
        self.assertEqual(first.stages, ["wake", "deep", "rem", "light"])
        self.assertEqual(first.stages, second.stages)
        self.assertEqual(first.confidence, second.confidence)
        self.assertEqual(len(first.confidence or []), 4)
        self.assertTrue(all(value is not None and value > 0 for value in first.confidence or []))
        self.assertEqual(first.artifacts, [False, False, False, False])
        self.assertEqual(first.extras["night_median_hr_bpm"], 60.0)

    def test_no_contact_produces_unknown_epochs_not_a_crash(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=120, contact=False)
            recording = load_recording(tmp)

        result = baseline.STAGER.stage(recording, {})
        self.assertEqual(result.stages, ["unknown"] * 4)
        self.assertEqual(result.confidence, [0.0] * 4)
        self.assertEqual(result.artifacts, [True] * 4)
        self.assertTrue(any("no valid HR" in warning for warning in result.warnings))

    def test_missing_acc_disables_movement_rules(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=120, hr_values=_profile(), include_acc=False)
            recording = load_recording(tmp)

        result = baseline.STAGER.stage(recording, {})
        self.assertEqual(len(result.stages), 4)
        self.assertFalse(result.extras["has_acc"])
        self.assertTrue(any("no ACC" in warning for warning in result.warnings))

    def test_invalid_params_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=60)
            recording = load_recording(tmp)

        with self.assertRaises(AnalysisError) as context:
            baseline.STAGER.stage(recording, {"not_a_parameter": 1})
        self.assertEqual(context.exception.code, "invalid_params")

        with self.assertRaises(AnalysisError) as context:
            baseline.STAGER.stage(recording, {"deep_hr_ratio": 2.0})
        self.assertEqual(context.exception.code, "invalid_params")


if __name__ == "__main__":
    unittest.main()
