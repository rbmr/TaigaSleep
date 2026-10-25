from __future__ import annotations

import pytest

from taiga_worker.analysis.algorithms import sleepecg, wav2sleep
from taiga_worker.analysis.contract import AnalysisError
from taiga_worker.analysis.registry import discover


def _unrelated_errors(registry) -> list[str]:
    return [
        error
        for error in registry.errors
        if "wav2sleep" not in error and "sleepecg" not in error
    ]


def test_discovers_the_builtin_baseline() -> None:
    registry = discover()
    assert _unrelated_errors(registry) == []
    selected = registry.select("baseline")
    assert selected.info.id == "baseline"
    assert selected.info.version == "1.1.0"
    assert selected.source == "builtin"


def test_selects_an_exact_version() -> None:
    registry = discover()
    selected = registry.select("baseline", "1.1.0")
    assert selected.info.version == "1.1.0"


def test_unknown_algorithm_is_a_clear_error() -> None:
    registry = discover()
    with pytest.raises(AnalysisError) as context:
        registry.select("does-not-exist")
    assert context.value.code == "unknown_algorithm"

    with pytest.raises(AnalysisError) as context:
        registry.select("baseline", "9.9.9")
    assert context.value.code == "unknown_algorithm"


def test_capabilities_include_source_and_versions() -> None:
    registry = discover()
    capabilities = {item["id"]: item for item in registry.capabilities()}
    assert set(capabilities) >= {"baseline"}
    item = capabilities["baseline"]
    assert item["source"] == "builtin"
    assert item["available_versions"] == ["1.1.0"]
    assert "needs" in item
    assert "supported_stages" in item


def test_optional_wav2sleep_is_discovered_only_when_usable() -> None:
    available, reason = wav2sleep.availability()
    if not available:
        pytest.skip(f"wav2sleep is not available: {reason or 'optional extra not installed'}")
    assert wav2sleep.INFO.id in discover().latest_by_id()


def test_allowlist_filters_and_reports_missing_algorithms() -> None:
    registry = discover(allowlist=["baseline"])
    assert ("baseline", "1.1.0") in registry.algorithms

    missing = discover(allowlist=["not-installed"])
    assert missing.algorithms == {}
    assert any("not installed" in error for error in missing.errors)

def test_optional_sleepecg_is_discovered_only_when_usable() -> None:
    available, reason = sleepecg.availability()
    if not available:
        pytest.skip(f"SleepECG is not available: {reason or 'optional extra not installed'}")
    assert sleepecg.INFO.id in discover().latest_by_id()
