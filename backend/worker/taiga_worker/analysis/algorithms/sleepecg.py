"""Optional SleepECG stager.

SleepECG classifies sleep from heartbeat times (RR intervals). Its bundled
classifiers are three-class (Wake/REM/NREM) or two-class (Wake/Sleep); they do
not distinguish light and deep sleep. This adapter prefers the ECG heartbeat
detector when ECG is available, falls back to the H10 RR intervals for HR-only
recordings, and applies the same five-epoch probability smoothing as wav2sleep.

The package and its bundled classifier weights are BSD-3-Clause. They are an
optional extra, so a base worker does not install Keras or PyTorch.
"""

from __future__ import annotations

import importlib.util
import os
import warnings
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping
from zoneinfo import ZoneInfo

import numpy as np

from ..contract import (
    AlgorithmInfo,
    AnalysisError,
    Recording,
    Stage,
    Stager,
    StagingResult,
    StreamNeed,
)
from ..decode import RR_MAX_MS, RR_MIN_MS
from ..smoothing import smooth_probabilities, unknown_runs

CLASSIFIERS = {
    "wrn-gru-mesa": ("nrem", "rem", "wake"),
    "wrn-gru-mesa-weighted": ("nrem", "rem", "wake"),
    "ws-gru-mesa": ("sleep", "wake"),
}
CLASSIFIER_MODE = {
    "wrn-gru-mesa": "wake-rem-nrem",
    "wrn-gru-mesa-weighted": "wake-rem-nrem",
    "ws-gru-mesa": "wake-sleep",
}
SMOOTHING_EPOCHS = 5


INFO = AlgorithmInfo(
    id="sleep-ecg",
    version="1.0.0",
    name="SleepECG",
    description=(
        "RR/ECG heartbeat-based stager from SleepECG. The bundled classifiers "
        "distinguish Wake/REM/NREM or Wake/Sleep; deep sleep is never invented. "
        "Experimental; not clinically validated."
    ),
    license="BSD-3-Clause",
    citation=(
        "Hofer F, Brunner C. SleepECG: a Python package for sleep stage "
        "classification based on ECG. Journal of Open Source Software 2023. "
        "doi:10.21105/joss.05411"
    ),
    homepage="https://github.com/cbrnr/sleepecg",
    needs=(
        StreamNeed("hr", required=False),
        StreamNeed("ecg", required=False),
    ),
    epoch_seconds=30,
    # Custom scheme: the selected classifier emits either wake/rem/nrem or
    # wake/sleep. The shared derive layer must treat both as supported and deep
    # as unsupported.
    stage_scheme="sleep-ecg",
    supported_stages=("wake", "rem", "nrem", "sleep", "unknown"),
    capabilities=frozenset(
        {"stages", "confidence", "probabilities", "artifacts", "no_deep"}
    ),
    default_params={
        "classifier": "wrn-gru-mesa",
        "prefer_ecg": True,
        "age": None,
        "gender": None,
        "max_nans": None,
        "min_beats_per_epoch": 5,
        "min_contact_fraction": 0.5,
        "min_ecg_coverage": 0.5,
        "min_usable_epochs": 60,
        "min_unknown_epochs": 2,
        "smooth_window_epochs": SMOOTHING_EPOCHS,
        "max_undefined_probability": 0.5,
    },
    params_schema={
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "classifier": {
                "type": "string",
                "enum": sorted(CLASSIFIERS),
            },
            "prefer_ecg": {
                "type": "boolean",
                "description": "Use the ECG heartbeat detector when ECG is present.",
            },
            "age": {"type": ["integer", "null"], "minimum": 0, "maximum": 120},
            "gender": {"type": ["string", "null"], "enum": ["female", "male", None]},
            "max_nans": {
                "type": ["number", "null"],
                "minimum": 0.0,
                "maximum": 1.0,
                "description": "Override the classifier's feature NaN threshold.",
            },
            "min_beats_per_epoch": {"type": "integer", "minimum": 1, "maximum": 60},
            "min_contact_fraction": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "min_ecg_coverage": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "min_usable_epochs": {"type": "integer", "minimum": 1, "maximum": 1440},
            "min_unknown_epochs": {"type": "integer", "minimum": 1, "maximum": 20},
            "smooth_window_epochs": {"type": "integer", "minimum": 1, "maximum": 21},
            "max_undefined_probability": {
                "type": "number",
                "minimum": 0.0,
                "maximum": 1.0,
            },
        },
    },
)


def availability() -> tuple[bool, str]:
    """Whether the optional SleepECG stack and bundled classifiers are usable."""

    missing = [
        name
        for name in ("sleepecg", "keras", "torch")
        if importlib.util.find_spec(name) is None
    ]
    if missing:
        return False, ""
    spec = importlib.util.find_spec("sleepecg")
    locations = list(getattr(spec, "submodule_search_locations", []) or [])
    if not locations:
        return False, "sleepecg has no package directory"
    classifiers = Path(locations[0]) / "classifiers"
    if not (classifiers / "wrn-gru-mesa.zip").is_file():
        return False, f"SleepECG classifiers are missing at {classifiers}"
    return True, ""


def _params(raw: Mapping[str, Any] | None) -> dict[str, Any]:
    merged: dict[str, Any] = dict(INFO.default_params)
    for key, value in (raw or {}).items():
        if key not in merged:
            raise AnalysisError("invalid_params", f"unknown SleepECG parameter {key!r}")
        merged[key] = value

    classifier = str(merged["classifier"] or "").strip()
    if classifier not in CLASSIFIERS:
        raise AnalysisError(
            "invalid_params",
            f"classifier must be one of {sorted(CLASSIFIERS)}",
        )
    merged["classifier"] = classifier

    prefer = merged["prefer_ecg"]
    if not isinstance(prefer, bool):
        raise AnalysisError("invalid_params", "prefer_ecg must be a boolean")
    merged["prefer_ecg"] = prefer

    age = merged["age"]
    if age is not None:
        try:
            age = int(age)
        except (TypeError, ValueError) as exc:
            raise AnalysisError("invalid_params", "age must be an integer") from exc
        if not 0 <= age <= 120:
            raise AnalysisError("invalid_params", "age must be between 0 and 120")
    merged["age"] = age

    gender = merged["gender"]
    if gender is not None:
        gender = str(gender).strip().lower()
        if gender not in {"female", "male"}:
            raise AnalysisError("invalid_params", "gender must be 'female' or 'male'")
    merged["gender"] = gender

    max_nans = merged["max_nans"]
    if max_nans is not None:
        try:
            max_nans = float(max_nans)
        except (TypeError, ValueError) as exc:
            raise AnalysisError("invalid_params", "max_nans must be a number") from exc
        if not 0.0 <= max_nans <= 1.0:
            raise AnalysisError("invalid_params", "max_nans must be in [0, 1]")
    merged["max_nans"] = max_nans

    for key, low, high in (
        ("min_beats_per_epoch", 1, 60),
        ("min_usable_epochs", 1, 1440),
        ("min_unknown_epochs", 1, 20),
        ("smooth_window_epochs", 1, 21),
    ):
        try:
            value = int(merged[key])
        except (TypeError, ValueError) as exc:
            raise AnalysisError("invalid_params", f"{key} must be an integer") from exc
        if not low <= value <= high:
            raise AnalysisError("invalid_params", f"{key} must be between {low} and {high}")
        merged[key] = value

    for key in ("min_contact_fraction", "min_ecg_coverage", "max_undefined_probability"):
        try:
            value = float(merged[key])
        except (TypeError, ValueError) as exc:
            raise AnalysisError("invalid_params", f"{key} must be a number") from exc
        if not 0.0 <= value <= 1.0:
            raise AnalysisError("invalid_params", f"{key} must be in [0, 1]")
        merged[key] = value

    return merged


def _install_nanpsd_fix() -> None:
    """Work around a SleepECG 0.6.0 bug for windows without a complete row.

    `_nanpsd` calls `periodogram` on an empty selection when no window is free of
    NaNs, which returns a frequency axis of length 541 while the output array
    expects 1080. The fixed version keeps the same behaviour and only skips the
    empty call. Remove this shim when upstream fixes it.
    """

    from sleepecg import feature_extraction as fe

    if getattr(fe, "_taiga_nanpsd_fixed", False):
        return

    def fixed_nanpsd(x: np.ndarray, fs: float, max_nans: float = 0.0):
        nfft = x.shape[1]
        psd = np.full((x.shape[0], nfft // 2 + 1), np.nan)
        nan_fraction = np.mean(np.isnan(x), axis=1)

        full = nan_fraction == 0
        if np.any(full):
            frequencies, psd[full] = fe.periodogram(x=x[full], fs=fs)
        else:
            frequencies = np.fft.rfftfreq(nfft, 1 / fs)

        empty = nan_fraction == 1
        for index in np.where((nan_fraction <= max_nans) & ~(full | empty))[0]:
            valid = x[index][~np.isnan(x[index])]
            _, psd[index] = fe.periodogram(
                valid, fs=fs, window="hann", nfft=nfft
            )
        return frequencies, psd

    fe._nanpsd = fixed_nanpsd
    fe._taiga_nanpsd_fixed = True


_CLASSIFIER_CACHE: dict[str, Any] = {}


def _load_classifier(name: str):
    if name in _CLASSIFIER_CACHE:
        return _CLASSIFIER_CACHE[name]

    os.environ["KERAS_BACKEND"] = "torch"
    try:
        import keras
        import torch
        from sleepecg.classification import load_classifier
    except Exception as exc:  # noqa: BLE001 - optional extra or broken install
        raise AnalysisError(
            "algorithm_unavailable",
            "cannot import SleepECG/Keras/torch; install the worker's "
            "sleepecg extra. If the install is fine, raise TAIGA_JOB_MEMORY_MB "
            f"(SleepECG needs about 1.5 GiB of address space). Import error: "
            f"{type(exc).__name__}: {exc}",
        ) from exc

    try:
        keras.config.disable_interactive_logging()
    except Exception:  # noqa: BLE001 - older Keras
        pass
    torch.set_num_threads(1)
    _install_nanpsd_fix()
    try:
        classifier = load_classifier(name, "SleepECG")
    except Exception as exc:  # noqa: BLE001
        raise AnalysisError(
            "invalid_classifier",
            f"cannot load SleepECG classifier {name!r}: {type(exc).__name__}: {exc}",
        ) from exc
    if classifier.stages_mode != CLASSIFIER_MODE[name]:
        raise AnalysisError(
            "invalid_classifier",
            f"classifier {name!r} reported mode {classifier.stages_mode!r}, "
            f"expected {CLASSIFIER_MODE[name]!r}",
        )
    try:
        predict = classifier.model.predict

        def predict_quiet(*args: Any, **kwargs: Any):
            kwargs.setdefault("verbose", 0)
            return predict(*args, **kwargs)

        classifier.model.predict = predict_quiet
    except Exception:  # noqa: BLE001 - progress output is cosmetic
        pass
    _CLASSIFIER_CACHE[name] = classifier
    return classifier


def _local_start_time(recording: Recording) -> Any:
    timezone = recording.meta.timezone or "UTC"
    try:
        zone = ZoneInfo(timezone)
    except Exception:  # noqa: BLE001 - unknown tz database entry
        zone = ZoneInfo("UTC")
    return datetime.fromtimestamp(recording.meta.start_utc_ms / 1000, tz=zone).time()


def ecg_heartbeats(recording: Recording) -> np.ndarray:
    """Detect heartbeat times from the normalized ECG, in seconds."""

    from sleepecg.heartbeats import detect_heartbeats

    ecg = recording.ecg
    if ecg is None or ecg.t_s.size == 0:
        return np.empty(0, dtype=np.float64)
    try:
        indices = detect_heartbeats(ecg.voltage_mv.astype(np.float64), ecg.rate_hz)
    except Exception:  # noqa: BLE001 - fall back to the RR path
        return np.empty(0, dtype=np.float64)
    beats = np.asarray(indices, dtype=np.float64) / max(ecg.rate_hz, 1e-6)
    beats = beats[(beats >= 0) & (beats <= recording.duration_s)]
    return np.sort(beats)


def rr_heartbeats(recording: Recording) -> np.ndarray:
    """Reconstruct heartbeat times from the H10 RR intervals, in seconds."""

    hr = recording.hr
    if hr is None:
        return np.empty(0, dtype=np.float64)
    parts: list[np.ndarray] = []
    for time_s, intervals, valid, contact in zip(
        hr.t_s, hr.rr_ms, hr.valid, hr.contact
    ):
        if not valid or not contact or len(intervals) == 0:
            continue
        rr = np.asarray(intervals, dtype=np.float64) / 1000.0
        rr = rr[(rr >= RR_MIN_MS / 1000.0) & (rr <= RR_MAX_MS / 1000.0)]
        if rr.size:
            # The last beat of the batch is at (or immediately before) the
            # sample time; walk backwards through the intervals.
            parts.append(time_s - np.cumsum(rr[::-1])[::-1])
    if not parts:
        return np.empty(0, dtype=np.float64)
    beats = np.sort(np.concatenate(parts))
    beats = beats[(beats >= 0) & (beats <= recording.duration_s)]
    if beats.size == 0:
        return beats
    # Adjacent batches overlap by one beat; remove near-duplicates without
    # collapsing genuinely fast beats.
    keep = [float(beats[0])]
    for beat in beats[1:]:
        if beat - keep[-1] >= 0.2:
            keep.append(float(beat))
    return np.asarray(keep, dtype=np.float64)


def _beats(recording: Recording, config: Mapping[str, Any]) -> tuple[np.ndarray, str]:
    beats = np.empty(0, dtype=np.float64)
    if config["prefer_ecg"] and recording.ecg is not None:
        beats = ecg_heartbeats(recording)
        if beats.size >= 2:
            return beats, "ecg"
    beats = rr_heartbeats(recording)
    if beats.size >= 2:
        return beats, "rr"
    if recording.ecg is not None and not config["prefer_ecg"]:
        beats = ecg_heartbeats(recording)
        if beats.size >= 2:
            return beats, "ecg"
    if recording.ecg is None and recording.hr is None:
        raise AnalysisError(
            "missing_stream", "SleepECG needs RR intervals or an ECG stream"
        )
    raise AnalysisError(
        "insufficient_beats",
        "SleepECG could not find enough heartbeats in the RR intervals or ECG",
    )


@dataclass(frozen=True)
class PreparedSleepEcg:
    stages: list[Stage]
    confidence: list[float | None]
    probabilities: dict[str, list[float]]
    artifacts: list[bool]
    unknown_epochs: int
    artifact_epochs: int
    scored_epochs: int
    canonical_epochs: int
    model_epochs: int
    beat_source: str
    beats: int


def _run_classifier(
    recording: Recording,
    config: Mapping[str, Any],
    classifier: Any,
    beats: np.ndarray,
) -> np.ndarray:
    from sleepecg.classification import stage
    from sleepecg.io.sleep_readers import Gender, SleepRecord, SubjectData

    gender = None
    if config["gender"] == "female":
        gender = Gender.FEMALE
    elif config["gender"] == "male":
        gender = Gender.MALE
    subject = (
        SubjectData(age=config["age"], gender=gender)
        if config["age"] is not None or gender is not None
        else None
    )
    record = SleepRecord(
        heartbeat_times=beats,
        id=recording.meta.session_id,
        recording_start_time=_local_start_time(recording),
        subject_data=subject,
    )

    feature_params = dict(classifier.feature_extraction_params)
    if config["max_nans"] is not None:
        feature_params["max_nans"] = config["max_nans"]
    model = replace(classifier, feature_extraction_params=feature_params)

    with warnings.catch_warnings():
        warnings.filterwarnings("ignore", message="HR analysis window too short.*")
        raw = stage(model, record, return_mode="prob")
    raw = np.asarray(raw, dtype=np.float64)
    if raw.ndim != 2:
        raise AnalysisError("invalid_result", "SleepECG returned an unexpected shape")
    return raw


def build_result(
    recording: Recording,
    config: Mapping[str, Any],
    names: tuple[str, ...],
    raw: np.ndarray,
    beats: np.ndarray,
    beat_source: str,
) -> PreparedSleepEcg:
    """Map classifier probabilities to the canonical result.

    This function is deliberately free of SleepECG imports so the mapping,
    smoothing and artifact rules can be tested without the optional stack.
    """

    grid = recording.epochs(30)
    canonical = grid.count
    if raw.ndim != 2:
        raise AnalysisError("invalid_result", "SleepECG returned an unexpected shape")
    if raw.shape[1] == len(names) + 1:
        undefined = raw[:, 0]
        class_probs = raw[:, 1:]
    elif raw.shape[1] == len(names):
        undefined = np.zeros(raw.shape[0], dtype=np.float64)
        class_probs = raw
    else:
        raise AnalysisError(
            "invalid_result",
            f"SleepECG returned {raw.shape[1]} columns for {len(names)} stages",
        )

    model_epochs = class_probs.shape[0]
    scored = min(canonical, model_epochs)
    if scored < 1:
        raise AnalysisError("insufficient_beats", "SleepECG scored no epochs")

    class_probs = class_probs[:scored]
    undefined = undefined[:scored]
    totals = class_probs.sum(axis=1, keepdims=True)
    class_probs = np.divide(
        class_probs,
        totals,
        out=np.full_like(class_probs, 1.0 / max(len(names), 1)),
        where=totals > 0,
    )

    beat_counts = np.histogram(beats, bins=np.arange(0, scored * 30 + 1, 30))[0]
    contact_available = bool(
        recording.hr is not None and np.any(recording.hr.contact_supported)
    )
    usable = beat_counts >= int(config["min_beats_per_epoch"])
    if contact_available:
        contact = np.asarray(grid.hr_contact_fraction, dtype=np.float64)[:scored]
        usable &= contact >= float(config["min_contact_fraction"])
    if beat_source == "ecg":
        coverage = np.asarray(grid.coverage["ecg"], dtype=np.float64)[:scored]
        saturated = np.asarray(grid.ecg_saturated_fraction, dtype=np.float64)[:scored]
        usable &= coverage >= float(config["min_ecg_coverage"])
        usable &= saturated <= 0.5
    usable &= undefined <= float(config["max_undefined_probability"])

    usable_all = np.zeros(canonical, dtype=bool)
    usable_all[:scored] = usable
    unknown = unknown_runs(~usable_all, int(config["min_unknown_epochs"]))
    stageable = ~unknown
    stageable_epochs = int(np.count_nonzero(stageable))
    if stageable_epochs == 0:
        raise AnalysisError("insufficient_data", "no 30 s epoch has usable RR/ECG contact")
    if stageable_epochs < int(config["min_usable_epochs"]):
        needed = int(config["min_usable_epochs"]) * 30 / 60.0
        raise AnalysisError(
            "insufficient_data",
            f"SleepECG needs at least {needed:g} min of usable beats; "
            f"found {stageable_epochs * 30 / 60.0:g} min",
        )

    smoothed = smooth_probabilities(
        class_probs, stageable[:scored], int(config["smooth_window_epochs"])
    )
    probabilities: dict[str, list[float]] = {name: [] for name in names}
    stages: list[Stage] = []
    confidence: list[float | None] = []
    artifacts: list[bool] = []
    for index in range(canonical):
        if index < scored:
            row = smoothed[index]
            for name, value in zip(names, row):
                probabilities[name].append(float(value))
            if stageable[index]:
                stages.append(names[int(np.argmax(row))])  # type: ignore[arg-type]
                confidence.append(float(np.max(row)))
            else:
                stages.append("unknown")
                confidence.append(None)
            artifacts.append(not bool(usable_all[index]))
        else:
            for name in names:
                probabilities[name].append(0.0)
            stages.append("unknown")
            confidence.append(None)
            artifacts.append(True)

    return PreparedSleepEcg(
        stages=stages,
        confidence=confidence,
        probabilities=probabilities,
        artifacts=artifacts,
        unknown_epochs=int(np.count_nonzero(unknown)),
        artifact_epochs=int(np.count_nonzero(~usable_all)),
        scored_epochs=scored,
        canonical_epochs=canonical,
        model_epochs=model_epochs,
        beat_source=beat_source,
        beats=int(beats.size),
    )


class SleepEcgStager(Stager):
    info = INFO

    def stage(self, recording: Recording, params: Mapping[str, Any]) -> StagingResult:
        config = _params(params)
        beats, beat_source = _beats(recording, config)
        classifier = _load_classifier(str(config["classifier"]))
        raw = _run_classifier(recording, config, classifier, beats)
        prepared = build_result(
            recording,
            config,
            CLASSIFIERS[str(config["classifier"])],
            raw,
            beats,
            beat_source,
        )

        mode = classifier.stages_mode
        messages = [
            f"classifier {config['classifier']} ({mode}); "
            + (
                "deep sleep is not distinguished"
                if mode == "wake-rem-nrem"
                else "only wake and sleep are distinguished"
            ),
            f"heartbeats from {beat_source.upper()} ({prepared.beats} beats)",
        ]
        if prepared.unknown_epochs:
            messages.append(
                f"{prepared.artifact_epochs} epoch(s) have low beat/contact quality; "
                f"{prepared.unknown_epochs} are unknown and the rest are artifacts"
            )
        if config["age"] is None or config["gender"] is None:
            messages.append(
                "age/gender were not provided; SleepECG metadata features are masked "
                "and its wake/sleep prior may be less reliable"
            )
        if int(config["smooth_window_epochs"]) > 1:
            messages.append(
                f"smoothed stage probabilities over {config['smooth_window_epochs']} "
                f"epochs ({int(config['smooth_window_epochs']) * 30 / 60.0:g} min)"
            )

        from sleepecg import __version__ as sleepecg_version

        extras = {
            "classifier": config["classifier"],
            "classifier_source": "SleepECG",
            "stages_mode": mode,
            "beat_source": beat_source,
            "beats": prepared.beats,
            "model_epochs": prepared.model_epochs,
            "scored_epochs": prepared.scored_epochs,
            "canonical_epochs": prepared.canonical_epochs,
            "unknown_epochs": prepared.unknown_epochs,
            "artifact_epochs": prepared.artifact_epochs,
            "smooth_window_epochs": int(config["smooth_window_epochs"]),
            "min_beats_per_epoch": int(config["min_beats_per_epoch"]),
            "min_contact_fraction": float(config["min_contact_fraction"]),
            "max_undefined_probability": float(config["max_undefined_probability"]),
            "age_provided": config["age"] is not None,
            "gender_provided": config["gender"] is not None,
            "sleepecg": sleepecg_version,
        }
        return StagingResult(
            stages=prepared.stages,
            confidence=prepared.confidence,
            probabilities=prepared.probabilities,
            artifacts=prepared.artifacts,
            warnings=messages,
            supported_stages=tuple(CLASSIFIERS[str(config["classifier"])]) + ("unknown",),
            extras=extras,
            provenance={
                "package": "sleepecg",
                "package_version": sleepecg_version,
                "classifier": config["classifier"],
                "classifier_source": "SleepECG",
                "stages_mode": mode,
                "beat_source": beat_source,
                "license_note": "SleepECG package and bundled classifier weights are BSD-3-Clause",
            },
        )


STAGER = SleepEcgStager()


__all__ = [
    "CLASSIFIERS",
    "INFO",
    "STAGER",
    "SleepEcgStager",
    "availability",
    "build_result",
    "ecg_heartbeats",
    "rr_heartbeats",
]
