"""Optional wav2sleep ECG stager.

wav2sleep is a multi-modal sleep stager from Carter and Tarassenko (ML4H
2024). This adapter uses its ECG-only path: the canonical ECG stream is
resampled to 1024 samples per 30 s epoch (34.133... Hz), z-scored over the
whole model input, optionally padded/truncated to the model length, and
classified into Wake/Light/Deep/REM.

The upstream package and PyTorch are optional dependencies. Importing this
module is cheap; torch is imported only when a job actually runs. When the
extra or the pinned checkpoint is missing the algorithm is simply not
advertised, so a base worker keeps running the built-in baseline.
"""

from __future__ import annotations

import hashlib
import importlib.util
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

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
from ..smoothing import smooth_probabilities, unknown_runs

CHECKPOINT_REPO = "joncarter/wav2sleep"
CHECKPOINT_REVISION = "8b6e46ab3ef6e3945282b81f631933c59db0b205"
CHECKPOINT_SHA256 = "ea6fb4410315cf6cce406fe1ffd44cba83e8dc69be6a69101aff62cc2cbee0bc"
CHECKPOINT_BYTES = 9_666_562
CHECKPOINT_ENV = "TAIGA_WAV2SLEEP_CHECKPOINT"

EPOCH_SECONDS = 30
SAMPLES_PER_EPOCH = 1024
SAMPLE_RATE_HZ = SAMPLES_PER_EPOCH / EPOCH_SECONDS
# Upstream ``INTEGER_LABEL_MAPS[4]``: 0=Wake, 1=Light (N1+N2), 2=Deep (N3),
# 3=REM. The order is part of the checkpoint contract and must not change.
CLASSES: tuple[Stage, ...] = ("wake", "light", "deep", "rem")
REMOTE_SCHEMES = ("hf://", "http://", "https://")

INFO = AlgorithmInfo(
    id="wav2sleep",
    version="1.1.0",
    name="wav2sleep ECG",
    description=(
        "ECG-only cardio-respiratory sleep stager from the wav2sleep model "
        "(ML4H 2024). Experimental; not clinically validated."
    ),
    license="MIT",
    citation=(
        "Carter JF, Tarassenko L. wav2sleep: A Unified Multi-Modal Approach to "
        "Sleep Stage Classification from Physiological Signals. ML4H 2024. "
        "arXiv:2411.04644"
    ),
    homepage="https://github.com/joncarter1/wav2sleep",
    needs=(StreamNeed("ecg", required=True, min_rate_hz=50.0),),
    epoch_seconds=EPOCH_SECONDS,
    stage_scheme="aasm4",
    supported_stages=AASM4_STAGES,
    capabilities=frozenset({"stages", "confidence", "probabilities", "artifacts"}),
    default_params={
        # Empty means "read TAIGA_WAV2SLEEP_CHECKPOINT". A checkpoint is never
        # downloaded at runtime, so a job can run offline.
        "checkpoint": "",
        "max_length_hours": 10,
        "pad_to_max_length": False,
        "batch_size": 1,
        "min_ecg_coverage": 0.5,
        "min_usable_epochs": 60,
        "min_hr_contact_fraction": 0.5,
        "max_ecg_rms_ratio": 12.0,
        "min_ecg_rms_ratio": 0.1,
        "min_unknown_epochs": 2,
        "smooth_window_epochs": 5,
        "device": "cpu",
    },
    params_schema={
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "checkpoint": {
                "type": "string",
                "description": "Local checkpoint directory (config.yaml + state_dict.pth).",
            },
            "max_length_hours": {"type": "integer", "minimum": 1, "maximum": 24},
            "pad_to_max_length": {
                "type": "boolean",
                "description": (
                    "Zero-pad the model input to max_length_hours, matching upstream's "
                    "fixed 10 h inference. Off by default: shorter nights run as-is."
                ),
            },
            "batch_size": {
                "type": "integer",
                "minimum": 1,
                "maximum": 64,
                "description": "Accepted for API parity; one job analyses one night.",
            },
            "min_ecg_coverage": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "min_usable_epochs": {"type": "integer", "minimum": 1, "maximum": 1440},
            "min_hr_contact_fraction": {
                "type": "number",
                "minimum": 0.0,
                "maximum": 1.0,
                "description": "Minimum HR contact fraction when the strap reports contact.",
            },
            "max_ecg_rms_ratio": {
                "type": "number",
                "minimum": 0.0,
                "description": "Reject epochs this many times louder than the night median; 0 disables.",
            },
            "min_ecg_rms_ratio": {
                "type": "number",
                "minimum": 0.0,
                "description": "Reject epochs this much quieter than the night median; 0 disables.",
            },
            "min_unknown_epochs": {
                "type": "integer",
                "minimum": 1,
                "maximum": 20,
                "description": "Short artifact runs are staged and flagged instead of becoming gaps.",
            },
            "smooth_window_epochs": {
                "type": "integer",
                "minimum": 1,
                "maximum": 21,
                "description": "Centered probability smoothing window; 1 disables smoothing.",
            },
            "device": {"type": "string", "enum": ["cpu", "auto"]},
        },
    },
)


def _default_checkpoint_dir() -> Path:
    cache = os.environ.get("XDG_CACHE_HOME", "").strip()
    root = Path(cache).expanduser() if cache else Path.home() / ".cache"
    return root / "taiga-sleep" / "models" / "wav2sleep"


def _resolve_checkpoint(raw: Any) -> Path | None:
    value = str(raw or "").strip() or os.environ.get(CHECKPOINT_ENV, "").strip()
    if value:
        return Path(value).expanduser().resolve()
    default = _default_checkpoint_dir()
    return default.resolve() if default.is_dir() else None


def _remote_checkpoint(value: Any) -> bool:
    text = str(value or "").strip() or os.environ.get(CHECKPOINT_ENV, "").strip()
    return text.lower().startswith(REMOTE_SCHEMES)


def _dependencies_installed() -> tuple[bool, str]:
    missing = [
        name
        for name in ("torch", "wav2sleep")
        if importlib.util.find_spec(name) is None
    ]
    if missing:
        return False, ""
    return True, ""


def availability() -> tuple[bool, str]:
    """Whether this worker can actually run wav2sleep.

    Missing optional dependencies are silent: a base worker is expected not to
    have them. Missing or incomplete checkpoint configuration is reported to
    the worker log because the operator opted into the extra.
    """

    installed, _ = _dependencies_installed()
    if not installed:
        return False, ""
    if _remote_checkpoint(None):
        return (
            False,
            f"{CHECKPOINT_ENV} must be a local directory; download the pinned "
            "checkpoint during installation",
        )
    checkpoint = _resolve_checkpoint(None)
    if checkpoint is None:
        return (
            False,
            f"wav2sleep is installed but {CHECKPOINT_ENV} is not set; "
            "download the pinned checkpoint and point the worker at it",
        )
    for name in ("config.yaml", "state_dict.pth"):
        if not (checkpoint / name).is_file():
            return False, f"wav2sleep checkpoint is incomplete at {checkpoint} (missing {name})"
    return True, ""


def _params(raw: Mapping[str, Any] | None) -> dict[str, Any]:
    merged: dict[str, Any] = dict(INFO.default_params)
    for key, value in (raw or {}).items():
        if key not in merged:
            raise AnalysisError("invalid_params", f"unknown wav2sleep parameter {key!r}")
        merged[key] = value

    try:
        max_hours = int(merged["max_length_hours"])
    except (TypeError, ValueError) as exc:
        raise AnalysisError("invalid_params", "max_length_hours must be an integer") from exc
    if not 1 <= max_hours <= 24:
        raise AnalysisError("invalid_params", "max_length_hours must be between 1 and 24")
    merged["max_length_hours"] = max_hours

    try:
        batch_size = int(merged["batch_size"])
    except (TypeError, ValueError) as exc:
        raise AnalysisError("invalid_params", "batch_size must be an integer") from exc
    if not 1 <= batch_size <= 64:
        raise AnalysisError("invalid_params", "batch_size must be between 1 and 64")
    merged["batch_size"] = batch_size

    try:
        min_coverage = float(merged["min_ecg_coverage"])
    except (TypeError, ValueError) as exc:
        raise AnalysisError("invalid_params", "min_ecg_coverage must be a number") from exc
    if not 0.0 <= min_coverage <= 1.0:
        raise AnalysisError("invalid_params", "min_ecg_coverage must be in [0, 1]")
    merged["min_ecg_coverage"] = min_coverage

    try:
        min_epochs = int(merged["min_usable_epochs"])
    except (TypeError, ValueError) as exc:
        raise AnalysisError("invalid_params", "min_usable_epochs must be an integer") from exc
    if not 1 <= min_epochs <= 1440:
        raise AnalysisError("invalid_params", "min_usable_epochs must be between 1 and 1440")
    merged["min_usable_epochs"] = min_epochs

    try:
        contact = float(merged["min_hr_contact_fraction"])
    except (TypeError, ValueError) as exc:
        raise AnalysisError("invalid_params", "min_hr_contact_fraction must be a number") from exc
    if not 0.0 <= contact <= 1.0:
        raise AnalysisError("invalid_params", "min_hr_contact_fraction must be in [0, 1]")
    merged["min_hr_contact_fraction"] = contact

    for key in ("max_ecg_rms_ratio", "min_ecg_rms_ratio"):
        try:
            value = float(merged[key])
        except (TypeError, ValueError) as exc:
            raise AnalysisError("invalid_params", f"{key} must be a number") from exc
        if value < 0:
            raise AnalysisError("invalid_params", f"{key} must be >= 0")
        merged[key] = value

    for key, low, high in (
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

    pad = merged["pad_to_max_length"]
    if not isinstance(pad, bool):
        raise AnalysisError("invalid_params", "pad_to_max_length must be a boolean")
    merged["pad_to_max_length"] = pad

    checkpoint = merged["checkpoint"]
    if not isinstance(checkpoint, str):
        raise AnalysisError("invalid_params", "checkpoint must be a string")
    merged["checkpoint"] = checkpoint.strip()

    device = str(merged["device"] or "cpu").strip().lower()
    if device not in {"cpu", "auto"}:
        raise AnalysisError("invalid_params", "device must be 'cpu' or 'auto'")
    merged["device"] = device
    return merged


@dataclass(frozen=True)
class PreparedEcg:
    signal: np.ndarray
    canonical_epochs: int
    scored_epochs: int
    input_epochs: int
    usable: np.ndarray
    coverage: np.ndarray
    saturated: np.ndarray
    artifacts: np.ndarray | None = None
    hr_contact: np.ndarray | None = None
    rms_ratio: np.ndarray | None = None

    @property
    def padded_epochs(self) -> int:
        return self.input_epochs - self.scored_epochs

    @property
    def usable_epochs(self) -> int:
        return int(np.count_nonzero(self.usable))

    @property
    def artifact_epochs(self) -> int:
        if self.artifacts is None:
            return self.scored_epochs - self.usable_epochs
        return int(np.count_nonzero(self.artifacts))

    @property
    def unknown_epochs(self) -> int:
        return self.scored_epochs - self.usable_epochs


def _epoch_rms(values: np.ndarray, epochs: int) -> np.ndarray:
    result = np.full(epochs, np.nan, dtype=np.float64)
    for index in range(epochs):
        chunk = values[index * SAMPLES_PER_EPOCH : (index + 1) * SAMPLES_PER_EPOCH]
        chunk = chunk[np.isfinite(chunk)]
        if chunk.size >= 100:
            result[index] = float(np.sqrt(np.mean(chunk**2)))
    return result


def _finite_signal(values: np.ndarray) -> np.ndarray:
    """Fill gaps the way upstream preprocessing does.

    Upstream interpolates between real samples and fills everything outside
    the recording with zeros before computing the z-score. Gaps still count as
    artifacts through the shared coverage mask; they are never treated as
    trustworthy epochs.
    """

    finite = np.isfinite(values)
    if not np.any(finite):
        raise AnalysisError("bad_ecg", "the ECG stream has no usable samples")
    indices = np.nonzero(finite)[0]
    first = int(indices[0])
    last = int(indices[-1])
    filled = np.zeros(values.size, dtype=np.float64)
    if first == last:
        filled[first] = values[first]
    else:
        target = np.arange(first, last + 1)
        filled[first : last + 1] = np.interp(target, indices, values[indices])
    return filled


def prepare_ecg(recording: Recording, config: Mapping[str, Any]) -> PreparedEcg:
    """Build the model tensor and decide which epochs may be scored.

    Per 30 s epoch there are 1024 samples. A partial last epoch and, when
    requested, the rest of ``max_length_hours`` are zero-filled. Artifact
    epochs are replaced with a flat signal and excluded from the z-score, so a
    detached strap or a noisy electrode does not rescale the rest of the night.
    """

    ecg = recording.ecg
    if ecg is None or ecg.t_s.size == 0:
        raise AnalysisError("missing_stream", "wav2sleep requires a non-empty ECG stream")

    grid = recording.epochs(EPOCH_SECONDS)
    max_epochs = int(config["max_length_hours"]) * 60 * 2
    scored_epochs = min(grid.count, max_epochs)
    input_epochs = max_epochs if config["pad_to_max_length"] else scored_epochs
    target_samples = input_epochs * SAMPLES_PER_EPOCH

    resampled = recording.resample("ecg", SAMPLE_RATE_HZ)
    values = np.asarray(resampled.values, dtype=np.float64)
    values[~resampled.valid] = np.nan
    values = _finite_signal(values)

    coverage = np.asarray(grid.coverage["ecg"], dtype=np.float64)[:scored_epochs]
    saturated = np.asarray(grid.ecg_saturated_fraction, dtype=np.float64)[:scored_epochs]
    usable = (coverage >= float(config["min_ecg_coverage"])) & (saturated <= 0.5)

    contact_available = bool(
        recording.hr is not None and np.any(recording.hr.contact_supported)
    )
    hr_contact = None
    if contact_available:
        hr_contact = np.asarray(grid.hr_contact_fraction, dtype=np.float64)[:scored_epochs]
        usable &= hr_contact >= float(config["min_hr_contact_fraction"])

    rms = _epoch_rms(values, scored_epochs)
    rms_ratio = None
    finite_rms = rms[np.isfinite(rms)]
    if finite_rms.size:
        median_rms = float(np.median(finite_rms))
        if median_rms > 0:
            rms_ratio = rms / median_rms
            max_ratio = float(config["max_ecg_rms_ratio"])
            min_ratio = float(config["min_ecg_rms_ratio"])
            if max_ratio > 0:
                usable &= np.isfinite(rms_ratio) & (rms_ratio <= max_ratio)
            if min_ratio > 0:
                usable &= np.isfinite(rms_ratio) & (rms_ratio >= min_ratio)

    artifacts = ~usable
    unknown = unknown_runs(artifacts, int(config["min_unknown_epochs"]))
    stageable = ~unknown
    stageable_epochs = int(np.count_nonzero(stageable))
    if stageable_epochs == 0:
        raise AnalysisError("bad_ecg", "no 30 s epoch has usable ECG contact and signal quality")
    if stageable_epochs < int(config["min_usable_epochs"]):
        needed = int(config["min_usable_epochs"]) * EPOCH_SECONDS / 60.0
        raise AnalysisError(
            "insufficient_ecg",
            f"wav2sleep needs at least {needed:g} min of usable ECG contact; "
            f"found {stageable_epochs * EPOCH_SECONDS / 60.0:g} min",
        )

    if values.size > target_samples:
        values = values[:target_samples]
    elif values.size < target_samples:
        values = np.pad(values, (0, target_samples - values.size), constant_values=0.0)

    normalise = np.ones(target_samples, dtype=bool)
    for index in np.nonzero(unknown)[0]:
        normalise[index * SAMPLES_PER_EPOCH : (index + 1) * SAMPLES_PER_EPOCH] = False
    normalised = values[normalise]
    if normalised.size == 0:
        raise AnalysisError("bad_ecg", "the ECG stream has no usable samples")
    mean = float(np.mean(normalised))
    std = float(np.std(normalised))
    if not math.isfinite(mean) or not math.isfinite(std) or std < 1e-6:
        raise AnalysisError("bad_ecg", "the ECG stream has no measurable variation")
    signal = ((values - mean) / std).astype(np.float32, copy=False)
    signal[~normalise] = 0.0

    return PreparedEcg(
        signal=signal,
        canonical_epochs=grid.count,
        scored_epochs=scored_epochs,
        input_epochs=input_epochs,
        usable=stageable,
        coverage=coverage,
        saturated=saturated,
        artifacts=artifacts,
        hr_contact=hr_contact,
        rms_ratio=rms_ratio,
    )


def _checkpoint_digest(checkpoint: Path) -> tuple[str, int]:
    path = checkpoint / "state_dict.pth"
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise AnalysisError("invalid_checkpoint", f"cannot read {path}: {exc}") from exc
    return hashlib.sha256(data).hexdigest(), len(data)


_MODEL_CACHE: dict[tuple[str, str], Any] = {}


def _load_model(checkpoint: Path, device: str) -> Any:
    digest, size = _checkpoint_digest(checkpoint)
    if digest != CHECKPOINT_SHA256 or size != CHECKPOINT_BYTES:
        raise AnalysisError(
            "invalid_checkpoint",
            f"state_dict.pth does not match the pinned wav2sleep checkpoint "
            f"(expected sha256 {CHECKPOINT_SHA256}, got {digest})",
        )
    if not (checkpoint / "config.yaml").is_file():
        raise AnalysisError("invalid_checkpoint", f"config.yaml is missing in {checkpoint}")

    key = (str(checkpoint), device)
    cached = _MODEL_CACHE.get(key)
    if cached is not None:
        return cached

    try:
        import torch
        from wav2sleep import load_model
    except Exception as exc:  # noqa: BLE001 - optional extra, memory or broken install
        raise AnalysisError(
            "algorithm_unavailable",
            "cannot import wav2sleep/torch; install the worker's wav2sleep extra. "
            "If the install is fine, raise TAIGA_JOB_MEMORY_MB (wav2sleep needs "
            f"about 1.5 GiB of address space). Import error: {type(exc).__name__}: {exc}",
        ) from exc

    torch.set_num_threads(1)
    try:
        model = load_model(str(checkpoint), device=device)
    except Exception as exc:  # noqa: BLE001 - surface a clean checkpoint failure
        raise AnalysisError(
            "invalid_checkpoint",
            f"cannot load the wav2sleep checkpoint: {type(exc).__name__}: {exc}",
        ) from exc
    if "ECG" not in getattr(model, "valid_signals", []):
        raise AnalysisError("invalid_checkpoint", "the checkpoint has no ECG signal encoder")
    if int(getattr(model, "num_classes", 0)) != len(CLASSES):
        raise AnalysisError(
            "invalid_checkpoint",
            f"the checkpoint has {getattr(model, 'num_classes', 0)} classes, expected {len(CLASSES)}",
        )
    _MODEL_CACHE[key] = model
    return model


def classify(model: Any, signal: np.ndarray) -> np.ndarray:
    """Run one night and return class probabilities shaped ``(epochs, 4)``."""

    import torch

    tensor = torch.from_numpy(np.asarray(signal, dtype=np.float32)).unsqueeze(0)
    with torch.inference_mode():
        logits = model({"ECG": tensor})
        probabilities = torch.softmax(logits.float(), dim=-1)
    return probabilities[0].cpu().numpy()


def to_result(
    prepared: PreparedEcg,
    probabilities: np.ndarray,
    *,
    extras: Mapping[str, Any],
    smooth_window_epochs: int = 1,
) -> StagingResult:
    """Map model output to the canonical result, keeping artifacts and gaps distinct."""

    if probabilities.ndim != 2 or probabilities.shape[0] < prepared.scored_epochs:
        raise AnalysisError("invalid_result", "wav2sleep returned an unexpected tensor shape")

    values = np.asarray(probabilities[: prepared.scored_epochs, : len(CLASSES)], dtype=np.float64)
    values = np.nan_to_num(values, nan=0.0, posinf=0.0, neginf=0.0)
    values = smooth_probabilities(values, prepared.usable, smooth_window_epochs)
    artifact_mask = (
        np.asarray(prepared.artifacts, dtype=bool)
        if prepared.artifacts is not None
        else ~prepared.usable
    )

    stages: list[Stage] = []
    confidence: list[float | None] = []
    artifacts: list[bool] = []
    by_stage: dict[Stage, list[float]] = {stage: [] for stage in CLASSES}

    for index in range(prepared.scored_epochs):
        row = values[index]
        total = float(np.sum(row))
        if total > 0:
            row = row / total
        for stage, value in zip(CLASSES, row):
            by_stage[stage].append(float(value))
        if prepared.usable[index]:
            stages.append(CLASSES[int(np.argmax(row))])
            confidence.append(float(np.max(row)))
        else:
            stages.append("unknown")
            confidence.append(None)
        artifacts.append(bool(artifact_mask[index]))

    for _ in range(prepared.scored_epochs, prepared.canonical_epochs):
        stages.append("unknown")
        confidence.append(None)
        artifacts.append(True)
    for stage in CLASSES:
        by_stage[stage].extend([0.0] * (prepared.canonical_epochs - prepared.scored_epochs))

    warnings: list[str] = []
    if prepared.scored_epochs < prepared.canonical_epochs:
        warnings.append(
            f"scored the first {prepared.scored_epochs} of {prepared.canonical_epochs} epochs "
            f"({int(extras.get('max_length_hours', 10))} h limit); the rest are unknown"
        )
    if prepared.padded_epochs > 0:
        warnings.append(
            f"padded {prepared.padded_epochs} epochs with zeros to match the model input"
        )
    if prepared.artifact_epochs > 0:
        warnings.append(
            f"{prepared.artifact_epochs} epoch(s) have no reliable ECG contact or signal "
            f"quality; {prepared.unknown_epochs} are reported as unknown and the rest are "
            "flagged as artifacts"
        )
    if smooth_window_epochs > 1:
        warnings.append(
            f"smoothed stage probabilities over {smooth_window_epochs} epochs "
            f"({smooth_window_epochs * EPOCH_SECONDS / 60.0:g} min)"
        )

    return StagingResult(
        stages=stages,
        confidence=confidence,
        probabilities=by_stage,
        artifacts=artifacts,
        warnings=warnings,
        extras=dict(extras),
        provenance={
            "checkpoint": str(extras.get("checkpoint_path", "")),
            "checkpoint_revision": CHECKPOINT_REVISION,
            "checkpoint_sha256": CHECKPOINT_SHA256,
            "checkpoint_bytes": CHECKPOINT_BYTES,
            "classes": list(CLASSES),
        },
    )


class Wav2SleepStager(Stager):
    info = INFO

    def stage(self, recording: Recording, params: Mapping[str, Any]) -> StagingResult:
        config = _params(params)
        if _remote_checkpoint(config["checkpoint"]):
            raise AnalysisError(
                "invalid_checkpoint",
                "wav2sleep checkpoints must be local; download the pinned checkpoint during installation",
            )
        checkpoint = _resolve_checkpoint(config["checkpoint"])
        if checkpoint is None:
            raise AnalysisError(
                "missing_checkpoint",
                f"wav2sleep needs a local checkpoint; set {CHECKPOINT_ENV} or the checkpoint param",
            )

        prepared = prepare_ecg(recording, config)
        model = _load_model(checkpoint, str(config["device"]))
        probabilities = classify(model, prepared.signal)
        try:
            import torch

            torch_version: str | None = str(torch.__version__)
        except Exception:  # noqa: BLE001 - tests and checkpoints can stub inference
            torch_version = None

        extras = {
            "checkpoint_path": str(checkpoint),
            "checkpoint_revision": CHECKPOINT_REVISION,
            "checkpoint_repo": CHECKPOINT_REPO,
            "checkpoint_sha256": CHECKPOINT_SHA256,
            "classes": list(CLASSES),
            "samples_per_epoch": SAMPLES_PER_EPOCH,
            "sample_rate_hz": SAMPLE_RATE_HZ,
            "canonical_epochs": prepared.canonical_epochs,
            "scored_epochs": prepared.scored_epochs,
            "input_epochs": prepared.input_epochs,
            "padded_epochs": prepared.padded_epochs,
            "usable_epochs": prepared.usable_epochs,
            "artifact_epochs": prepared.artifact_epochs,
            "unknown_epochs": prepared.unknown_epochs,
            "hr_contact_available": prepared.hr_contact is not None,
            "min_hr_contact_fraction": float(config["min_hr_contact_fraction"]),
            "max_ecg_rms_ratio": float(config["max_ecg_rms_ratio"]),
            "min_ecg_rms_ratio": float(config["min_ecg_rms_ratio"]),
            "min_unknown_epochs": int(config["min_unknown_epochs"]),
            "smooth_window_epochs": int(config["smooth_window_epochs"]),
            "max_length_hours": int(config["max_length_hours"]),
            "pad_to_max_length": bool(config["pad_to_max_length"]),
            "batch_size": int(config["batch_size"]),
            "device": str(config["device"]),
            "torch": torch_version,
            "normalization": "zscore_stageable",
        }
        return to_result(
            prepared,
            probabilities,
            extras=extras,
            smooth_window_epochs=int(config["smooth_window_epochs"]),
        )


STAGER = Wav2SleepStager()


__all__ = [
    "CHECKPOINT_BYTES",
    "CHECKPOINT_ENV",
    "CHECKPOINT_REPO",
    "CHECKPOINT_REVISION",
    "CHECKPOINT_SHA256",
    "CLASSES",
    "INFO",
    "SAMPLES_PER_EPOCH",
    "SAMPLE_RATE_HZ",
    "STAGER",
    "PreparedEcg",
    "Wav2SleepStager",
    "availability",
    "classify",
    "prepare_ecg",
    "to_result",
]
