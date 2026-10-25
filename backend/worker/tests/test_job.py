from __future__ import annotations

import io
import json
import sys
from pathlib import Path

import pytest

from taiga_worker.analysis import AnalysisError
from taiga_worker.job import analyze, main
from tests import analysis_fixture as fx


def test_analyze_returns_a_model_agnostic_result(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    result = analyze(
        {
            "id": "job1",
            "recording_dir": str(tmp_path),
            "model": "baseline",
            "model_version": "1.1.0",
            "params": {},
            "params_hash": "api-hash",
            "worker": "test-worker",
        }
    )

    assert result["model"] == "baseline"
    assert result["model_version"] == "1.1.0"
    assert result["params_hash"] == "api-hash"
    assert result["stages"]["count"] == 2
    assert result["provenance"]["worker"] == "test-worker"


def test_missing_recording_dir_is_a_clear_error() -> None:
    with pytest.raises(AnalysisError) as context:
        analyze({"id": "job1", "model": "baseline"})
    assert context.value.code == "missing_recording"


def test_main_prints_a_failure_json_for_a_missing_recording(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(sys, "stdin", io.StringIO(json.dumps({"id": "job1", "model": "baseline"})))

    code = main()

    assert code == 0
    payload = json.loads(capsys.readouterr().out.strip())
    assert payload["status"] == "failed"
    assert "missing_recording" in payload["error"]
    assert not payload["retryable"]
