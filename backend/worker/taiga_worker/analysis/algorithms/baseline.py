"""Built-in reference stager.

This is intentionally simple and deterministic. It is useful for proving the
contract, testing the full pipeline and giving HR-only recordings a fallback.
It reports wake and sleep only: neither heart rate nor movement can separate
deep, light and REM sleep, so it does not pretend to. It is **not** clinically
validated and must never be presented as a medical device.
"""

from __future__ import annotations

from typing import Any, Mapping

import numpy as np

from ..contract import (
    WAKE_SLEEP_STAGES,
    AlgorithmInfo,
    AnalysisError,
    Recording,
    Stage,
    Stager,
    StagingResult,
    StreamNeed,
)
from ..smoothing import smooth_labels

INFO = AlgorithmInfo(
    id="baseline",
    version="1.2.0",
    name="Baseline HR/ACC",
    description=(
        "Deterministic heart-rate and movement reference stager that reports wake and "
        "sleep only. Experimental; not clinically validated."
    ),
    license="GPL-3.0-or-later",
    citation=None,
    homepage="https://github.com/rbmr/TaigaSleep",
    needs=(
        StreamNeed("hr", required=True),
        StreamNeed("acc", required=False),
    ),
    epoch_seconds=30,
    stage_scheme="wake-sleep",
    supported_stages=WAKE_SLEEP_STAGES,
    capabilities=frozenset({"stages", "artifacts"}),
    default_params={
        "wake_movement_g": 0.08,
        "min_contact_fraction": 0.50,
        "min_hr_coverage": 0.50,
        "smooth_window_epochs": 7,
    },
    params_schema={
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "wake_movement_g": {"type": "number", "minimum": 0.0},
            "min_contact_fraction": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "min_hr_coverage": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "smooth_window_epochs": {"type": "integer", "minimum": 1, "maximum": 21},
        },
    },
)


def _params(raw: Mapping[str, Any] | None) -> dict[str, Any]:
    merged: dict[str, Any] = dict(INFO.default_params)
    for key, value in (raw or {}).items():
        if key not in merged:
            raise AnalysisError("invalid_params", f"unknown baseline parameter {key!r}")
        merged[key] = value

    for key in (
        "wake_movement_g",
        "min_contact_fraction",
        "min_hr_coverage",
    ):
        try:
            merged[key] = float(merged[key])
        except (TypeError, ValueError) as exc:
            raise AnalysisError("invalid_params", f"parameter {key!r} must be a number") from exc

    try:
        smooth = int(merged["smooth_window_epochs"])
    except (TypeError, ValueError) as exc:
        raise AnalysisError(
            "invalid_params", "smooth_window_epochs must be an integer"
        ) from exc
    if not 1 <= smooth <= 21:
        raise AnalysisError("invalid_params", "smooth_window_epochs must be between 1 and 21")
    merged["smooth_window_epochs"] = smooth

    if merged["wake_movement_g"] < 0:
        raise AnalysisError("invalid_params", "wake_movement_g must be >= 0")
    for key in ("min_contact_fraction", "min_hr_coverage"):
        if not 0 <= merged[key] <= 1:
            raise AnalysisError("invalid_params", f"{key} must be in [0, 1]")
    return merged


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
        artifacts: list[bool] = []

        for index in range(grid.count):
            hr_coverage = float(grid.coverage["hr"][index])
            contact = float(grid.hr_contact_fraction[index])
            hr_mean = float(grid.hr_mean_bpm[index])
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
                artifacts.append(True)
                continue

            ratio = hr_mean / night_median if night_median > 0 else 1.0
            if movement >= config["wake_movement_g"]:
                stage: Stage = "wake"
            elif ratio >= 1.05 and movement >= config["wake_movement_g"] * 0.25:
                stage = "wake"
            else:
                stage = "sleep"

            stages.append(stage)
            artifacts.append(hr_coverage < 0.5 or contact < 0.5)

        window = int(config["smooth_window_epochs"])
        if window > 1:
            stages = smooth_labels(stages, window)

        return StagingResult(
            stages=stages,
            artifacts=artifacts,
            warnings=warnings,
            extras={
                "night_median_hr_bpm": None if not np.isfinite(night_median) else round(night_median, 1),
                "params": config,
                "has_acc": has_acc,
                "smooth_window_epochs": window,
            },
        )


STAGER = BaselineStager()


__all__ = ["INFO", "STAGER", "BaselineStager"]
