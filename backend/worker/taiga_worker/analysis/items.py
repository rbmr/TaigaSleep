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
        "Time scored as sleep across REM, light and deep sleep. "
        "It excludes wake and unscored time.",
        ("stages",),
    ),
    NumberItem(
        "sleep_latency_s",
        "Time to fall asleep",
        "s",
        "sleep",
        "Time from the first scored 30-second interval to the first interval of sleep. "
        "Unscored time before "
        "it is not counted.",
        ("stages",),
    ),
    NumberItem(
        "sleep_efficiency",
        "Sleep efficiency",
        "%",
        "sleep",
        "Share of the scored recording that was scored as sleep. Total sleep divided by "
        "scored time, unavailable when less than half of the recording could be scored.",
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
        "Wake time at the end of the recording after the last interval of sleep.",
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
        "Beat-to-beat variation: the median of the RMSSD of the valid 5-minute windows. "
        "The RMSSD is the root mean square of successive RR-interval differences; a "
        "difference that touches a removed artifact beat or crosses a break in the R-R "
        "data is not used. A window counts when it has at least 30 clean beats covering "
        "half of it, an artifact share of at most 5% and, when movement was recorded, "
        "movement in at most a fifth of its 30-second intervals.",
        ("hr",),
    ),
    NumberItem(
        "hrv_sdnn_median_ms",
        "HRV SDNN (5-minute median)",
        "ms",
        "heart",
        "Short-term variability: the standard deviation of the RR intervals in each "
        "5-minute window, summarised as the median of the valid windows. It is not a "
        "whole-night SDNN and is not comparable with one. The same window rule as HRV "
        "RMSSD decides which windows count.",
        ("hr",),
    ),
    NumberItem(
        "hrv_ln_rmssd",
        "HRV ln RMSSD",
        "ln(ms)",
        "heart",
        "Natural logarithm of the RMSSD of each valid 5-minute window, summarised as the "
        "median. The logarithm makes beat-to-beat variation comparable across nights and "
        "people. The same window rule as HRV RMSSD decides which windows count.",
        ("hr",),
    ),
    NumberItem(
        "hrv_artifact_share",
        "Artifact share",
        "%",
        "heart_support",
        "Share of all R-R intervals removed as artifacts (ectopic, missed, extra or "
        "otherwise out-of-line beats) before the HRV figures were computed.",
        ("hr",),
    ),
    NumberItem(
        "hrv_window_count",
        "Valid 5-minute windows",
        "count",
        "heart_support",
        "Number of 5-minute windows that met the data, artifact and movement rules and "
        "therefore contributed to the HRV figures.",
        ("hr",),
    ),
    NumberItem(
        "hrv_rr_count",
        "RR intervals used",
        "count",
        "heart_support",
        "Number of clean R-R intervals in the valid 5-minute windows behind the HRV figures.",
        ("hr",),
    ),
    NumberItem(
        "movement_index",
        "Movement index",
        "%",
        "movement",
        "Share of 30-second intervals with movement above 0.08 g, measured from the night's median "
        "accelerometer magnitude.",
        ("acc",),
    ),
    NumberItem(
        "movement_mean_g",
        "Average movement",
        "g",
        "movement",
        "Average movement per 30-second interval, the deviation from the "
        "night's median accelerometer "
        "magnitude.",
        ("acc",),
    ),
    NumberItem(
        "movement_epochs",
        "Intervals with movement",
        "count",
        "movement",
        "Number of 30-second intervals with movement above 0.08 g, measured from the "
        "night's median "
        "accelerometer magnitude.",
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
)

PERCENT_FROM_FRACTION = {"sleep_efficiency", "movement_index", "hrv_artifact_share"}

QUALITY_COMPONENTS = {
    "usable_epoch_coverage": "Usable coverage",
    "artifact_free_rate": "Artifact-free intervals",
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
        "How much of the recording could be used for staging: usable coverage, artifact-free "
        "time and heart-rate coverage, each weighted.",
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
        "is left out and the other components share its weight.",
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
        "explanation": (
            "Mean heart rate for each 30-second interval. "
            "Gaps are intervals without a valid sample."
        ),
        "sources": ["hr"],
    }


def hrv_series_item(hrv: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if not hrv or not hrv.get("count"):
        return None
    return {
        "kind": "series",
        "id": "hrv",
        "label": "HRV by 5-minute window",
        "group": "heart",
        "unit": "ms",
        "series": hrv,
        "explanation": (
            "RMSSD, SDNN, ln RMSSD, mean heart rate and artifact share of every 5-minute "
            "window. A window is left out of the summary figures when it has too little "
            "clean data, an artifact share above 5% or, when movement was recorded, "
            "movement; the reason says which rule did not pass."
        ),
        "sources": ["hr"],
    }


def build_items(
    metrics: Mapping[str, Any],
    support: Mapping[str, Any],
    quality: Mapping[str, Any] | None,
    sleep_quality: Mapping[str, Any] | None,
    hr: Mapping[str, Any] | None,
    hrv: Mapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    items = number_items(metrics, support)
    items.extend(score_items(quality, sleep_quality))
    series = series_item(hr)
    if series is not None:
        items.append(series)
    hrv_series = hrv_series_item(hrv)
    if hrv_series is not None:
        items.append(hrv_series)
    return items
