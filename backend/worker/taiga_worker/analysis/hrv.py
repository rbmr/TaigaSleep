"""Heart-rate variability from the raw R-R intervals.

Every figure here is computed from the beat series alone. Nothing reads the stages or any
other derived result, so the same recording gives the same HRV whatever the stager says:
a stager only decides how the night is described, never how variable its beats were.

The recording is cut into fixed five-minute windows. A window is summarised by its
RMSSD, its SDNN, the logarithm of the RMSSD, its mean heart rate and the share of its
intervals that were removed as artifacts. Windows enter the summary only when their data
is good enough: enough clean intervals, a high enough coverage of the window, an artifact
share at or below :data:`MAX_WINDOW_ARTIFACT_SHARE`, and, when the recording has an
accelerometer stream, little movement. That rule uses raw signals only. The summary
figure of each measure is the median over the included windows, so one disturbed window
does not move the night.

Successive differences are never taken across a removed beat or across a break in the
R-R data, because either one would read as a beat-to-beat change that never happened.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

from .beats import BeatArtifacts, BeatSeries, beat_series, classify_artifacts
from .contract import Recording
from .epochs import MOVEMENT_THRESHOLD_G

#: The window the figures are computed over, in seconds.
WINDOW_SECONDS = 300.0
#: A window needs this many clean intervals and this share of its time covered by them.
MIN_WINDOW_INTERVALS = 30
MIN_WINDOW_COVERAGE = 0.5
#: Fewer successive differences than this cannot describe the variability of a window.
MIN_WINDOW_DIFFERENCES = 20
#: Above this share of removed intervals the window is too disturbed to summarise.
MAX_WINDOW_ARTIFACT_SHARE = 0.05
#: A window still counts as calm when at most this share of its 30-second intervals
#: moved above the movement threshold.
MAX_WINDOW_MOVEMENT_SHARE = 0.2
#: The interval the movement rule is measured over, matching the movement figures.
MOVEMENT_EPOCH_SECONDS = 30.0

TOO_LITTLE_DATA = "too_little_data"
ARTIFACTS = "artifacts"
MOVEMENT = "movement"


@dataclass(frozen=True)
class HrvWindow:
    """One fixed window and the figures computed from it, included or not."""

    start_s: float
    rmssd_ms: float | None
    sdnn_ms: float | None
    ln_rmssd: float | None
    mean_hr_bpm: float | None
    artifact_share: float
    intervals: int
    included: bool
    reason: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "start_s": round(self.start_s, 3),
            "rmssd_ms": None if self.rmssd_ms is None else round(self.rmssd_ms, 1),
            "sdnn_ms": None if self.sdnn_ms is None else round(self.sdnn_ms, 1),
            "ln_rmssd": None if self.ln_rmssd is None else round(self.ln_rmssd, 3),
            "mean_hr_bpm": None if self.mean_hr_bpm is None else round(self.mean_hr_bpm, 1),
            "artifact_share": round(self.artifact_share, 4),
            "intervals": self.intervals,
            "included": self.included,
            "reason": self.reason,
        }


@dataclass(frozen=True)
class HrvResult:
    metrics: dict[str, Any]
    support: dict[str, Any]
    windows: tuple[HrvWindow, ...]


def hrv_metrics(recording: Recording) -> HrvResult:
    """The HRV figures of a recording, with their window series and support."""

    series = beat_series(recording)
    if series is None:
        status = "unsupported_device" if recording.hr is None else "insufficient_data"
        reason = "no_hr_stream" if recording.hr is None else "no_rr_intervals"
        return _missing(status, reason)

    artifacts = classify_artifacts(series)
    windows = _windows(recording, series, artifacts)
    included = [window for window in windows if window.included]

    metrics: dict[str, Any] = {
        "hrv_rmssd_ms": None,
        "hrv_sdnn_median_ms": None,
        "hrv_ln_rmssd": None,
        "hrv_artifact_share": round(artifacts.share, 4),
        "hrv_window_count": len(included),
        "hrv_rr_count": int(sum(window.intervals for window in included)),
    }
    support: dict[str, Any] = {key: _support("supported") for key in metrics}
    if not windows:
        support["hrv_window_count"] = _support(
            "insufficient_data", "recording_shorter_than_one_hrv_window"
        )
        support["hrv_rr_count"] = _support(
            "insufficient_data", "recording_shorter_than_one_hrv_window"
        )
    if not included:
        reason = "no_valid_hrv_windows" if windows else "recording_shorter_than_one_hrv_window"
        for key in ("hrv_rmssd_ms", "hrv_sdnn_median_ms", "hrv_ln_rmssd", "hrv_rr_count"):
            support[key] = _support("insufficient_data", reason)
        return HrvResult(metrics=metrics, support=support, windows=tuple(windows))

    metrics["hrv_rmssd_ms"] = _median((value.rmssd_ms for value in included), 1)
    metrics["hrv_sdnn_median_ms"] = _median((value.sdnn_ms for value in included), 1)
    metrics["hrv_ln_rmssd"] = _median((value.ln_rmssd for value in included), 3)
    return HrvResult(metrics=metrics, support=support, windows=tuple(windows))


def window_series(recording: Recording, windows: tuple[HrvWindow, ...]) -> dict[str, Any] | None:
    """The window series of the result, or ``None`` when the recording has no window."""

    if not windows:
        return None
    entries = [window.to_dict() for window in windows]
    keys = (
        "start_s",
        "rmssd_ms",
        "sdnn_ms",
        "ln_rmssd",
        "mean_hr_bpm",
        "artifact_share",
        "intervals",
        "included",
        "reason",
    )
    return {
        "window_seconds": int(WINDOW_SECONDS),
        "start_unix_ms": recording.start_unix_ns // 1_000_000,
        "count": len(entries),
        **{key: [entry[key] for entry in entries] for key in keys},
    }


def _windows(
    recording: Recording,
    series: BeatSeries,
    artifacts: BeatArtifacts,
) -> list[HrvWindow]:
    count = int(recording.duration_s // WINDOW_SECONDS)
    if count < 1:
        return []
    ends_s = series.times_s[1:]
    movement = _window_movement(recording, count)
    flagged = artifacts.flagged
    windows: list[HrvWindow] = []
    for index in range(count):
        start_s = index * WINDOW_SECONDS
        end_s = start_s + WINDOW_SECONDS
        left = int(np.searchsorted(ends_s, start_s, side="left"))
        right = int(np.searchsorted(ends_s, end_s, side="left"))
        window_rr = series.rr_ms[left:right]
        window_flagged = flagged[left:right]
        real = np.isfinite(window_rr)
        total = int(np.count_nonzero(real))
        clean = window_rr[real & ~window_flagged]
        share = float(np.count_nonzero(window_flagged[real]) / total) if total else 1.0

        # Differences are only taken between two adjacent real intervals of one run,
        # neither of which is a removed beat.
        pair = real[:-1] & real[1:] & ~window_flagged[:-1] & ~window_flagged[1:]
        differences = (window_rr[1:] - window_rr[:-1])[pair]
        differences = differences[np.isfinite(differences)]

        rmssd = float(np.sqrt(np.mean(differences**2))) if differences.size else None
        sdnn = float(np.std(clean, ddof=1)) if clean.size > 1 else None
        coverage = float(np.sum(clean)) / 1000.0 / WINDOW_SECONDS
        mean_rr = float(np.mean(clean)) if clean.size else 0.0

        included = True
        reason = ""
        if total == 0 or clean.size < MIN_WINDOW_INTERVALS or coverage < MIN_WINDOW_COVERAGE:
            included, reason = False, TOO_LITTLE_DATA
        elif differences.size < MIN_WINDOW_DIFFERENCES:
            included, reason = False, TOO_LITTLE_DATA
        elif share > MAX_WINDOW_ARTIFACT_SHARE:
            included, reason = False, ARTIFACTS
        elif movement[index] is not None and movement[index] > MAX_WINDOW_MOVEMENT_SHARE:
            included, reason = False, MOVEMENT

        windows.append(
            HrvWindow(
                start_s=start_s,
                rmssd_ms=rmssd,
                sdnn_ms=sdnn,
                ln_rmssd=float(np.log(rmssd)) if rmssd is not None and rmssd > 0 else None,
                mean_hr_bpm=float(60000.0 / mean_rr) if mean_rr > 0 else None,
                artifact_share=share,
                intervals=int(clean.size),
                included=included,
                reason=reason,
            )
        )
    return windows


def _window_movement(recording: Recording, count: int) -> list[float | None]:
    """Share of each window's 30-second intervals that moved.

    Movement is the deviation from the recording's own median magnitude, the same rule
    the movement figures use, so a constant sensor offset does not read as movement. A
    window without accelerometer samples has no share.
    """

    acc = recording.acc
    if acc is None or acc.t_s.size == 0:
        return [None] * count
    magnitude = acc.magnitude_g
    finite = np.isfinite(magnitude)
    if not np.any(finite):
        return [None] * count
    baseline = float(np.median(magnitude[finite]))
    deviation = np.where(finite, np.abs(magnitude - baseline), 0.0)
    cumulative = np.concatenate(([0.0], np.cumsum(deviation)))
    counted = np.concatenate(([0], np.cumsum(finite.astype(np.int64))))

    shares: list[float | None] = []
    for index in range(count):
        window_start = index * WINDOW_SECONDS
        moved = 0
        measured = 0
        for start in np.arange(window_start, window_start + WINDOW_SECONDS, MOVEMENT_EPOCH_SECONDS):
            left = int(np.searchsorted(acc.t_s, start, side="left"))
            right = int(np.searchsorted(acc.t_s, start + MOVEMENT_EPOCH_SECONDS, side="left"))
            samples = int(counted[right] - counted[left])
            if samples <= 0:
                continue
            measured += 1
            if float(cumulative[right] - cumulative[left]) / samples > MOVEMENT_THRESHOLD_G:
                moved += 1
        shares.append(float(moved) / measured if measured else None)
    return shares


def _median(values, digits: int) -> float | None:
    present = [float(value) for value in values if value is not None]
    return round(float(np.median(present)), digits) if present else None


def _support(status: str, reason: str | None = None) -> dict[str, Any]:
    return {"status": status, "reason": reason}


def _missing(status: str, reason: str) -> HrvResult:
    missing = _support(status, reason)
    metrics: dict[str, Any] = {
        "hrv_rmssd_ms": None,
        "hrv_sdnn_median_ms": None,
        "hrv_ln_rmssd": None,
        "hrv_artifact_share": None,
        "hrv_window_count": 0,
        "hrv_rr_count": 0,
    }
    return HrvResult(metrics=metrics, support={key: missing for key in metrics}, windows=())


__all__ = [
    "ARTIFACTS",
    "HrvResult",
    "HrvWindow",
    "MAX_WINDOW_ARTIFACT_SHARE",
    "MAX_WINDOW_MOVEMENT_SHARE",
    "MIN_WINDOW_COVERAGE",
    "MIN_WINDOW_DIFFERENCES",
    "MIN_WINDOW_INTERVALS",
    "MOVEMENT",
    "TOO_LITTLE_DATA",
    "WINDOW_SECONDS",
    "hrv_metrics",
    "window_series",
]
