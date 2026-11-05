"""Typed result items: the numbers, series and scores the app can render without a catalog.

Every item carries its own label, unit, explanation and data sources, so the app does not
need to know the algorithm's metric keys. Stages stay a first-class timeline on the result
itself; they are not squeezed into an item.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping


@dataclass(frozen=True)
class NumberItem:
    key: str
    label: str
    unit: str
    group: str
    explanation: str
    sources: tuple[str, ...]


NUMBER_ITEMS: tuple[NumberItem, ...] = (
    NumberItem(
        "total_sleep_s",
        "Total asleep",
        "s",
        "sleep",
        "Time scored as sleep across REM, light and deep epochs. It excludes wake and unscored time.",
        ("stages",),
    ),
    NumberItem(
        "sleep_latency_s",
        "Time to fall asleep",
        "s",
        "sleep",
        "Time from the start of the recording to the first sleep epoch.",
        ("stages",),
    ),
    NumberItem(
        "sleep_efficiency",
        "Sleep efficiency",
        "%",
        "sleep",
        "Share of the whole recording that was scored as sleep. Total sleep divided by total duration.",
        ("stages",),
    ),
    NumberItem(
        "awakenings",
        "Awakenings",
        "count",
        "sleep",
        "Number of wake periods longer than 2 minutes between sleep onset and the final awakening.",
        ("stages",),
    ),
    NumberItem(
        "waso_s",
        "Wake after sleep onset",
        "s",
        "sleep",
        "Wake time after sleep onset and before the final awakening.",
        ("stages",),
    ),
    NumberItem(
        "final_awakening_s",
        "Final awakening",
        "s",
        "sleep",
        "Wake time at the end of the recording after the last sleep epoch.",
        ("stages",),
    ),
    NumberItem(
        "hr_mean_bpm",
        "Average heart rate",
        "bpm",
        "heart",
        "Mean of all valid heart-rate samples.",
        ("hr",),
    ),
    NumberItem(
        "hr_min_bpm",
        "Lowest heart rate",
        "bpm",
        "heart",
        "Lowest valid heart-rate sample.",
        ("hr",),
    ),
    NumberItem(
        "hr_max_bpm",
        "Highest heart rate",
        "bpm",
        "heart",
        "Highest valid heart-rate sample.",
        ("hr",),
    ),
    NumberItem(
        "hrv_rmssd_ms",
        "HRV RMSSD",
        "ms",
        "heart",
        "Beat-to-beat variation: the root mean square of successive RR-interval differences.",
        ("hr",),
    ),
    NumberItem(
        "hrv_sdnn_ms",
        "HRV SDNN",
        "ms",
        "heart",
        "Overall heart-rate variability: the standard deviation of the RR intervals.",
        ("hr",),
    ),
    NumberItem(
        "hrv_rr_count",
        "RR intervals used",
        "count",
        "heart_support",
        "Number of clean RR intervals used for HRV after filtering.",
        ("hr",),
    ),
    NumberItem(
        "movement_index",
        "Movement index",
        "%",
        "movement",
        "Share of epochs with movement above 0.08 g.",
        ("acc",),
    ),
    NumberItem(
        "movement_mean_g",
        "Average movement",
        "g",
        "movement",
        "Average movement magnitude per epoch.",
        ("acc",),
    ),
    NumberItem(
        "movement_epochs",
        "Epochs with movement",
        "count",
        "movement",
        "Number of epochs with movement above 0.08 g.",
        ("acc",),
    ),
    NumberItem(
        "scored_duration_s",
        "Scored time",
        "s",
        "quality",
        "Time covered by a scored sleep stage.",
        ("stages",),
    ),
    NumberItem(
        "unscored_s",
        "Unscored time",
        "s",
        "quality",
        "Time the algorithm could not score.",
        ("stages",),
    ),
    NumberItem(
        "total_duration_s",
        "Recording length",
        "s",
        "quality",
        "Length of the analyzed recording.",
        ("meta", "stages"),
    ),
)

PERCENT_FROM_FRACTION = {"sleep_efficiency", "movement_index"}

QUALITY_COMPONENTS = {
    "usable_epoch_coverage": "Usable epoch coverage",
    "artifact_free_rate": "Artifact-free epochs",
    "mean_stage_confidence": "Mean stage confidence",
    "hr_coverage": "Heart-rate coverage",
}

SLEEP_SCORE_COMPONENTS = {
    "sleep_efficiency": "Sleep efficiency",
    "waso": "Wake after sleep onset",
    "awakenings_per_hour": "Awakenings per hour",
    "sleep_latency": "Time to fall asleep",
    "restorative_sleep_share": "Restorative sleep share",
}


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def number_items(metrics: Mapping[str, Any], support: Mapping[str, Any]) -> list[dict[str, Any]]:
    """One number item for every metric that is supported and has a value."""
    items: list[dict[str, Any]] = []
    for spec in NUMBER_ITEMS:
        state = support.get(spec.key) or {}
        if state.get("status") != "supported":
            continue
        value = _number(metrics.get(spec.key))
        if value is None:
            continue
        if spec.key in PERCENT_FROM_FRACTION:
            value = round(value * 100.0, 1)
        else:
            value = round(value, 3)
        items.append(
            {
                "kind": "number",
                "id": spec.key,
                "label": spec.label,
                "group": spec.group,
                "unit": spec.unit,
                "value": value,
                "explanation": spec.explanation,
                "sources": list(spec.sources),
            }
        )
    return items


def score_item(
    item_id: str,
    label: str,
    score: Mapping[str, Any] | None,
    component_labels: Mapping[str, str],
    explanation: str,
    sources: list[str],
) -> dict[str, Any] | None:
    """One score item with only the components that actually contributed."""
    if not score or score.get("score") is None:
        return None
    components: list[dict[str, Any]] = []
    for key, raw in (score.get("components") or {}).items():
        if not isinstance(raw, Mapping) or not raw.get("available"):
            continue
        value = _number(raw.get("value"))
        weight = _number(raw.get("weight"))
        if value is None or weight is None:
            continue
        components.append(
            {
                "id": key,
                "label": component_labels.get(key, key),
                "weight": round(weight * 100.0, 1),
                "value": round(value * 100.0, 1),
            }
        )
    return {
        "kind": "score",
        "id": item_id,
        "label": label,
        "group": "score",
        "unit": "%",
        "value": round(float(score["score"]), 1),
        "components": components,
        "explanation": explanation,
        "sources": sources,
    }


def score_items(
    quality: Mapping[str, Any] | None,
    sleep_quality: Mapping[str, Any] | None,
) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    data_quality = score_item(
        "data_quality",
        "Data quality",
        quality,
        QUALITY_COMPONENTS,
        "How much of the recording could be used for staging: epoch coverage, artifact-free "
        "time, stage confidence and heart-rate coverage, each weighted.",
        ["stages", "hr"],
    )
    if data_quality is not None:
        items.append(data_quality)
    sleep_score = score_item(
        "sleep_quality",
        "Sleep score",
        sleep_quality,
        SLEEP_SCORE_COMPONENTS,
        "Weighted average of the sleep figures below. A component whose data is unavailable "
        "is removed and the remaining weights are renormalised.",
        ["stages"],
    )
    if sleep_score is not None:
        items.append(sleep_score)
    return items


def series_item(hr: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if not hr:
        return None
    return {
        "kind": "series",
        "id": "hr",
        "label": "Heart rate",
        "group": "heart",
        "unit": "bpm",
        "series": hr,
        "explanation": "Mean heart rate for each epoch. Gaps are epochs without a valid sample.",
        "sources": ["hr"],
    }


def build_items(
    metrics: Mapping[str, Any],
    support: Mapping[str, Any],
    quality: Mapping[str, Any] | None,
    sleep_quality: Mapping[str, Any] | None,
    hr: Mapping[str, Any] | None,
) -> list[dict[str, Any]]:
    items = number_items(metrics, support)
    items.extend(score_items(quality, sleep_quality))
    series = series_item(hr)
    if series is not None:
        items.append(series)
    return items
