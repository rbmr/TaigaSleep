"""Stable interfaces between the worker, the stagers and the result API.

A stager is deliberately small: it receives one normalized :class:`Recording`
and returns one :class:`StagingResult`. It does not read files, call the API or
write results. The worker owns I/O, validation, metrics and quality so results
stay comparable when the user switches algorithms.
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, ClassVar, Literal, Mapping, Sequence

import numpy as np

CONTRACT_VERSION = 1

# Canonical stages. ``nrem`` exists for algorithms that cannot split NREM into
# light and deep; ``sleep`` exists for wake/sleep classifiers that cannot split
# sleep at all. Neither may be silently mapped to a more specific stage.
Stage = Literal["wake", "rem", "nrem", "light", "deep", "sleep", "unknown"]
STAGES: tuple[Stage, ...] = ("wake", "rem", "nrem", "light", "deep", "sleep", "unknown")
AASM4_STAGES: tuple[Stage, ...] = ("wake", "rem", "light", "deep", "unknown")
WAKE_REM_NREM_STAGES: tuple[Stage, ...] = ("wake", "rem", "nrem", "unknown")
WAKE_SLEEP_STAGES: tuple[Stage, ...] = ("wake", "sleep", "unknown")

StreamName = Literal["hr", "ecg", "acc"]
STREAM_NAMES: tuple[StreamName, ...] = ("hr", "ecg", "acc")

ALGORITHM_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")


class AnalysisError(RuntimeError):
    """A user-visible analysis failure with a stable machine-readable code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class StreamNeed:
    """A stream and, optionally, the sample-rate range an algorithm accepts."""

    name: StreamName
    required: bool = True
    min_rate_hz: float | None = None
    max_rate_hz: float | None = None


@dataclass(frozen=True)
class AlgorithmInfo:
    """Everything the API and the app need to describe a stager."""

    id: str
    version: str
    name: str
    description: str
    license: str
    citation: str | None = None
    homepage: str | None = None
    needs: tuple[StreamNeed, ...] = ()
    epoch_seconds: int = 30
    stage_scheme: str = "aasm4"
    supported_stages: tuple[Stage, ...] = AASM4_STAGES
    capabilities: frozenset[str] = frozenset({"stages"})
    default_params: Mapping[str, Any] = field(default_factory=dict)
    params_schema: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not ALGORITHM_ID_RE.match(self.id):
            raise ValueError(f"invalid algorithm id: {self.id!r}")
        if not self.version:
            raise ValueError("algorithm version is required")
        if not self.name:
            raise ValueError("algorithm name is required")
        if not self.license:
            raise ValueError("algorithm license is required")
        if self.epoch_seconds <= 0:
            raise ValueError("epoch_seconds must be positive")
        unknown = [stage for stage in self.supported_stages if stage not in STAGES]
        if unknown:
            raise ValueError(f"unknown stages in supported_stages: {unknown}")
        if "unknown" not in self.supported_stages:
            raise ValueError("supported_stages must include 'unknown'")
        schemes = {
            "aasm4": set(AASM4_STAGES),
            "wake-rem-nrem": set(WAKE_REM_NREM_STAGES),
            "wake-sleep": set(WAKE_SLEEP_STAGES),
        }
        if self.stage_scheme in schemes:
            extra = set(self.supported_stages) - schemes[self.stage_scheme]
            if extra:
                raise ValueError(
                    f"stage_scheme {self.stage_scheme!r} cannot contain {sorted(extra)}"
                )
        for need in self.needs:
            if need.name not in STREAM_NAMES:
                raise ValueError(f"unknown stream in needs: {need.name}")

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "version": self.version,
            "name": self.name,
            "description": self.description,
            "license": self.license,
            "citation": self.citation,
            "homepage": self.homepage,
            "needs": [
                {
                    "name": need.name,
                    "required": need.required,
                    "min_rate_hz": need.min_rate_hz,
                    "max_rate_hz": need.max_rate_hz,
                }
                for need in self.needs
            ],
            "epoch_seconds": self.epoch_seconds,
            "stage_scheme": self.stage_scheme,
            "supported_stages": list(self.supported_stages),
            "capabilities": sorted(self.capabilities),
            "default_params": dict(self.default_params),
            "params_schema": dict(self.params_schema),
        }


@dataclass(frozen=True)
class RecordingMeta:
    format: str
    version: int
    session_id: str
    state: str
    start_utc_ms: int
    end_utc_ms: int | None
    timezone: str
    app_version: str | None
    phone_manufacturer: str | None
    phone_model: str | None
    sensor_kind: str
    sensor_id: str | None
    sensor_name: str | None
    hr_enabled: bool
    ecg_enabled: bool
    acc_enabled: bool
    ecg_rate_hz: float
    acc_rate_hz: float
    acc_range_g: float
    raw: Mapping[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, meta: Mapping[str, Any]) -> "RecordingMeta":
        try:
            sensor = meta.get("sensor") or {}
            config = meta.get("config") or {}
            app = meta.get("app") or {}
            phone = meta.get("phone") or {}
            return cls(
                format=str(meta["format"]),
                version=int(meta["version"]),
                session_id=str(meta["session_id"]),
                state=str(meta["state"]),
                start_utc_ms=int(meta["start_utc_ms"]),
                end_utc_ms=None if meta.get("end_utc_ms") is None else int(meta["end_utc_ms"]),
                timezone=str(meta.get("timezone") or ""),
                app_version=str(app.get("version")) if app.get("version") is not None else None,
                phone_manufacturer=phone.get("manufacturer"),
                phone_model=phone.get("model"),
                sensor_kind=str(sensor.get("kind") or ""),
                sensor_id=sensor.get("id"),
                sensor_name=sensor.get("name"),
                hr_enabled=bool(config.get("hr", True)),
                ecg_enabled=bool(config.get("ecg", False)),
                acc_enabled=bool(config.get("acc", False)),
                ecg_rate_hz=float(config.get("ecg_rate_hz") or 130.0),
                acc_rate_hz=float(config.get("acc_rate_hz") or 50.0),
                acc_range_g=float(config.get("acc_range_g") or 8.0),
                raw=dict(meta),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise AnalysisError("invalid_meta", f"invalid meta.json: {exc}") from exc


@dataclass(frozen=True)
class RecordingEvent:
    phone_ns: int
    elapsed_ns: int
    type: str
    message: str


@dataclass(frozen=True)
class Gap:
    stream: str
    start_s: float
    end_s: float

    @property
    def duration_s(self) -> float:
        return max(0.0, self.end_s - self.start_s)

    def to_dict(self) -> dict[str, Any]:
        return {
            "stream": self.stream,
            "start_s": round(self.start_s, 3),
            "end_s": round(self.end_s, 3),
            "duration_s": round(self.duration_s, 3),
        }


@dataclass(frozen=True)
class ClockSync:
    """A normal ``clock_sync`` event: the app set the strap clock."""

    stream: str
    at_s: float
    source: str
    message: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "stream": self.stream,
            "at_s": round(self.at_s, 3),
            "source": self.source,
            "message": self.message,
        }


@dataclass(frozen=True)
class ClockJump:
    """An actual discontinuity, not a scheduled clock sync."""

    stream: str
    at_s: float
    delta_s: float
    source: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "stream": self.stream,
            "at_s": round(self.at_s, 3),
            "delta_s": round(self.delta_s, 3),
            "source": self.source,
        }


@dataclass(frozen=True)
class StreamReport:
    present: bool
    samples: int
    rate_hz: float | None
    valid_bytes: int
    frames: int
    truncated_tail: bool
    error: str | None
    coverage: float


@dataclass(frozen=True)
class InputQuality:
    streams: Mapping[str, StreamReport]
    hr_contact_fraction: float | None
    ecg_saturated_fraction: float | None
    gaps: tuple[Gap, ...]
    clock_syncs: tuple[ClockSync, ...]
    clock_jumps: tuple[ClockJump, ...]
    warnings: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "streams": {
                name: {
                    "present": report.present,
                    "samples": report.samples,
                    "rate_hz": report.rate_hz,
                    "valid_bytes": report.valid_bytes,
                    "frames": report.frames,
                    "truncated_tail": report.truncated_tail,
                    "error": report.error,
                    "coverage": round(report.coverage, 4),
                }
                for name, report in self.streams.items()
            },
            "hr_contact_fraction": None
            if self.hr_contact_fraction is None
            else round(self.hr_contact_fraction, 4),
            "ecg_saturated_fraction": None
            if self.ecg_saturated_fraction is None
            else round(self.ecg_saturated_fraction, 4),
            "gaps": [gap.to_dict() for gap in self.gaps],
            "clock_syncs": [sync.to_dict() for sync in self.clock_syncs],
            "clock_jumps": [jump.to_dict() for jump in self.clock_jumps],
            "warnings": list(self.warnings),
        }


@dataclass(frozen=True)
class HrStream:
    t_s: np.ndarray
    hr_bpm: np.ndarray
    contact_supported: np.ndarray
    contact: np.ndarray
    valid: np.ndarray
    rr_ms: tuple[np.ndarray, ...]
    phone_ns: np.ndarray

    @property
    def rate_hz(self) -> float:
        return _median_rate(self.t_s)


@dataclass(frozen=True)
class EcgStream:
    t_s: np.ndarray
    voltage_mv: np.ndarray
    saturated: np.ndarray
    rate_hz: float
    phone_ns: np.ndarray

    @property
    def valid(self) -> np.ndarray:
        return ~self.saturated


@dataclass(frozen=True)
class AccStream:
    t_s: np.ndarray
    xyz_g: np.ndarray
    rate_hz: float
    phone_ns: np.ndarray

    @property
    def magnitude_g(self) -> np.ndarray:
        return np.sqrt(np.sum(self.xyz_g.astype(np.float64) ** 2, axis=1))


@dataclass
class EpochGrid:
    epoch_seconds: int
    start_unix_ns: int
    count: int
    starts_s: np.ndarray
    ends_s: np.ndarray
    coverage: Mapping[str, np.ndarray]
    hr_contact_fraction: np.ndarray
    hr_mean_bpm: np.ndarray
    hr_std_bpm: np.ndarray
    hr_sample_count: np.ndarray
    ecg_saturated_fraction: np.ndarray
    movement_g: np.ndarray
    acc_sample_count: np.ndarray

    @property
    def duration_s(self) -> float:
        return float(self.count * self.epoch_seconds)

    def epoch_start_unix_ns(self, index: int) -> int:
        return self.start_unix_ns + index * self.epoch_seconds * 1_000_000_000


@dataclass(frozen=True)
class ResampledStream:
    t_s: np.ndarray
    values: np.ndarray
    rate_hz: float
    valid: np.ndarray


@dataclass
class Recording:
    meta: RecordingMeta
    start_unix_ns: int
    end_unix_ns: int
    hr: HrStream | None
    ecg: EcgStream | None
    acc: AccStream | None
    events: tuple[RecordingEvent, ...]
    quality: InputQuality
    _epoch_cache: dict[int, EpochGrid] = field(default_factory=dict, repr=False, init=False)

    @property
    def duration_s(self) -> float:
        return max(0.0, (self.end_unix_ns - self.start_unix_ns) / 1_000_000_000)

    def has_stream(self, name: StreamName) -> bool:
        return getattr(self, name) is not None

    def stream_rate(self, name: StreamName) -> float | None:
        stream = getattr(self, name)
        return None if stream is None else stream.rate_hz

    def epochs(self, seconds: int = 30) -> EpochGrid:
        from .epochs import build_epoch_grid

        if seconds not in self._epoch_cache:
            self._epoch_cache[seconds] = build_epoch_grid(self, seconds)
        return self._epoch_cache[seconds]

    def resample(self, name: StreamName, rate_hz: float) -> ResampledStream:
        from .epochs import resample_stream

        return resample_stream(self, name, rate_hz)


@dataclass
class StagingResult:
    stages: Sequence[Stage]
    confidence: Sequence[float | None] | None = None
    probabilities: Mapping[Stage, Sequence[float]] | None = None
    artifacts: Sequence[bool] | None = None
    beats_s: Sequence[float] | None = None
    warnings: Sequence[str] = ()
    extras: Mapping[str, Any] = field(default_factory=dict)
    # Algorithm-specific provenance (checkpoint, model revision, ...). It is
    # merged into the standard provenance under ``algorithm``; core keys such
    # as licence and citation cannot be replaced.
    provenance: Mapping[str, Any] = field(default_factory=dict)


class Stager(ABC):
    """Base class for every stager.

    A stager declares its manifest as a class attribute and implements
    :meth:`stage`. Subclassing is required, so a typo or a duck-typed object
    cannot silently register as an algorithm.
    """

    info: ClassVar[AlgorithmInfo]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        info = getattr(cls, "info", None)
        if not isinstance(info, AlgorithmInfo):
            raise TypeError(
                f"{cls.__name__} must define info as an AlgorithmInfo class attribute"
            )

    @abstractmethod
    def stage(self, recording: Recording, params: Mapping[str, Any]) -> StagingResult:
        """Return one canonical stage per epoch, without doing any I/O."""


@dataclass(frozen=True)
class RegisteredAlgorithm:
    info: AlgorithmInfo
    stager: Stager
    source: str


def _median_rate(t_s: np.ndarray) -> float:
    if t_s.size < 2:
        return 0.0
    diffs = np.diff(t_s)
    diffs = diffs[diffs > 0]
    if diffs.size == 0:
        return 0.0
    return float(1.0 / np.median(diffs))


def validate_staging_result(
    result: StagingResult,
    *,
    epoch_count: int,
    info: AlgorithmInfo,
) -> None:
    """Reject results whose shape cannot be interpreted, before they are stored."""

    if len(result.stages) != epoch_count:
        raise AnalysisError(
            "invalid_result",
            f"stager returned {len(result.stages)} stages for {epoch_count} epochs",
        )
    allowed = set(info.supported_stages)
    bad = [stage for stage in result.stages if stage not in allowed]
    if bad:
        raise AnalysisError(
            "invalid_result",
            f"stager returned unsupported stage {bad[0]!r}",
        )
    if result.confidence is not None:
        if len(result.confidence) != epoch_count:
            raise AnalysisError("invalid_result", "confidence length does not match epochs")
        for value in result.confidence:
            if value is None:
                continue
            if not 0.0 <= float(value) <= 1.0:
                raise AnalysisError("invalid_result", "confidence must be between 0 and 1")
    if result.artifacts is not None and len(result.artifacts) != epoch_count:
        raise AnalysisError("invalid_result", "artifact length does not match epochs")
    if result.probabilities is not None:
        for stage, values in result.probabilities.items():
            if stage not in STAGES or len(values) != epoch_count:
                raise AnalysisError("invalid_result", f"invalid probabilities for {stage!r}")
    if result.beats_s is not None:
        beats = np.asarray(result.beats_s, dtype=np.float64)
        if beats.ndim != 1 or (beats.size > 1 and np.any(np.diff(beats) < 0)):
            raise AnalysisError("invalid_result", "beat times must be a sorted 1D sequence")


__all__ = [
    "AASM4_STAGES",
    "ALGORITHM_ID_RE",
    "CONTRACT_VERSION",
    "STAGES",
    "STREAM_NAMES",
    "WAKE_REM_NREM_STAGES",
    "WAKE_SLEEP_STAGES",
    "AccStream",
    "AlgorithmInfo",
    "AnalysisError",
    "ClockJump",
    "ClockSync",
    "EcgStream",
    "EpochGrid",
    "Gap",
    "HrStream",
    "InputQuality",
    "Recording",
    "RecordingEvent",
    "RecordingMeta",
    "RegisteredAlgorithm",
    "ResampledStream",
    "Stage",
    "Stager",
    "StagingResult",
    "StreamNeed",
    "StreamName",
    "StreamReport",
    "validate_staging_result",
]
