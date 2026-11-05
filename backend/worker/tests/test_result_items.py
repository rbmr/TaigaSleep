from __future__ import annotations

import re
from pathlib import Path

from taiga_worker.analysis.pipeline import run_analysis
from tests import analysis_fixture as fx

REPO_ROOT = Path(__file__).resolve().parents[3]
CATALOG = REPO_ROOT / "app/src/main/java/com/rbmr/taigasleep/insights/MetricCatalog.kt"


def catalog_ids() -> set[str]:
    """The figure ids the app currently lists; every one must exist as an item."""
    text = CATALOG.read_text(encoding="utf-8")
    return set(re.findall(r'MetricSpec\("([a-z0-9_]+)"', text))


def item_ids(payload: dict) -> set[str]:
    return {item["id"] for item in payload["items"]}


def test_every_app_metric_is_an_item(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=240)
    payload = run_analysis(tmp_path, "baseline", {}).to_dict()

    expected = catalog_ids() | {"hr", "sleep_quality", "data_quality"}
    missing = expected - item_ids(payload)

    assert not missing, f"no item for {sorted(missing)}"


def test_an_unsupported_figure_is_absent_instead_of_null(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120, include_acc=False)
    payload = run_analysis(tmp_path, "baseline", {}).to_dict()

    assert "movement_index" not in item_ids(payload)
    assert all(
        item.get("value") is not None
        for item in payload["items"]
        if item["kind"] == "number"
    )
