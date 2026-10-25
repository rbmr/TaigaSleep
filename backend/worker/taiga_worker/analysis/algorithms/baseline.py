"""Built-in reference stager.

This is intentionally simple and deterministic. It is useful for proving the
contract, testing the full pipeline and giving HR-only recordings a fallback.
It is **not** clinically validated and must never be presented as a medical
device.
"""

from __future__ import annotations

from typing import Any, Mapping, Sequence

import numpy as np

from ..contract import (
    AASM4_STAGES,
    AlgorithmInfo,
    AnalysisError,
    Recording,
    Stage,
    Stager,
    StagingResult,
    StreamNeed,
)

INFO = AlgorithmInfo(
    id="baseline",
    version="1.0.0",
    name="Baseline HR/ACC",
    description=(
        "Deterministic heart-rate and movement reference stager. "
        "Experimental; not clinically validated."
    ),
    license="GPL-3.0-or-later",
    citation=None,
    homepage="https://github.com/rbmr/TaigaSleep",
    needs=(
        StreamNeed("hr", required=True),
        StreamNeed("acc", required=False),
    ),
    epoch_seconds=30,
    stage_scheme="aasm4",
    supported_stages=AASM4_STAGES,
    capabilities=frozenset({"stages", "confidence", "artifacts"}),
    default_params={
        "wake_movement_g": 0.08,
        "deep_hr_ratio": 0.90,
        "rem_hr_std_bpm": 3.0,
        "rem_movement_g": 0.04,
        "min_contact_fraction": 0.50,
        "min_hr_coverage": 0.50,
    },
    params_schema={
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "wake_movement_g": {"type": "number", "minimum": 0.0},
            "deep_hr_ratio": {"type": "number", "exclusiveMinimum": 0.0, "maximum": 1.0},
            "rem_hr_std_bpm": {"type": "number", "minimum": 0.0},
            "rem_movement_g": {"type": "number", "minimum": 0.0},
            "min_contact_fraction": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "min_hr_coverage": {"type": "number", "minimum": 0.0, "maximum": 1.0},
        },
    },
)


def _params(raw: Mapping[str, Any] | None) -> dict[str, float]:
    merged: dict[str, float] = {
        key: float(value) for key, value in INFO.default_params.items()
    }
    for key, value in (raw or {}).items():
        if key not in merged:
            raise AnalysisError("invalid_params", f"unknown baseline parameter {key!r}")
        try:
            merged[key] = float(value)
        except (TypeError, ValueError) as exc:
            raise AnalysisError("invalid_params", f"parameter {key!r} must be a number") from exc

    if merged["wake_movement_g"] < 0:
        raise AnalysisError("invalid_params", "wake_movement_g must be >= 0")
    if not 0 < merged["deep_hr_ratio"] <= 1:
        raise AnalysisError("invalid_params", "deep_hr_ratio must be in (0, 1]")
    if merged["rem_hr_std_bpm"] < 0 or merged["rem_movement_g"] < 0:
        raise AnalysisError("invalid_params", "rem thresholds must be >= 0")
    for key in ("min_contact_fraction", "min_hr_coverage"):
        if not 0 <= merged[key] <= 1:
            raise AnalysisError("invalid_params", f"{key} must be in [0, 1]")
    return merged


def _clamp(value: float, low: float, high: float) -> float:
    return float(min(high, max(low, value)))


class BaselineStager(Stager):
    info = INFO

    def stage(self, recording: Recording, params: Mapping[str, Any]) -> StagingResult:
        config = _params(params)
        grid = recording.epochs(self.info.epoch_seconds)
        hr = recording.hr
        if hr is None:
            raise AnalysisError("missing_stream", "baseline requires an HR stream")

        warnings: list[str] = []
        valid_hr = hr.hr_bpm[hr.valid & np.isfinite(hr.hr_bpm)]
        if valid_hr.size:
            night_median = float(np.median(valid_hr))
        else:
            night_median = float("nan")
            warnings.append("no valid HR samples; all epochs are unknown")

        has_acc = recording.acc is not None
        if not has_acc:
            warnings.append("no ACC stream; movement rules are disabled")

        stages: list[Stage] = []
        confidence: list[float] = []
        artifacts: list[bool] = []

        for index in range(grid.count):
            hr_coverage = float(grid.coverage["hr"][index])
            contact = float(grid.hr_contact_fraction[index])
            hr_mean = float(grid.hr_mean_bpm[index])
            hr_std = float(grid.hr_std_bpm[index])
            movement = float(grid.movement_g[index]) if has_acc else 0.0
            if not np.isfinite(movement):
                movement = 0.0

            usable = (
                np.isfinite(night_median)
                and np.isfinite(hr_mean)
                and hr_coverage >= config["min_hr_coverage"]
                and contact >= config["min_contact_fraction"]
            )
            if not usable:
                stages.append("unknown")
                confidence.append(0.0)
                artifacts.append(True)
                continue

            ratio = hr_mean / night_median if night_median > 0 else 1.0
            base_confidence = 0.5
            if movement >= config["wake_movement_g"]:
                stage: Stage = "wake"
                base_confidence = 0.7 + 0.25 * min(1.0, movement / max(config["wake_movement_g"] * 2, 1e-6))
            elif ratio >= 1.05 and movement >= config["wake_movement_g"] * 0.25:
                stage = "wake"
                base_confidence = 0.58
            elif ratio <= config["deep_hr_ratio"] and movement <= config["rem_movement_g"]:
                stage = "deep"
                base_confidence = 0.58 + 0.3 * min(
                    1.0, (config["deep_hr_ratio"] - ratio) / max(config["deep_hr_ratio"], 1e-6)
                )
            elif (
                np.isfinite(hr_std)
                and hr_std >= config["rem_hr_std_bpm"]
                and movement <= config["rem_movement_g"]
            ):
                stage = "rem"
                base_confidence = 0.58 + 0.3 * min(
                    1.0, hr_std / max(config["rem_hr_std_bpm"] * 2, 1e-6)
                )
            else:
                stage = "light"

            signal = min(1.0, 0.5 + 0.5 * min(hr_coverage, contact))
            if not has_acc:
                signal *= 0.85
            stages.append(stage)
            confidence.append(round(_clamp(base_confidence * signal, 0.05, 0.99), 3))
            artifacts.append(hr_coverage < 0.5 or contact < 0.5)

        return StagingResult(
            stages=stages,
            confidence=confidence,
            artifacts=artifacts,
            warnings=warnings,
            extras={
                "night_median_hr_bpm": None if not np.isfinite(night_median) else round(night_median, 1),
                "params": config,
                "has_acc": has_acc,
            },
        )


STAGER = BaselineStager()


__all__ = ["INFO", "STAGER", "BaselineStager"]
