from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tests import analysis_fixture as fx
from taiga_worker.analysis.contract import CONTRACT_VERSION, AnalysisError
from taiga_worker.analysis.pipeline import run_analysis


class PipelineTest(unittest.TestCase):
    def test_full_result_is_json_serializable_and_model_agnostic(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=120)
            output = run_analysis(tmp, "baseline", {})
            payload = output.to_dict()
            encoded = json.dumps(payload)

        self.assertIn('"baseline"', encoded)
        self.assertEqual(payload["contract_version"], CONTRACT_VERSION)
        self.assertEqual(payload["model"], "baseline")
        self.assertEqual(payload["model_version"], "1.0.0")
        self.assertEqual(len(payload["params_hash"]), 16)
        self.assertEqual(payload["stages"]["epoch_seconds"], 30)
        self.assertEqual(payload["stages"]["count"], 4)
        self.assertEqual(sum(item["count"] for item in payload["stages"]["segments"]), 4)
        self.assertEqual(len(payload["stages"]["confidence"]), 4)
        self.assertEqual(len(payload["stages"]["artifacts"]), 4)
        self.assertIn("metrics", payload)
        self.assertIn("metric_support", payload)
        self.assertIn("quality", payload)
        self.assertIn("capabilities", payload)
        self.assertIn("provenance", payload)
        self.assertTrue(payload["capabilities"]["stages"])
        self.assertEqual(payload["provenance"]["recording"]["session_id"], "20261023_223000")
        self.assertGreaterEqual(payload["provenance"]["runtime_s"], 0)
        self.assertGreater(payload["provenance"]["peak_rss_mb"], 0)

    def test_params_hash_changes_with_params(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=60)
            first = run_analysis(tmp, "baseline", {})
            second = run_analysis(tmp, "baseline", {"deep_hr_ratio": 0.8})
            third = run_analysis(tmp, "baseline", {"deep_hr_ratio": 0.8})

        self.assertNotEqual(first.params_hash, second.params_hash)
        self.assertEqual(second.params_hash, third.params_hash)

    def test_invalid_params_fail_with_a_clear_code(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=60)
            with self.assertRaises(AnalysisError) as context:
                run_analysis(tmp, "baseline", {"unknown": 1})
        self.assertEqual(context.exception.code, "invalid_params")

    def test_missing_required_stream_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=60)
            (Path(tmp) / "hr.log").unlink()
            with self.assertRaises(AnalysisError) as context:
                run_analysis(tmp, "baseline", {})
        self.assertEqual(context.exception.code, "missing_stream")
        self.assertIn("HR", str(context.exception))

    def test_short_recording_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=10)
            with self.assertRaises(AnalysisError) as context:
                run_analysis(tmp, "baseline", {})
        self.assertEqual(context.exception.code, "recording_too_short")

    def test_unknown_algorithm_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=60)
            with self.assertRaises(AnalysisError) as context:
                run_analysis(tmp, "no-such-algorithm", {})
        self.assertEqual(context.exception.code, "unknown_algorithm")


if __name__ == "__main__":
    unittest.main()
