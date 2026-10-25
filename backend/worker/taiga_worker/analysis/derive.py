"""Shared metrics and quality derivation.

These functions are intentionally independent of the stager. They take the
canonical stage timeline plus the normalized recording and produce comparable
numbers. A stager only supplies stages, confidence, artifacts and optional beat
times.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import numpy as np

from .contract import (
    AlgorithmInfo,
    EpochGrid,
    Recording,
    Stage,
)

SLEEP_STAGES: tuple[Stage, ...] = ("rem", "nrem", "light", "deep", "sleep")
TIME_STAGES: tuple[Stage, ...] = ("wake", "rem", "nrem", "light", "deep", "sleep")
QUALITY_WEIGHTS = {
    "usable_epoch_coverage": 0.35,
    "artifact_free_rate": 0.25,
    "mean_stage_confidence": 0.25,
    "hr_coverage": 0.15,
}
QUALITY_WEIGHTS_VERSION = 1
USABLE_COVERAGE_THRESHOLD = 0.5
USABLE_CONTACT_THRESHOLD = 0.5
ARTIFACT_COVERAGE_THRESHOLD = 0.5
ARTIFACT_CONTACT_THRESHOLD = 0.5
MIN_RR_INTERVALS = 120
RR_MIN_MS = 300.0
RR_MAX_MS = 2000.0
MOVEMENT_THRESHOLD_G = 0.08


@dataclass(frozen=True)
class Derived:
    metrics: dict[str, Any]
    support: dict[str, Any]
    quality: dict[str, Any]


def _support(status: str, reason: str | None = None) -> dict[str, Any]:
    return {"status": status, "reason": reason}


def _stage_support(stage: Stage, info: AlgorithmInfo, reason: str) -> dict[str, Any]:
    return _support("supported") if stage in info.supported_stages else _support("unsupported_algorithm", reason)


def _segments(mask: np.ndarray) -> list[tuple[int, int]]:
    """Half-open [start, end) runs of True."""

    if mask.size == 0:
        return []
    padded = np.concatenate(([False], mask.astype(bool), [False]))
    changes = np.diff(padded.astype(np.int8))
    starts = np.nonzero(changes == 1)[0]
    ends = np.nonzero(changes == -1)[0]
    return list(zip(starts.tolist(), ends.tolist()))


def _clean_rr(rr_ms: np.ndarray) -> np.ndarray:
    rr = np.asarray(rr_ms, dtype=np.float64)
    rr = rr[np.isfinite(rr)]
    rr = rr[(rr >= RR_MIN_MS) & (rr <= RR_MAX_MS)]
    if rr.size >= 20:
        median = float(np.median(rr))
        if median > 0:
            rr = rr[np.abs(rr - median) <= 0.5 * median]
    return rr


def _hrv_values(recording: Recording, beats_s: Sequence[float] | None) -> tuple[np.ndarray | None, str | None]:
    if beats_s is not None:
        beats = np.asarray(beats_s, dtype=np.float64)
        if beats.size >= 2:
            return _clean_rr(np.diff(beats) * 1000.0), None
    hr = recording.hr
    if hr is None:
        return None, "no_hr_stream"
    if not hr.rr_ms:
        return None, "no_rr_intervals"
    rr = np.concatenate([np.asarray(values, dtype=np.float64) for values in hr.rr_ms])
    if rr.size == 0:
        return None, "no_rr_intervals"
    return _clean_rr(rr), None


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


def _hrv_metrics(recording: Recording, beats_s: Sequence[float] | None) -> tuple[dict[str, Any], dict[str, Any]]:
    rr, reason = _hrv_values(recording, beats_s)
    if rr is None or rr.size < MIN_RR_INTERVALS:
        status = "unsupported_device" if reason == "no_hr_stream" else "insufficient_data"
        if reason is None:
            reason = f"need_at_least_{MIN_RR_INTERVALS}_rr_intervals"
        missing = _support(status, reason)
        return (
            {"hrv_rmssd_ms": None, "hrv_sdnn_ms": None, "hrv_rr_count": int(0 if rr is None else rr.size)},
            {"hrv_rmssd_ms": missing, "hrv_sdnn_ms": missing, "hrv_rr_count": missing},
        )
    diffs = np.diff(rr)
    rmssd = float(np.sqrt(np.mean(diffs**2))) if diffs.size else 0.0
    sdnn = float(np.std(rr, ddof=1)) if rr.size > 1 else 0.0
    metrics = {
        "hrv_rmssd_ms": round(rmssd, 1),
        "hrv_sdnn_ms": round(sdnn, 1),
        "hrv_rr_count": int(rr.size),
    }
    supported = _support("supported")
    return metrics, {key: supported for key in metrics}


def _movement_metrics(recording: Recording, grid: EpochGrid) -> tuple[dict[str, Any], dict[str, Any]]:
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
    confidence: Sequence[float | None] | None = None,
    artifacts: Sequence[bool] | None = None,
    beats_s: Sequence[float] | None = None,
) -> Derived:
    if len(stages) != grid.count:
        raise ValueError("stage count does not match the epoch grid")

    stage_values = np.asarray(stages, dtype=object)
    epoch_seconds = grid.epoch_seconds
    supported = set(info.supported_stages)

    counts = {stage: int(np.sum(stage_values == stage)) for stage in TIME_STAGES}
    counts["unknown"] = int(np.sum(stage_values == "unknown"))
    scored_count = int(np.sum(stage_values != "unknown"))
    total_duration = float(grid.count * epoch_seconds)
    scored_duration = float(scored_count * epoch_seconds)
    unscored = float(counts["unknown"] * epoch_seconds)

    sleep_duration = float(sum(counts[s] for s in SLEEP_STAGES if s in supported) * epoch_seconds)
    efficiency = None if total_duration <= 0 else round(sleep_duration / total_duration, 4)

    sleep_mask = np.isin(stage_values, [s for s in SLEEP_STAGES if s in supported])
    sleep_indices = np.nonzero(sleep_mask)[0]
    if sleep_indices.size:
        first_sleep = int(sleep_indices[0])
        last_sleep = int(sleep_indices[-1])
        latency = float(first_sleep * epoch_seconds)
        final_awakening = float((grid.count - (last_sleep + 1)) * epoch_seconds)
        wake_mask = stage_values == "wake"
        wake_between = wake_mask.copy()
        wake_between[: first_sleep + 1] = False
        wake_between[last_sleep:] = False
        waso = float(np.sum(wake_between) * epoch_seconds)
        awakenings = 0
        for start, end in _segments(wake_between):
            if end - start >= 1:
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
            stage_support[key] = _stage_support(stage, info, f"algorithm_does_not_distinguish_{stage}")

    hr_metrics, hr_support = _hr_metrics(recording)
    hrv_metrics, hrv_support = _hrv_metrics(recording, beats_s)
    movement_metrics, movement_support = _movement_metrics(recording, grid)

    stage_metric_support = "supported" if scored_count > 0 else "insufficient_data"
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
        **hrv_metrics,
        **movement_metrics,
    }

    support: dict[str, Any] = {}
    for key in ("total_duration_s", "scored_duration_s", "unscored_s"):
        support[key] = _support("supported")
    for key in (
        "total_sleep_s",
        "sleep_efficiency",
        "sleep_latency_s",
        "waso_s",
        "awakenings",
        "final_awakening_s",
    ):
        support[key] = _support(
            stage_metric_support,
            None if stage_metric_support == "supported" else "no_scored_epochs",
        )
    support.update(stage_support)
    support.update(hr_support)
    support.update(hrv_support)
    support.update(movement_support)

    quality = _quality(
        recording,
        grid,
        stage_values,
        info,
        confidence=confidence,
        artifacts=artifacts,
    )

    return Derived(metrics=metrics, support=support, quality=quality)


def _quality(
    recording: Recording,
    grid: EpochGrid,
    stages: np.ndarray,
    info: AlgorithmInfo,
    *,
    confidence: Sequence[float | None] | None,
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
            raise ValueError("artifact count does not match the epoch grid")
        artifact = derived_artifact | algorithm_artifact
    else:
        artifact = derived_artifact

    usable_coverage = float(np.mean(usable)) if usable.size else 0.0
    artifact_free = float(1.0 - np.mean(artifact)) if artifact.size else None

    confidence_values: np.ndarray | None = None
    if confidence is not None:
        raw = np.asarray([np.nan if value is None else float(value) for value in confidence])
        if raw.size != grid.count:
            raise ValueError("confidence count does not match the epoch grid")
        finite = raw[np.isfinite(raw)]
        if finite.size:
            confidence_values = finite

    hr_coverage: float | None = None
    if recording.hr is not None and "hr" in grid.coverage:
        hr_coverage = float(np.mean(grid.coverage["hr"]))

    components: dict[str, Any] = {}
    values = {
        "usable_epoch_coverage": usable_coverage,
        "artifact_free_rate": artifact_free,
        "mean_stage_confidence": None if confidence_values is None else float(np.mean(confidence_values)),
        "hr_coverage": hr_coverage,
    }
    notes: list[str] = []
    for name, value in values.items():
        weight = QUALITY_WEIGHTS[name]
        if value is None:
            components[name] = {"value": None, "weight": weight, "available": False}
            notes.append(f"{name} unavailable; its weight was renormalized")
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
        score = 100.0 * sum(
            component["value"] * component["weight"]
            for component in components.values()
            if component["available"]
        ) / available_weight

    return {
        "score": None if score is None else round(score, 1),
        "weights_version": QUALITY_WEIGHTS_VERSION,
        "components": components,
        "notes": notes,
    }


__all__ = ["Derived", "derive", "QUALITY_WEIGHTS", "QUALITY_WEIGHTS_VERSION"]
