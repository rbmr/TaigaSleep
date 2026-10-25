"""One analysis run: load, stage, derive, serialize.

The result shape is deliberately model-agnostic. The worker's job subprocess
calls :func:`run_analysis` and prints :meth:`AnalysisOutput.to_dict` as JSON.
"""

from __future__ import annotations

import hashlib
import json
import platform
import resource
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping, Sequence

import numpy as np

from .contract import (
    CONTRACT_VERSION,
    AnalysisError,
    EpochGrid,
    Recording,
    Stage,
    StagingResult,
    validate_staging_result,
)
from .decode import load_recording
from .derive import derive
from .registry import Registry, discover

STAGE_ORDER: tuple[Stage, ...] = ("wake", "rem", "nrem", "light", "deep", "unknown")


@dataclass
class AnalysisOutput:
    model: str
    model_version: str
    params: dict[str, Any]
    params_hash: str
    stages: dict[str, Any]
    metrics: dict[str, Any]
    metric_support: dict[str, Any]
    quality: dict[str, Any]
    capabilities: dict[str, Any]
    provenance: dict[str, Any]
    extras: dict[str, Any]
    warnings: list[str]
    contract_version: int = CONTRACT_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "contract_version": self.contract_version,
            "model": self.model,
            "model_version": self.model_version,
            "params": self.params,
            "params_hash": self.params_hash,
            "stages": self.stages,
            "metrics": self.metrics,
            "metric_support": self.metric_support,
            "quality": self.quality,
            "capabilities": self.capabilities,
            "provenance": self.provenance,
            "extras": self.extras,
            "warnings": self.warnings,
        }


def params_hash(params: Mapping[str, Any]) -> str:
    canonical = json.dumps(_jsonable(dict(params)), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def _jsonable(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, Mapping):
        return {str(key): _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    return str(value)


def _peak_rss_mb() -> float:
    try:
        value = float(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    except (AttributeError, ValueError):
        return 0.0
    if sys.platform == "darwin":
        value /= 1024.0 * 1024.0
    else:
        value /= 1024.0
    return round(value, 1)


def _confidence_array(result: StagingResult, epoch_count: int) -> list[float | None] | None:
    if result.confidence is not None:
        return [None if value is None else round(float(value), 4) for value in result.confidence]
    if result.probabilities is None:
        return None
    values = np.zeros(epoch_count, dtype=np.float64)
    present = False
    for probabilities in result.probabilities.values():
        array = np.asarray(probabilities, dtype=np.float64)
        if array.size != epoch_count:
            continue
        values = np.maximum(values, np.nan_to_num(array, nan=0.0))
        present = True
    if not present:
        return None
    return [round(float(min(1.0, max(0.0, value))), 4) for value in values]


def _segments(stages: Sequence[Stage]) -> list[dict[str, Any]]:
    if not stages:
        return []
    result: list[dict[str, Any]] = []
    current = stages[0]
    count = 1
    for stage in stages[1:]:
        if stage == current:
            count += 1
        else:
            result.append({"stage": current, "count": count})
            current = stage
            count = 1
    result.append({"stage": current, "count": count})
    return result


def _serialize_stages(
    recording: Recording,
    grid: EpochGrid,
    result: StagingResult,
    confidence: list[float | None] | None,
) -> dict[str, Any]:
    stages = list(result.stages)
    if sum(segment["count"] for segment in _segments(stages)) != grid.count:
        raise AnalysisError("invalid_result", "stage timeline does not cover every epoch")
    probabilities = None
    if result.probabilities is not None:
        probabilities = {
            stage: [round(float(value), 4) for value in values]
            for stage, values in result.probabilities.items()
        }
    return {
        "epoch_seconds": grid.epoch_seconds,
        "start_unix_ms": recording.start_unix_ns // 1_000_000,
        "count": grid.count,
        "segments": _segments(stages),
        "confidence": confidence,
        "artifacts": None if result.artifacts is None else [bool(value) for value in result.artifacts],
        "probabilities": probabilities,
    }


def _required_streams(recording: Recording, info: Any) -> None:
    for need in info.needs:
        if not need.required:
            continue
        stream = getattr(recording, need.name)
        if stream is None:
            raise AnalysisError(
                "missing_stream",
                f"algorithm {info.id!r} requires a {need.name.upper()} stream, but it is missing",
            )
        samples = getattr(stream, "t_s", None)
        if samples is None or len(samples) == 0:
            raise AnalysisError(
                "missing_stream",
                f"algorithm {info.id!r} requires a non-empty {need.name.upper()} stream",
            )
        rate = getattr(stream, "rate_hz", None)
        if rate is None:
            continue
        if need.min_rate_hz is not None and rate < need.min_rate_hz:
            raise AnalysisError(
                "unsupported_rate",
                f"{need.name.upper()} rate {rate:g} Hz is below the algorithm minimum "
                f"{need.min_rate_hz:g} Hz",
            )
        if need.max_rate_hz is not None and rate > need.max_rate_hz:
            raise AnalysisError(
                "unsupported_rate",
                f"{need.name.upper()} rate {rate:g} Hz is above the algorithm maximum "
                f"{need.max_rate_hz:g} Hz",
            )


def run_analysis(
    directory: str,
    model: str,
    params: Mapping[str, Any] | None = None,
    *,
    version: str | None = None,
    registry: Registry | None = None,
    allowlist: Iterable[str] | None = None,
    worker: str | None = None,
    params_hash_override: str | None = None,
) -> AnalysisOutput:
    """Run one stager over one recording directory."""

    started = time.monotonic()
    requested_params = dict(params or {})
    if not isinstance(requested_params, dict):
        raise AnalysisError("invalid_params", "params must be a JSON object")

    registry = registry or discover(allowlist)
    requested_version = None if version in (None, "", "latest", "unversioned") else version
    registered = registry.select(model, requested_version)
    info = registered.info

    recording = load_recording(directory)
    if recording.duration_s < info.epoch_seconds:
        raise AnalysisError(
            "recording_too_short",
            f"recording is {recording.duration_s:g}s; {info.id} needs at least "
            f"{info.epoch_seconds}s",
        )
    _required_streams(recording, info)

    grid = recording.epochs(info.epoch_seconds)
    try:
        result = registered.stager.stage(recording, requested_params)
    except AnalysisError:
        raise
    except Exception as exc:  # noqa: BLE001 - turn a plugin crash into a job failure
        raise AnalysisError(
            "algorithm_failed",
            f"{registered.info.id} failed: {type(exc).__name__}: {exc}",
        ) from exc

    validate_staging_result(result, epoch_count=grid.count, info=info)
    confidence = _confidence_array(result, grid.count)
    derived = derive(
        recording,
        grid,
        result.stages,
        info,
        confidence=confidence,
        artifacts=result.artifacts,
        beats_s=result.beats_s,
    )
    serialized_stages = _serialize_stages(recording, grid, result, confidence)
    runtime_s = round(time.monotonic() - started, 3)

    capabilities = {
        "stages": "stages" in info.capabilities,
        "confidence": confidence is not None,
        "probabilities": result.probabilities is not None,
        "artifacts": result.artifacts is not None,
        "beats": result.beats_s is not None,
        "stage_scheme": info.stage_scheme,
        "supported_stages": list(info.supported_stages),
        "needs": [need.name for need in info.needs],
    }
    warnings = list(dict.fromkeys(
        list(recording.quality.warnings) + list(result.warnings)
    ))
    algorithm_provenance = {
        "id": info.id,
        "version": info.version,
        **{str(key): _jsonable(value) for key, value in result.provenance.items()},
    }
    provenance = {
        "contract_version": CONTRACT_VERSION,
        "algorithm": algorithm_provenance,
        "algorithm_source": registered.source,
        "license": info.license,
        "citation": info.citation,
        "homepage": info.homepage,
        "worker": worker,
        "runtime_s": runtime_s,
        "peak_rss_mb": _peak_rss_mb(),
        "python": platform.python_version(),
        "numpy": np.__version__,
        "registry_errors": list(registry.errors),
        "recording": {
            "session_id": recording.meta.session_id,
            "state": recording.meta.state,
            "start_unix_ms": recording.meta.start_utc_ms,
            "end_unix_ms": recording.end_unix_ns // 1_000_000,
            "duration_s": round(recording.duration_s, 3),
            "streams": {
                name: {
                    "samples": report.samples,
                    "rate_hz": None if report.rate_hz is None else round(report.rate_hz, 3),
                    "coverage": round(report.coverage, 4),
                    "truncated_tail": report.truncated_tail,
                }
                for name, report in recording.quality.streams.items()
            },
        },
        "input_quality": recording.quality.to_dict(),
    }

    return AnalysisOutput(
        model=info.id,
        model_version=info.version,
        params=_jsonable(requested_params),
        params_hash=params_hash_override or params_hash(requested_params),
        stages=serialized_stages,
        metrics=derived.metrics,
        metric_support=derived.support,
        quality=derived.quality,
        capabilities=capabilities,
        provenance=provenance,
        extras=_jsonable(dict(result.extras)),
        warnings=warnings,
    )


__all__ = ["AnalysisOutput", "STAGE_ORDER", "params_hash", "run_analysis"]
