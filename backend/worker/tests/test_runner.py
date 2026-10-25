from __future__ import annotations

import sys
import unittest

from taiga_worker.runner import JobRunner


class RunnerTest(unittest.TestCase):
    def test_successful_job_returns_result(self) -> None:
        runner = JobRunner(
            memory_mb=512,
            timeout_s=10,
            command=[
                sys.executable,
                "-c",
                "import sys; sys.stdin.read(); print('{\"ok\": true}')",
            ],
        )
        outcome = runner.run({"id": "job1"})
        self.assertTrue(outcome.ok)
        self.assertEqual(outcome.result, {"ok": True})
        self.assertIsNone(outcome.error)

    def test_nonzero_exit_is_a_failure(self) -> None:
        runner = JobRunner(
            memory_mb=512,
            timeout_s=10,
            command=[sys.executable, "-c", "import sys; print('boom', file=sys.stderr); sys.exit(3)"],
        )
        outcome = runner.run({"id": "job1"})
        self.assertFalse(outcome.ok)
        self.assertIn("boom", outcome.error or "")
        self.assertTrue(outcome.retryable)

    def test_invalid_output_is_a_failure(self) -> None:
        runner = JobRunner(
            memory_mb=512,
            timeout_s=10,
            command=[sys.executable, "-c", "print('not json')"],
        )
        outcome = runner.run({"id": "job1"})
        self.assertFalse(outcome.ok)
        self.assertIn("not valid JSON", outcome.error or "")

    def test_failed_status_payload_is_a_failure(self) -> None:
        runner = JobRunner(
            memory_mb=512,
            timeout_s=10,
            command=[
                sys.executable,
                "-c",
                "print('{\"status\": \"failed\", \"error\": \"bad data\"}')",
            ],
        )
        outcome = runner.run({"id": "job1"})
        self.assertFalse(outcome.ok)
        self.assertEqual(outcome.error, "bad data")
        self.assertFalse(outcome.retryable)

    def test_retryable_failure_payload(self) -> None:
        runner = JobRunner(
            memory_mb=512,
            timeout_s=10,
            command=[
                sys.executable,
                "-c",
                "print('{\"status\": \"failed\", \"error\": \"temporary\", \"retryable\": true}')",
            ],
        )
        outcome = runner.run({"id": "job1"})
        self.assertFalse(outcome.ok)
        self.assertTrue(outcome.retryable)

    def test_timeout_kills_the_child(self) -> None:
        runner = JobRunner(
            memory_mb=512,
            timeout_s=0.2,
            command=[sys.executable, "-c", "import time; time.sleep(5)"],
        )
        outcome = runner.run({"id": "job1"})
        self.assertFalse(outcome.ok)
        self.assertIn("timed out", outcome.error or "")
        self.assertTrue(outcome.retryable)


if __name__ == "__main__":
    unittest.main()
