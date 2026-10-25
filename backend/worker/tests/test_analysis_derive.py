from __future__ import annotations

import tempfile
import unittest

from tests import analysis_fixture as fx
from taiga_worker.analysis.algorithms import baseline
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.derive import derive


class DeriveTest(unittest.TestCase):
    def _recording(self, tmp, **kwargs):
        kwargs.setdefault("duration_s", 120)
        fx.write_recording(tmp, **kwargs)
        return load_recording(tmp)

    def test_metrics_from_a_known_stage_timeline(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            recording = self._recording(
                tmp,
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
        self.assertEqual(metrics["total_duration_s"], 120.0)
        self.assertEqual(metrics["total_sleep_s"], 90.0)
        self.assertEqual(metrics["sleep_efficiency"], 0.75)
        self.assertEqual(metrics["sleep_latency_s"], 30.0)
        self.assertEqual(metrics["waso_s"], 0.0)
        self.assertEqual(metrics["awakenings"], 0)
        self.assertEqual(metrics["final_awakening_s"], 0.0)
        self.assertEqual(metrics["time_in_wake_s"], 30.0)
        self.assertEqual(metrics["time_in_light_s"], 30.0)
        self.assertEqual(metrics["time_in_deep_s"], 30.0)
        self.assertEqual(metrics["time_in_rem_s"], 30.0)
        self.assertIsNone(metrics["time_in_nrem_s"])
        self.assertEqual(metrics["hr_mean_bpm"], 60.0)
        self.assertEqual(metrics["hr_min_bpm"], 60.0)
        self.assertEqual(metrics["hr_max_bpm"], 60.0)
        self.assertEqual(metrics["hrv_rr_count"], 360)
        self.assertGreater(metrics["hrv_rmssd_ms"], 0.0)
        self.assertGreater(metrics["hrv_sdnn_ms"], 0.0)
        self.assertEqual(metrics["movement_index"], 0.25)
        self.assertGreater(metrics["movement_mean_g"], 0.02)
        self.assertEqual(metrics["movement_epochs"], 1)

        self.assertEqual(derived.support["time_in_nrem_s"]["status"], "unsupported_algorithm")
        self.assertEqual(derived.support["hr_mean_bpm"]["status"], "supported")

        quality = derived.quality
        self.assertEqual(quality["score"], 95.0)
        self.assertEqual(quality["components"]["mean_stage_confidence"]["value"], 0.8)
        self.assertEqual(quality["notes"], [])

    def test_wake_after_sleep_onset_and_awakenings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            recording = self._recording(tmp)
            grid = recording.epochs(30)
            derived = derive(
                recording,
                grid,
                ["light", "wake", "light", "deep"],
                baseline.INFO,
                confidence=[1.0, 1.0, 1.0, 1.0],
            )

        self.assertEqual(derived.metrics["sleep_latency_s"], 0.0)
        self.assertEqual(derived.metrics["waso_s"], 30.0)
        self.assertEqual(derived.metrics["awakenings"], 1)
        self.assertEqual(derived.metrics["final_awakening_s"], 0.0)

    def test_quality_renormalizes_unavailable_confidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            recording = self._recording(tmp)
            grid = recording.epochs(30)
            derived = derive(recording, grid, ["light"] * 4, baseline.INFO, confidence=None)

        self.assertEqual(derived.quality["score"], 100.0)
        self.assertFalse(derived.quality["components"]["mean_stage_confidence"]["available"])
        self.assertTrue(any("mean_stage_confidence" in note for note in derived.quality["notes"]))

    def test_missing_acc_is_an_unsupported_metric_not_a_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            recording = self._recording(tmp, include_acc=False)
            grid = recording.epochs(30)
            derived = derive(recording, grid, ["light"] * 4, baseline.INFO, confidence=[1.0] * 4)

        self.assertIsNone(derived.metrics["movement_index"])
        self.assertEqual(
            derived.support["movement_index"]["status"], "unsupported_device"
        )
        self.assertIsNotNone(derived.quality["score"])

    def test_missing_hr_is_unsupported_and_quality_is_renormalized(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            recording = self._recording(tmp)
            from pathlib import Path

            (Path(tmp) / "hr.log").unlink()
            recording = load_recording(tmp)
            grid = recording.epochs(30)
            derived = derive(recording, grid, ["light"] * 4, baseline.INFO, confidence=[1.0] * 4)

        self.assertIsNone(derived.metrics["hr_mean_bpm"])
        self.assertEqual(derived.support["hr_mean_bpm"]["status"], "unsupported_device")
        self.assertFalse(derived.quality["components"]["hr_coverage"]["available"])
        self.assertIsNotNone(derived.quality["score"])


if __name__ == "__main__":
    unittest.main()
