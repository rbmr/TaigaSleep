"""Shared metrics and quality derivation.

These functions are intentionally independent of the stager. They take the
canonical stage timeline plus the normalized recording and produce comparable
numbers. A stager only supplies stages, confidence and artifacts. The heart-rate
variability figures are the exception the other way round: they are computed from
the raw R-R stream alone and read nothing the stager produced.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import numpy as np

from .contract import (
    AlgorithmInfo,
    EpochGrid,
    Recording,
    Stage,
)
from .epochs import MOVEMENT_THRESHOLD_G
from .hrv import hrv_metrics, window_series

SLEEP_STAGES: tuple[Stage, ...] = ("rem", "nrem", "light", "deep", "sleep")
TIME_STAGES: tuple[Stage, ...] = ("wake", "rem", "nrem", "light", "deep", "sleep")
# Stage confidence says how sure a stager is, not how good the data is, and it is uncalibrated
# (a maximum softmax or a fixed constant), so it is not part of the data-quality score. The
# remaining weights are the former 0.35/0.25/0.15 renormalised over their sum of 0.75.
QUALITY_WEIGHTS = {
    "usable_epoch_coverage": 0.4667,
    "artifact_free_rate": 0.3333,
    "hr_coverage": 0.2,
}
QUALITY_WEIGHTS_VERSION = 2
# Below this data-quality score the sleep score rests on too little usable data to mean anything,
# so it is withheld with the reason below instead of being shown anyway.
MIN_QUALITY_FOR_SLEEP_SCORE = 50.0
NOT_ENOUGH_GOOD_DATA = "not_enough_good_data"
# A transparent, versioned product policy over the sleep figures. Each component
# is scaled to 0..1 where higher is better; unavailable components are removed and
# the remaining weights are renormalized. The score deliberately has no target
# sleep duration: duration is reported as its own figure instead.
SLEEP_SCORE_WEIGHTS = {
    "sleep_efficiency": 0.30,
    "waso": 0.20,
    "awakenings_per_hour": 0.20,
    "sleep_latency": 0.15,
    "restorative_sleep_share": 0.15,
}
SLEEP_SCORE_VERSION = 1
WASO_ZERO_SCORE_SECONDS = 7200.0
AWAKENINGS_PER_HOUR_ZERO_SCORE = 6.0
SLEEP_LATENCY_ZERO_SCORE_SECONDS = 3600.0
RESTORATIVE_SHARE_FULL_SCORE = 0.5
USABLE_COVERAGE_THRESHOLD = 0.5
USABLE_CONTACT_THRESHOLD = 0.5
ARTIFACT_COVERAGE_THRESHOLD = 0.5
ARTIFACT_CONTACT_THRESHOLD = 0.5
# Efficiency is a share of the time the algorithm could score. Below this share of
# the recording, that share is not representative and efficiency is not reported.
MIN_SCORED_SHARE_FOR_EFFICIENCY = 0.5
# A wake period is only an awakening when it lasts longer than two minutes.
AWAKENING_MIN_SECONDS = 120.0


@dataclass(frozen=True)
class Derived:
    metrics: dict[str, Any]
    support: dict[str, Any]
    quality: dict[str, Any]
    sleep_quality: dict[str, Any]
    hrv_windows: dict[str, Any] | None = None


def _support(status: str, reason: str | None = None) -> dict[str, Any]:
    return {"status": status, "reason": reason}


def _stage_support(stage: Stage, supported: set[Stage], reason: str) -> dict[str, Any]:
    return (
        _support("supported") if stage in supported else _support("unsupported_algorithm", reason)
    )


def _segments(mask: np.ndarray) -> list[tuple[int, int]]:
    """Half-open [start, end) runs of True."""

    if mask.size == 0:
        return []
    padded = np.concatenate(([False], mask.astype(bool), [False]))
    changes = np.diff(padded.astype(np.int8))
    starts = np.nonzero(changes == 1)[0]
    ends = np.nonzero(changes == -1)[0]
    return list(zip(starts.tolist(), ends.tolist(), strict=True))


def _hr_metrics(recording: Recording) -> tuple[dict[str, Any], dict[str, Any]]:
    hr = recording.hr
    if hr is None:
        missing = _support("unsupported_device", "no_hr_stream")
        return (
            {"hr_mean_bpm": None, "hr_min_bpm": None, "hr_max_bpm": None},
            {"hr_mean_bpm": missing, "hr_min_bpm": missing, "hr_max_bpm": missing},
        )
    valid = hr.valid & np.isfinite(hr.hr_bpm)
    values = hr.hr_bpm[valid].astype(np.float64)
    if values.size == 0:
        missing = _support("insufficient_data", "no_valid_hr_samples")
        return (
            {"hr_mean_bpm": None, "hr_min_bpm": None, "hr_max_bpm": None},
            {"hr_mean_bpm": missing, "hr_min_bpm": missing, "hr_max_bpm": missing},
        )
    metrics = {
        "hr_mean_bpm": round(float(np.mean(values)), 1),
        "hr_min_bpm": round(float(np.min(values)), 1),
        "hr_max_bpm": round(float(np.max(values)), 1),
    }
    supported = _support("supported")
    return metrics, {key: supported for key in metrics}


def _movement_metrics(
    recording: Recording, grid: EpochGrid
) -> tuple[dict[str, Any], dict[str, Any]]:
    if recording.acc is None:
        missing = _support("unsupported_device", "no_acc_stream")
        return (
            {"movement_index": None, "movement_mean_g": None, "movement_epochs": None},
            {
                "movement_index": missing,
                "movement_mean_g": missing,
                "movement_epochs": missing,
            },
        )
    valid = grid.acc_sample_count > 0
    if not np.any(valid):
        missing = _support("insufficient_data", "no_acc_samples")
        return (
            {"movement_index": None, "movement_mean_g": None, "movement_epochs": None},
            {
                "movement_index": missing,
                "movement_mean_g": missing,
                "movement_epochs": missing,
            },
        )
    values = grid.movement_g[valid]
    values = values[np.isfinite(values)]
    if values.size == 0:
        missing = _support("insufficient_data", "no_acc_samples")
        return (
            {"movement_index": None, "movement_mean_g": None, "movement_epochs": None},
            {
                "movement_index": missing,
                "movement_mean_g": missing,
                "movement_epochs": missing,
            },
        )
    moving = int(np.sum(values > MOVEMENT_THRESHOLD_G))
    metrics = {
        "movement_index": round(moving / values.size, 4),
        "movement_mean_g": round(float(np.mean(values)), 4),
        "movement_epochs": moving,
    }
    supported = _support("supported")
    return metrics, {key: supported for key in metrics}


def derive(
    recording: Recording,
    grid: EpochGrid,
    stages: Sequence[Stage],
    info: AlgorithmInfo,
    *,
    artifacts: Sequence[bool] | None = None,
    supported_stages: Sequence[Stage] | None = None,
) -> Derived:
    if len(stages) != grid.count:
        raise ValueError("stage count does not match the interval grid")

    stage_values = np.asarray(stages, dtype=object)
    epoch_seconds = grid.epoch_seconds
    supported = set(supported_stages or info.supported_stages)

    counts = {stage: int(np.sum(stage_values == stage)) for stage in TIME_STAGES}
    counts["unknown"] = int(np.sum(stage_values == "unknown"))
    scored_count = int(np.sum(stage_values != "unknown"))
    total_duration = float(grid.count * epoch_seconds)
    scored_duration = float(scored_count * epoch_seconds)
    unscored = float(counts["unknown"] * epoch_seconds)

    sleep_duration = float(sum(counts[s] for s in SLEEP_STAGES if s in supported) * epoch_seconds)
    scored_share = 0.0 if total_duration <= 0 else scored_duration / total_duration
    if scored_share < MIN_SCORED_SHARE_FOR_EFFICIENCY:
        efficiency = None
        efficiency_support = _support("insufficient_data", "scored_time_below_half_of_recording")
    else:
        efficiency = round(sleep_duration / scored_duration, 4)
        efficiency_support = _support("supported")

    sleep_mask = np.isin(stage_values, [s for s in SLEEP_STAGES if s in supported])
    sleep_indices = np.nonzero(sleep_mask)[0]
    scored_indices = np.nonzero(stage_values != "unknown")[0]
    if sleep_indices.size:
        first_sleep = int(sleep_indices[0])
        last_sleep = int(sleep_indices[-1])
        first_scored = int(scored_indices[0])
        latency = float(max(0, first_sleep - first_scored) * epoch_seconds)
        final_awakening = float((grid.count - (last_sleep + 1)) * epoch_seconds)
        wake_mask = stage_values == "wake"
        wake_between = wake_mask.copy()
        wake_between[: first_sleep + 1] = False
        wake_between[last_sleep:] = False
        waso = float(np.sum(wake_between) * epoch_seconds)
        awakenings = 0
        for start, end in _segments(wake_between):
            if (end - start) * epoch_seconds > AWAKENING_MIN_SECONDS:
                awakenings += 1
    else:
        latency = None
        final_awakening = None
        waso = None
        awakenings = None

    time_per_stage: dict[str, Any] = {}
    stage_support: dict[str, Any] = {}
    for stage in TIME_STAGES:
        key = f"time_in_{stage}_s"
        if stage in supported:
            time_per_stage[key] = float(counts[stage] * epoch_seconds)
            stage_support[key] = _support("supported")
        else:
            time_per_stage[key] = None
            stage_support[key] = _stage_support(
                stage, supported, f"algorithm_does_not_distinguish_{stage}"
            )

    hr_metrics, hr_support = _hr_metrics(recording)
    hrv = hrv_metrics(recording)
    hrv_windows = window_series(recording, hrv.windows)
    movement_metrics, movement_support = _movement_metrics(recording, grid)

    stage_support_status = "supported" if scored_count > 0 else "insufficient_data"
    metrics: dict[str, Any] = {
        "total_duration_s": total_duration,
        "scored_duration_s": scored_duration,
        "unscored_s": unscored,
        "total_sleep_s": sleep_duration,
        "sleep_efficiency": efficiency,
        "sleep_latency_s": latency,
        "waso_s": waso,
        "awakenings": awakenings,
        "final_awakening_s": final_awakening,
        **time_per_stage,
        **hr_metrics,
        **hrv.metrics,
        **movement_metrics,
    }

    support: dict[str, Any] = {}
    for key in ("scored_duration_s", "unscored_s"):
        support[key] = _support("supported")
    for key in (
        "total_sleep_s",
        "sleep_latency_s",
        "waso_s",
        "awakenings",
        "final_awakening_s",
    ):
        support[key] = _support(
            stage_support_status,
            None if stage_support_status == "supported" else "no_scored_epochs",
        )
    support["sleep_efficiency"] = efficiency_support
    support.update(stage_support)
    support.update(hr_support)
    support.update(hrv.support)
    support.update(movement_support)

    quality = _quality(
        recording,
        grid,
        stage_values,
        info,
        artifacts=artifacts,
    )
    sleep_quality = _sleep_quality(
        counts=counts,
        supported=supported,
        epoch_seconds=epoch_seconds,
        sleep_duration=sleep_duration,
        efficiency=efficiency,
        latency=latency,
        waso=waso,
        awakenings=awakenings,
        quality_score=quality["score"],
    )
    support["sleep_quality"] = sleep_quality["support"]

    return Derived(
        metrics=metrics,
        support=support,
        quality=quality,
        sleep_quality=sleep_quality,
        hrv_windows=hrv_windows,
    )


def _sleep_score_value(value: float, zero_at: float) -> float:
    """Scale a lower-is-better figure to 0..1, reaching 0 at ``zero_at``."""

    if zero_at <= 0:
        return 0.0
    return round(float(min(1.0, max(0.0, 1.0 - value / zero_at))), 4)


def _sleep_quality(
    *,
    counts: Mapping[Stage, int],
    supported: set[Stage],
    epoch_seconds: int,
    sleep_duration: float,
    efficiency: float | None,
    latency: float | None,
    waso: float | None,
    awakenings: int | None,
    quality_score: float | None,
) -> dict[str, Any]:
    sleep_hours = sleep_duration / 3600.0
    restorative_seconds: float | None = None
    if "deep" in supported and "rem" in supported and sleep_duration > 0:
        restorative_seconds = float((counts["deep"] + counts["rem"]) * epoch_seconds)

    values: dict[str, float | None] = {
        "sleep_efficiency": None if efficiency is None else round(float(efficiency), 4),
        "waso": None if waso is None else _sleep_score_value(waso, WASO_ZERO_SCORE_SECONDS),
        "awakenings_per_hour": (
            None
            if awakenings is None or sleep_hours <= 0
            else _sleep_score_value(awakenings / sleep_hours, AWAKENINGS_PER_HOUR_ZERO_SCORE)
        ),
        "sleep_latency": (
            None
            if latency is None
            else _sleep_score_value(latency, SLEEP_LATENCY_ZERO_SCORE_SECONDS)
        ),
        "restorative_sleep_share": (
            None
            if restorative_seconds is None
            else round(
                min(1.0, restorative_seconds / sleep_duration / RESTORATIVE_SHARE_FULL_SCORE),
                4,
            )
        ),
    }

    components: dict[str, Any] = {}
    notes: list[str] = []
    for name, value in values.items():
        weight = SLEEP_SCORE_WEIGHTS[name]
        if value is None:
            components[name] = {"value": None, "weight": weight, "available": False}
            notes.append(f"{name} is unavailable, so the other components share its weight")
        else:
            components[name] = {
                "value": round(float(min(1.0, max(0.0, value))), 4),
                "weight": weight,
                "available": True,
            }

    available_weight = sum(
        component["weight"] for component in components.values() if component["available"]
    )
    score = None
    if available_weight > 0:
        score = (
            100.0
            * sum(
                component["value"] * component["weight"]
                for component in components.values()
                if component["available"]
            )
            / available_weight
        )

    # A score computed from too little good data would read as a statement about the night
    # rather than about the recording, so it is withheld and the reason is recorded instead.
    if quality_score is not None and quality_score < MIN_QUALITY_FOR_SLEEP_SCORE:
        score = None
        notes.append(
            f"no sleep score because data quality {quality_score} is below "
            f"{MIN_QUALITY_FOR_SLEEP_SCORE}"
        )

    return {
        "score": None if score is None else round(score, 1),
        "components_version": SLEEP_SCORE_VERSION,
        "components": components,
        "notes": notes,
        "support": (
            _support("supported")
            if score is not None
            else _support("insufficient_data", NOT_ENOUGH_GOOD_DATA)
        ),
    }


def _quality(
    recording: Recording,
    grid: EpochGrid,
    stages: np.ndarray,
    info: AlgorithmInfo,
    *,
    artifacts: Sequence[bool] | None,
) -> dict[str, Any]:
    required = [need.name for need in info.needs if need.required]
    usable = np.ones(grid.count, dtype=bool)
    for name in required:
        coverage = grid.coverage.get(name)
        if coverage is None:
            usable[:] = False
            continue
        usable &= coverage >= USABLE_COVERAGE_THRESHOLD
        if name == "hr":
            usable &= grid.hr_contact_fraction >= USABLE_CONTACT_THRESHOLD
        if name == "ecg":
            usable &= grid.ecg_saturated_fraction <= 0.5

    derived_artifact = np.zeros(grid.count, dtype=bool)
    for name in required:
        coverage = grid.coverage.get(name)
        if coverage is not None:
            derived_artifact |= coverage < ARTIFACT_COVERAGE_THRESHOLD
    if "hr" in required:
        derived_artifact |= grid.hr_contact_fraction < ARTIFACT_CONTACT_THRESHOLD
    if "ecg" in required:
        derived_artifact |= grid.ecg_saturated_fraction > 0.5
    if artifacts is not None:
        algorithm_artifact = np.asarray(artifacts, dtype=bool)
        if algorithm_artifact.size != grid.count:
            raise ValueError("artifact count does not match the interval grid")
        artifact = derived_artifact | algorithm_artifact
    else:
        artifact = derived_artifact

    usable_coverage = float(np.mean(usable)) if usable.size else 0.0
    artifact_free = float(1.0 - np.mean(artifact)) if artifact.size else None

    hr_coverage: float | None = None
    if recording.hr is not None and "hr" in grid.coverage:
        hr_coverage = float(np.mean(grid.coverage["hr"]))

    components: dict[str, Any] = {}
    values = {
        "usable_epoch_coverage": usable_coverage,
        "artifact_free_rate": artifact_free,
        "hr_coverage": hr_coverage,
    }
    notes: list[str] = []
    for name, value in values.items():
        weight = QUALITY_WEIGHTS[name]
        if value is None:
            components[name] = {"value": None, "weight": weight, "available": False}
            notes.append(f"{name} is unavailable, so the other components share its weight")
        else:
            components[name] = {
                "value": round(float(min(1.0, max(0.0, value))), 4),
                "weight": weight,
                "available": True,
            }

    available_weight = sum(
        component["weight"] for component in components.values() if component["available"]
    )
    if available_weight <= 0:
        score = None
    else:
        score = (
            100.0
            * sum(
                component["value"] * component["weight"]
                for component in components.values()
                if component["available"]
            )
            / available_weight
        )

    return {
        "score": None if score is None else round(score, 1),
        "weights_version": QUALITY_WEIGHTS_VERSION,
        "components": components,
        "notes": notes,
    }


__all__ = [
    "Derived",
    "derive",
    "QUALITY_WEIGHTS",
    "QUALITY_WEIGHTS_VERSION",
    "MIN_QUALITY_FOR_SLEEP_SCORE",
    "NOT_ENOUGH_GOOD_DATA",
    "AWAKENING_MIN_SECONDS",
    "MIN_SCORED_SHARE_FOR_EFFICIENCY",
    "SLEEP_SCORE_WEIGHTS",
    "SLEEP_SCORE_VERSION",
]
