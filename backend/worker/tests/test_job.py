from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout

from tests import analysis_fixture as fx
from taiga_worker.analysis import AnalysisError
from taiga_worker.job import analyze, main


class JobTest(unittest.TestCase):
    def test_analyze_returns_a_model_agnostic_result(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=60)
            result = analyze(
                {
                    "id": "job1",
                    "recording_dir": tmp,
                    "model": "baseline",
                    "model_version": "1.0.0",
                    "params": {},
                    "params_hash": "api-hash",
                    "worker": "test-worker",
                }
            )

        self.assertEqual(result["model"], "baseline")
        self.assertEqual(result["model_version"], "1.0.0")
        self.assertEqual(result["params_hash"], "api-hash")
        self.assertEqual(result["stages"]["count"], 2)
        self.assertEqual(result["provenance"]["worker"], "test-worker")

    def test_missing_recording_dir_is_a_clear_error(self) -> None:
        with self.assertRaises(AnalysisError) as context:
            analyze({"id": "job1", "model": "baseline"})
        self.assertEqual(context.exception.code, "missing_recording")

    def test_main_prints_a_failure_json_for_a_missing_recording(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            import sys

            original = sys.stdin
            sys.stdin = io.StringIO(json.dumps({"id": "job1", "model": "baseline"}))
            try:
                code = main()
            finally:
                sys.stdin = original

        self.assertEqual(code, 0)
        payload = json.loads(output.getvalue().strip())
        self.assertEqual(payload["status"], "failed")
        self.assertIn("missing_recording", payload["error"])
        self.assertFalse(payload["retryable"])


if __name__ == "__main__":
    unittest.main()
