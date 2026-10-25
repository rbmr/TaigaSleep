from __future__ import annotations

import json
from pathlib import Path

import pytest

from taiga_worker.analysis.contract import CONTRACT_VERSION, AnalysisError
from taiga_worker.analysis.pipeline import run_analysis
from tests import analysis_fixture as fx


def test_full_result_is_json_serializable_and_model_agnostic(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    output = run_analysis(tmp_path, "baseline", {})
    payload = output.to_dict()
    encoded = json.dumps(payload)

    assert '"baseline"' in encoded
    assert payload["contract_version"] == CONTRACT_VERSION
    assert payload["model"] == "baseline"
    assert payload["model_version"] == "1.0.0"
    assert len(payload["params_hash"]) == 16
    assert payload["stages"]["epoch_seconds"] == 30
    assert payload["stages"]["count"] == 4
    assert sum(item["count"] for item in payload["stages"]["segments"]) == 4
    assert len(payload["stages"]["confidence"]) == 4
    assert len(payload["stages"]["artifacts"]) == 4
    assert "metrics" in payload
    assert "metric_support" in payload
    assert "quality" in payload
    assert "capabilities" in payload
    assert "provenance" in payload
    assert payload["capabilities"]["stages"]
    assert payload["provenance"]["recording"]["session_id"] == "20261023_223000"
    assert payload["provenance"]["runtime_s"] >= 0
    assert payload["provenance"]["peak_rss_mb"] > 0


def test_params_hash_changes_with_params(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    first = run_analysis(tmp_path, "baseline", {})
    second = run_analysis(tmp_path, "baseline", {"deep_hr_ratio": 0.8})
    third = run_analysis(tmp_path, "baseline", {"deep_hr_ratio": 0.8})

    assert first.params_hash != second.params_hash
    assert second.params_hash == third.params_hash


def test_params_hash_override_and_latest_version(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    output = run_analysis(
        tmp_path,
        "baseline",
        {},
        version="latest",
        params_hash_override="api-hash",
    )

    assert output.params_hash == "api-hash"
    assert output.model_version == "1.0.0"


def test_invalid_params_fail_with_a_clear_code(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "baseline", {"unknown": 1})
    assert context.value.code == "invalid_params"


def test_missing_required_stream_fails_clearly(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    (tmp_path / "hr.log").unlink()
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "baseline", {})
    assert context.value.code == "missing_stream"
    assert "HR" in str(context.value)


def test_short_recording_fails_clearly(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=10)
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "baseline", {})
    assert context.value.code == "recording_too_short"


def test_unknown_algorithm_fails_clearly(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "no-such-algorithm", {})
    assert context.value.code == "unknown_algorithm"
