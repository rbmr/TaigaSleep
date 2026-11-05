from __future__ import annotations

from pathlib import Path

from taiga_worker.analysis.pipeline import run_analysis
from tests import analysis_fixture as fx

# The figures the app renders. This is the contract of docs/analysis-contract.md; the app no
# longer has a metric catalog of its own.
FIGURE_IDS = {
    "total_sleep_s",
    "sleep_latency_s",
    "sleep_efficiency",
    "awakenings",
    "waso_s",
    "final_awakening_s",
    "hr_mean_bpm",
    "hr_min_bpm",
    "hr_max_bpm",
    "hrv_rmssd_ms",
    "hrv_sdnn_ms",
    "hrv_rr_count",
    "movement_index",
    "movement_mean_g",
    "movement_epochs",
    "scored_duration_s",
    "unscored_s",
    "total_duration_s",
    "hr",
    "sleep_quality",
    "data_quality",
}


def item_ids(payload: dict) -> set[str]:
    return {item["id"] for item in payload["items"]}


def test_every_figure_the_app_shows_is_an_item(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=240)
    payload = run_analysis(tmp_path, "baseline", {}).to_dict()

    missing = FIGURE_IDS - item_ids(payload)
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
