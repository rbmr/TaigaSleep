"""A continuous beat series rebuilt from the raw R-R intervals.

The strap reports R-R intervals in bursts: every heart-rate notification carries the
intervals of the beats that completed since the previous notification, and the
notification's own timestamp jitters by tens to hundreds of milliseconds. Chaining the
intervals gives exact beat-to-beat times; the timestamps are used only to place a run on
the recording's clock and to find where beats were really lost. Reading an interval's
time from the notification that carried it, as the old per-frame reconstruction did, put
every interval of a burst on one time and made the reported variability depend on when
the phone happened to receive it.

A **run** is a stretch of beats that were reported without a loss. A loss shows as a
persistent step up in the offset between the notification clock and the chained beats:
the chain misses the beats of several seconds, so it stays behind the clock afterwards.
A backlog (a notification carrying more time than elapsed), a stretch of sparse
notifications and a late timestamp all move the offset down or up only for a while and
come back to where they were; none of them is a loss and none of them ends a run. No
successive difference is taken across a break.

Each run is anchored on the clock with the median offset of its own bursts, so the beats
keep their place across the whole recording even when the stream reports fewer intervals
than real time for a while.

:func:`beat_series` and :func:`classify_artifacts` are shared, so every consumer of
beats reads the same series. The HRV derivation uses both, and the stagers can use the
same series instead of rebuilding beats from a single frame at a time.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np

from .contract import Recording

# A loss must be a step that stays: the median offset of the bursts after it is above the
# median before it by more than one recent interval plus this margin, and it is still above
# it after a second window. A backlog or a late timestamp moves the offset by less than
# that or only for a while, so neither counts as a loss.
MAX_CHAIN_DRIFT_INTERVALS = 1.0
MAX_CHAIN_DRIFT_MARGIN_S = 0.5
# The offset is compared over this many bursts on each side of a possible break; the second
# window after the candidate is the same length. A transient of up to one window therefore
# cannot pass as a persistent step.
OFFSET_WINDOW_BURSTS = 6
# A window needs this many bursts before it can carry a verdict.
OFFSET_MIN_BURSTS = 3

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
    #: The interval ending at ``times_s[i + 1]``, in milliseconds. A break between runs
    #: is ``nan``: there is no interval there and no difference may be taken across it.
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

    Every interval is chained exactly. A run ends only where the offset between the
    notification clock and the chained beats steps up and stays up: that is the time of
    the beats that were never reported. A run is placed on the clock by the median offset
    of its bursts.
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

    bursts: list[tuple[float, np.ndarray]] = []
    for index in np.argsort(times, kind="stable"):
        if contact_array is not None and not contact_array[index]:
            continue
        values = np.asarray(intervals_ms[index], dtype=np.float64)
        values = values[np.isfinite(values) & (values > 0)]
        if values.size:
            bursts.append((float(times[index]), values))
    if not bursts:
        return None

    offsets = _burst_offsets(bursts)
    breaks = _loss_breaks(bursts, offsets)
    return _anchored_series(bursts, offsets, breaks)


def _burst_offsets(bursts: Sequence[tuple[float, np.ndarray]]) -> np.ndarray:
    """Notification time minus chained time of the burst's last beat, per burst.

    A healthy stream wanders inside one interval (the phase of the last beat and the
    arrival jitter). A backlog or a late timestamp moves the offset for a while; a loss
    raises it and it stays raised.
    """

    offsets = np.empty(len(bursts), dtype=np.float64)
    chained_s = 0.0
    for index, (time_s, values) in enumerate(bursts):
        chained_s += float(values.sum()) / 1000.0
        offsets[index] = time_s - chained_s
    return offsets


def _loss_breaks(bursts: Sequence[tuple[float, np.ndarray]], offsets: np.ndarray) -> list[int]:
    """Burst indices where beats were really lost, in order."""

    first_interval = [0]
    flat: list[np.ndarray] = []
    for _, values in bursts:
        flat.append(values)
        first_interval.append(first_interval[-1] + values.size)
    interval_ms = np.concatenate(flat)

    count = offsets.size
    persistent: dict[int, float] = {}
    for index in range(1, count):
        before = offsets[max(0, index - OFFSET_WINDOW_BURSTS) : index]
        near = offsets[index : min(count, index + OFFSET_WINDOW_BURSTS)]
        far = offsets[
            min(count, index + OFFSET_WINDOW_BURSTS) : min(count, index + 2 * OFFSET_WINDOW_BURSTS)
        ]
        if before.size < OFFSET_MIN_BURSTS or near.size < OFFSET_MIN_BURSTS:
            continue
        local = interval_ms[
            first_interval[max(0, index - OFFSET_WINDOW_BURSTS)] : first_interval[
                min(count, index + 2 * OFFSET_WINDOW_BURSTS)
            ]
        ]
        if local.size == 0:
            continue
        limit = (
            MAX_CHAIN_DRIFT_INTERVALS * float(np.median(local)) / 1000.0 + MAX_CHAIN_DRIFT_MARGIN_S
        )
        # A loss makes the offset jump up at one burst, where the beats of the missing
        # stretch would have been.
        if offsets[index] - offsets[index - 1] <= limit:
            continue
        base = float(np.median(before))
        step_near = float(np.median(near)) - base
        step_far = float(np.median(far)) - base if far.size >= OFFSET_MIN_BURSTS else step_near
        step = min(step_near, step_far)
        if step > limit:
            persistent[index] = step

    chosen: list[int] = []
    for index in sorted(persistent, key=lambda candidate: persistent[candidate], reverse=True):
        if all(abs(index - taken) > OFFSET_WINDOW_BURSTS for taken in chosen):
            chosen.append(index)
    return sorted(chosen)


def _anchored_series(
    bursts: Sequence[tuple[float, np.ndarray]],
    offsets: np.ndarray,
    breaks: Sequence[int],
) -> BeatSeries:
    """Place each run on the clock at its median offset and join them with breaks."""

    bounds = [0, *breaks, len(bursts)]
    beat_times: list[np.ndarray] = []
    rr_parts: list[np.ndarray] = []
    run_start_parts: list[np.ndarray] = []
    chained_s = 0.0

    for run, (first, last) in enumerate(zip(bounds[:-1], bounds[1:], strict=True)):
        anchor_s = float(np.median(offsets[first:last]))
        beats = [chained_s + anchor_s]
        values: list[float] = []
        for index in range(first, last):
            for value in bursts[index][1]:
                chained_s += float(value) / 1000.0
                values.append(float(value))
                beats.append(chained_s + anchor_s)
        beat_array = np.asarray(beats, dtype=np.float64)
        value_array = np.asarray(values, dtype=np.float64)
        if run == 0:
            beat_times.append(beat_array)
            rr_parts.append(value_array)
            run_start_parts.append(np.zeros(value_array.size, dtype=bool))
        else:
            # The break itself is one entry without an interval, so the interval list
            # stays aligned with every pair of beats.
            beat_times.append(beat_array[:1])
            rr_parts.append(np.asarray([np.nan]))
            run_start_parts.append(np.asarray([True]))
            beat_times.append(beat_array[1:])
            rr_parts.append(value_array)
            run_start_parts.append(np.zeros(value_array.size, dtype=bool))

    return BeatSeries(
        times_s=np.concatenate(beat_times),
        rr_ms=np.concatenate(rr_parts),
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
    "ECTOPIC",
    "EXTRA",
    "GAP",
    "LONG",
    "MAX_CHAIN_DRIFT_INTERVALS",
    "MAX_CHAIN_DRIFT_MARGIN_S",
    "MISSED",
    "NORMAL",
    "OFFSET_WINDOW_BURSTS",
    "SHORT",
    "beat_series",
    "build_beat_series",
    "classify_artifacts",
]
