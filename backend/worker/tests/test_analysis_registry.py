from __future__ import annotations

import pytest

from taiga_worker.analysis.contract import AnalysisError
from taiga_worker.analysis.registry import discover


def test_discovers_the_builtin_baseline() -> None:
    registry = discover()
    assert registry.errors == []
    selected = registry.select("baseline")
    assert selected.info.id == "baseline"
    assert selected.info.version == "1.0.0"
    assert selected.source == "builtin"


def test_selects_an_exact_version() -> None:
    registry = discover()
    selected = registry.select("baseline", "1.0.0")
    assert selected.info.version == "1.0.0"


def test_unknown_algorithm_is_a_clear_error() -> None:
    registry = discover()
    with pytest.raises(AnalysisError) as context:
        registry.select("does-not-exist")
    assert context.value.code == "unknown_algorithm"

    with pytest.raises(AnalysisError) as context:
        registry.select("baseline", "9.9.9")
    assert context.value.code == "unknown_algorithm"


def test_capabilities_include_source_and_versions() -> None:
    capabilities = discover().capabilities()
    assert len(capabilities) == 1
    item = capabilities[0]
    assert item["id"] == "baseline"
    assert item["source"] == "builtin"
    assert item["available_versions"] == ["1.0.0"]
    assert "needs" in item
    assert "supported_stages" in item


def test_allowlist_filters_and_reports_missing_algorithms() -> None:
    registry = discover(allowlist=["baseline"])
    assert ("baseline", "1.0.0") in registry.algorithms

    missing = discover(allowlist=["not-installed"])
    assert missing.algorithms == {}
    assert any("not installed" in error for error in missing.errors)
