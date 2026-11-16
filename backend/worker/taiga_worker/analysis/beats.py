"""A continuous beat series rebuilt from the raw R-R intervals.

The strap reports R-R intervals in bursts: every heart-rate notification carries the
intervals of the beats that completed since the previous notification, and the
notification's own timestamp jitters by tens to hundreds of milliseconds. Chaining the
intervals gives exact beat-to-beat times; the timestamps are used only to anchor a run
on the recording's clock and to find its breaks. Reading an interval's time from the
notification that carried it, as the old per-frame reconstruction did, put every
interval of a burst on one time and made the reported variability depend on when the
phone happened to receive it.

A **run** is a stretch of intervals that were reported without a break. A new run starts
when a burst covers more than one recent interval's worth less time than elapsed since the
previous burst, which is what a lost notification or a real gap leaves behind; the phase of
a notification and its arrival jitter do not. No successive difference is taken across that
boundary. A notification without intervals is normal below about 60 bpm and does not start a
new run: the next notification simply carries the intervals that completed in the meantime.

Each burst is anchored where its own notification puts it once the time no interval covers
is more than a notification's phase could explain, so a stream that reports fewer intervals
than real time never drifts away from the recording's clock. A healthy stream is chained
exactly, so its beat-to-beat times stay exact.

:func:`beat_series` and :func:`classify_artifacts` are shared, so every consumer of
beats reads the same series. The HRV derivation uses both, and the stagers can use the
same series instead of rebuilding beats from a single frame at a time.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np

from .contract import Recording

# A burst can cover less time than has elapsed since the previous one without any beat
# being lost: the phone stamps a whole SDK batch with one arrival time, a burst may carry
# no complete interval, and the last beat of a burst sits somewhere inside the interval
# before it. The allowed shortfall therefore follows the interval actually being reported
# instead of a fixed number of seconds; a break means more than a whole interval of time
# is unaccounted for, not that the notification's phase moved.
MAX_CHAIN_DRIFT_INTERVALS = 1.0
MAX_CHAIN_DRIFT_MARGIN_S = 0.5
# A stream that reports fewer intervals than real time would otherwise drift away from the
# clock until every run breaks. Once the lag is more than this many intervals, no phase can
# explain it, so the next burst is placed back on the clock and the run goes on without a
# break. It is above the phase a healthy notification can have (less than one interval)
# and below the break bound above.
CHAIN_RESYNC_INTERVALS = 1.25
# The shortfall is scaled by the median of this many most recent intervals, so a single
# artifact or a burst of two beats does not set it.
DRIFT_INTERVAL_HISTORY = 31

# The artifact classifier: Lipponen & Tarvainen (2019), "A robust algorithm for heart
# rate variability time series artefact correction using novel beat classification",
# Journal of Medical Engineering & Technology 43(3), 173-181. The threshold is 5.2 times
# the quartile deviation of the surrounding successive differences, which adapts it to
# the heart rate and to the variability of the stretch. The slope and intercept of the
# decision boundaries are the published constants.
ARTIFACT_ALPHA = 5.2
ARTIFACT_C1 = 0.13
ARTIFACT_C2 = 0.17
# The published thresholds use the 91 successive differences around a beat, and the
# 11-beat median as the local interval.
ARTIFACT_DIFFERENCE_BEATS = 91
ARTIFACT_MEDIAN_BEATS = 11
# A perfectly regular synthetic stretch has a quartile deviation of zero, which would
# make every threshold zero. The floor only guards that degenerate distribution: a normal
# sinus rhythm has a far larger quartile deviation, and an artifact is much further from
# its neighbours than 20 ms.
ARTIFACT_MIN_THRESHOLD_MS = 20.0
# A run shorter than this cannot pass the edge rules below, so it carries no verdict.
ARTIFACT_MIN_RUN_INTERVALS = 8

NORMAL = "normal"
GAP = "gap"
ECTOPIC = "ectopic"
MISSED = "missed"
EXTRA = "extra"
LONG = "long"
SHORT = "short"


@dataclass(frozen=True)
class BeatSeries:
    """Beats on the recording's clock, in runs that a difference cannot span."""

    #: Beat times in seconds since the recording's start, in increasing order.
    times_s: np.ndarray
    #: The interval the strap reported between the beats at ``times_s[i]`` and
    #: ``times_s[i + 1]``, in milliseconds. At a burst boundary the two times can sit
    #: slightly closer or further apart than the interval, by the time the burst did not
    #: account for. A break between runs is ``nan``.
    rr_ms: np.ndarray
    #: True where ``rr_ms`` is the break between two runs.
    run_start: np.ndarray

    @property
    def count(self) -> int:
        """The number of real intervals, leaving out the breaks between runs."""

        return int(np.count_nonzero(np.isfinite(self.rr_ms)))


@dataclass(frozen=True)
class BeatArtifacts:
    """One label per interval; ``flagged`` is every real interval that is not normal."""

    labels: np.ndarray

    @property
    def flagged(self) -> np.ndarray:
        return (self.labels != NORMAL) & (self.labels != GAP)

    @property
    def share(self) -> float:
        real = self.labels != GAP
        return float(np.mean(self.flagged[real])) if np.any(real) else 0.0

    def count(self, label: str) -> int:
        return int(np.count_nonzero(self.labels == label))


def beat_series(recording: Recording) -> BeatSeries | None:
    """The beat series of a recording, or ``None`` when it reported no R-R intervals."""

    hr = recording.hr
    if hr is None or not hr.rr_ms:
        return None
    contact = hr.contact if np.any(hr.contact_supported) else None
    return build_beat_series(hr.t_s, hr.rr_ms, contact=contact)


def build_beat_series(
    times_s: Sequence[float],
    intervals_ms: Sequence[Sequence[float]],
    *,
    contact: Sequence[bool] | None = None,
) -> BeatSeries | None:
    """Chain the intervals of consecutive notifications into a beat series.

    ``times_s`` is the notification time of every frame and ``intervals_ms`` the
    intervals it carried, both in the frame order. A frame without contact is skipped
    when the device reports contact at all, because the intervals of a lost contact are
    not beats. A non-finite or non-positive interval is not a beat interval and is
    dropped.

    A burst starts a new run when it covers more than one recent interval's worth less
    time than elapsed since the previous burst. The comparison is against the previous
    burst's own time, so a constant delay in stamping cancels: what is left is the time no
    interval accounts for, which only a real gap makes large.
    """

    times = np.asarray(times_s, dtype=np.float64)
    if times.ndim != 1 or len(intervals_ms) != times.size:
        raise ValueError("one interval list per frame time is required")
    if contact is not None:
        contact_array = np.asarray(contact, dtype=bool)
        if contact_array.size != times.size:
            raise ValueError("one contact flag per frame time is required")
    else:
        contact_array = None

    order = np.argsort(times, kind="stable")
    beat_times: list[np.ndarray] = []
    rr_parts: list[np.ndarray] = []
    run_start_parts: list[np.ndarray] = []
    history: list[float] = []
    last_beat_s: float | None = None

    for index in order:
        if contact_array is not None and not contact_array[index]:
            continue
        values = np.asarray(intervals_ms[index], dtype=np.float64)
        values = values[np.isfinite(values) & (values > 0)]
        if values.size == 0:
            continue
        rr_s = values / 1000.0
        history.extend(float(value) for value in values)
        if len(history) > DRIFT_INTERVAL_HISTORY:
            del history[: len(history) - DRIFT_INTERVAL_HISTORY]
        interval_s = float(np.median(history)) / 1000.0
        drift_limit_s = MAX_CHAIN_DRIFT_INTERVALS * interval_s + MAX_CHAIN_DRIFT_MARGIN_S

        # The interval series of a burst ends at the notification's own time, so the
        # burst's first beat sits one total interval before it. Comparing that with the
        # previous burst's time leaves exactly the time that no interval covers.
        frame_start_s = float(times[index]) - float(rr_s.sum())
        if last_beat_s is None:
            new_run = True
            start_s = frame_start_s
        else:
            lag_s = frame_start_s - last_beat_s
            new_run = abs(lag_s) > drift_limit_s
            if new_run or abs(lag_s) > CHAIN_RESYNC_INTERVALS * interval_s:
                # Either a real gap, or a stream that keeps reporting fewer intervals than
                # real time: place the burst where its own notification puts it, so the
                # missing time is absorbed instead of accumulating over the night.
                start_s = max(frame_start_s, last_beat_s)
            else:
                # A healthy burst: chaining keeps every beat-to-beat time exact.
                start_s = last_beat_s
        beats = start_s + np.cumsum(rr_s)
        if new_run:
            run_starts = np.zeros(values.size, dtype=bool)
            if last_beat_s is None:
                beat_times.append(np.concatenate(([start_s], beats)).astype(np.float64))
                rr_parts.append(values)
                run_start_parts.append(run_starts)
            else:
                # The break itself is one entry without an interval, so the interval
                # list stays aligned with every pair of beats.
                beat_times.append(np.asarray([start_s], dtype=np.float64))
                rr_parts.append(np.asarray([np.nan]))
                run_start_parts.append(np.asarray([True]))
                beat_times.append(beats.astype(np.float64))
                rr_parts.append(values)
                run_start_parts.append(run_starts)
                history = [float(value) for value in values]
        else:
            assert last_beat_s is not None
            beat_times.append(beats.astype(np.float64))
            rr_parts.append(values)
            run_start_parts.append(np.zeros(values.size, dtype=bool))
        last_beat_s = float(beats[-1])

    if not rr_parts:
        return None
    return BeatSeries(
        times_s=np.concatenate(beat_times).astype(np.float64),
        rr_ms=np.concatenate(rr_parts).astype(np.float64),
        run_start=np.concatenate(run_start_parts),
    )


def classify_artifacts(series: BeatSeries) -> BeatArtifacts:
    """Label every interval normal, ectopic, missed, extra, long or short.

    The breaks between runs are labelled ``gap``; they are not intervals of the beat
    series and are never counted as artifacts.
    """

    labels = np.full(series.rr_ms.size, NORMAL, dtype=object)
    labels[~np.isfinite(series.rr_ms)] = GAP
    start = 0
    for index in range(series.rr_ms.size + 1):
        if index == series.rr_ms.size or not np.isfinite(series.rr_ms[index]):
            if index > start:
                labels[start:index] = _classify_run(series.rr_ms[start:index])
            start = index + 1
    return BeatArtifacts(labels=labels.astype("<U8"))


def _classify_run(rr_ms: np.ndarray) -> np.ndarray:
    """One run, classified on its own so no threshold or difference crosses a gap."""

    count = rr_ms.size
    labels = np.full(count, NORMAL, dtype=object)
    if count < ARTIFACT_MIN_RUN_INTERVALS:
        return labels

    # Successive differences, normalised by the adaptive threshold of their neighbourhood.
    differences = np.diff(rr_ms, prepend=0.0)
    differences[0] = float(np.mean(differences[1:]))
    threshold = _threshold(differences)
    normalised = differences / threshold

    # Each interval against the median of its neighbourhood: a missed beat doubles one
    # interval, an extra beat halves two of them, so they meet after the scaling rule.
    median_rr = _rolling(rr_ms, ARTIFACT_MEDIAN_BEATS, np.median)
    deviations = rr_ms - median_rr
    deviations = np.where(deviations < 0.0, 2.0 * deviations, deviations)
    deviation_threshold = _threshold(deviations)
    normalised_deviation = deviations / deviation_threshold

    # Subspace 1: a beat and the larger (or smaller) of its neighbours. Ectopic beats form
    # a short-long or long-short pair, so both values grow together.
    before = np.concatenate(([0.0], normalised[:-1]))
    after = np.concatenate((normalised[1:], [0.0]))
    larger = np.maximum(before, after)
    smaller = np.minimum(before, after)
    neighbour = np.where(normalised < 0.0, smaller, larger)

    # Subspace 2: a beat and the larger (or smaller) of the two differences that follow.
    next_one = np.concatenate((normalised[1:], [0.0]))
    next_two = np.concatenate((normalised[2:], [0.0, 0.0]))
    following = np.where(
        normalised >= 0.0,
        np.minimum(next_one, next_two),
        np.maximum(next_one, next_two),
    )

    ectopic = ((normalised > 1.0) & (neighbour < (-ARTIFACT_C1 * normalised - ARTIFACT_C2))) | (
        (normalised < -1.0) & (neighbour > (-ARTIFACT_C1 * normalised + ARTIFACT_C2))
    )
    # The decision uses neighbouring beats, so the ends of a run have no verdict.
    if count >= 4:
        ectopic[:2] = False
        ectopic[-2:] = False
    else:
        ectopic[:] = False

    long_beats = ((normalised > 1.0) & (following < -1.0)) | (
        (np.abs(normalised_deviation) > 3.0) & (rr_ms > median_rr)
    )
    short_beats = ((normalised < -1.0) & (following > 1.0)) | (
        (np.abs(normalised_deviation) > 3.0) & (rr_ms <= median_rr)
    )
    # An outlier can also show up only on the interval that follows it.
    for condition in (long_beats, short_beats):
        for index in range(count - 2):
            if condition[index] and abs(normalised[index + 1]) < abs(normalised[index + 2]):
                condition[index + 1] = True

    short_beats[ectopic] = False
    long_beats[ectopic] = False

    missed = (np.abs(rr_ms / 2.0 - median_rr) < deviation_threshold) & long_beats
    long_beats[missed] = False
    following_rr = np.concatenate((rr_ms[1:], [0.0]))
    extra = (np.abs(rr_ms + following_rr - median_rr) < deviation_threshold) & short_beats
    short_beats[extra] = False
    short_beats[[0, -1]] = False
    long_beats[[0, -1]] = False

    labels[short_beats] = SHORT
    labels[long_beats] = LONG
    labels[missed] = MISSED
    labels[extra] = EXTRA
    labels[ectopic] = ECTOPIC
    return labels


def _threshold(deviations: np.ndarray) -> np.ndarray:
    """``alpha`` times the quartile deviation of the surrounding values, with a floor.

    The quartile deviation is taken over the values as they are signed. Taking their
    absolute values first would collapse the spread around zero and make the threshold far
    too tight, so ordinary beats would be flagged instead of artifacts.
    """

    lower = _rolling(
        deviations,
        ARTIFACT_DIFFERENCE_BEATS,
        lambda values, axis: np.quantile(values, 0.25, axis=axis),
    )
    upper = _rolling(
        deviations,
        ARTIFACT_DIFFERENCE_BEATS,
        lambda values, axis: np.quantile(values, 0.75, axis=axis),
    )
    return np.maximum(ARTIFACT_ALPHA * (upper - lower) / 2.0, ARTIFACT_MIN_THRESHOLD_MS)


def _rolling(values: np.ndarray, size: int, reduce) -> np.ndarray:
    """``reduce`` over a centred window of ``size`` values.

    The edges reflect the values inside the run instead of repeating the outermost one:
    repeating it would shrink the window's spread and make the threshold too tight at
    the start and end of a run.
    """

    if values.size == 0:
        return values
    half = size // 2
    padded = np.pad(values, half, mode="reflect" if values.size > 1 else "edge")
    windows = np.lib.stride_tricks.sliding_window_view(padded, size)
    return np.asarray(reduce(windows, axis=1), dtype=np.float64)


__all__ = [
    "ARTIFACT_MIN_THRESHOLD_MS",
    "BeatArtifacts",
    "BeatSeries",
    "CHAIN_RESYNC_INTERVALS",
    "ECTOPIC",
    "EXTRA",
    "GAP",
    "LONG",
    "MAX_CHAIN_DRIFT_INTERVALS",
    "MAX_CHAIN_DRIFT_MARGIN_S",
    "MISSED",
    "NORMAL",
    "SHORT",
    "beat_series",
    "build_beat_series",
    "classify_artifacts",
]
