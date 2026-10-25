from __future__ import annotations

import sys

from taiga_worker.runner import JobRunner


def test_successful_job_returns_result() -> None:
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
    assert outcome.ok
    assert outcome.result == {"ok": True}
    assert outcome.error is None


def test_nonzero_exit_is_a_failure() -> None:
    runner = JobRunner(
        memory_mb=512,
        timeout_s=10,
        command=[sys.executable, "-c", "import sys; print('boom', file=sys.stderr); sys.exit(3)"],
    )
    outcome = runner.run({"id": "job1"})
    assert not outcome.ok
    assert "boom" in (outcome.error or "")
    assert outcome.retryable


def test_invalid_output_is_a_failure() -> None:
    runner = JobRunner(
        memory_mb=512,
        timeout_s=10,
        command=[sys.executable, "-c", "print('not json')"],
    )
    outcome = runner.run({"id": "job1"})
    assert not outcome.ok
    assert "not valid JSON" in (outcome.error or "")


def test_failed_status_payload_is_a_failure() -> None:
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
    assert not outcome.ok
    assert outcome.error == "bad data"
    assert not outcome.retryable


def test_retryable_failure_payload() -> None:
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
    assert not outcome.ok
    assert outcome.retryable


def test_timeout_kills_the_child() -> None:
    runner = JobRunner(
        memory_mb=512,
        timeout_s=0.2,
        command=[sys.executable, "-c", "import time; time.sleep(5)"],
    )
    outcome = runner.run({"id": "job1"})
    assert not outcome.ok
    assert "timed out" in (outcome.error or "")
    assert outcome.retryable
