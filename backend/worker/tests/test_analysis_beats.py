from __future__ import annotations

import math

import numpy as np
import pytest
from taiga_worker.analysis.beats import (
    ECTOPIC,
    EXTRA,
    MISSED,
    NORMAL,
    SHORT,
    build_beat_series,
    classify_artifacts,
)

# One notification per beat, timestamped when the beat completed, with the arrival jitter
# a Bluetooth notification really has.
JITTER_S = 0.2


def _resting_rr(
    *,
    seed: int = 7,
    count: int = 600,
    mean_ms: float = 1000.0,
    spread_ms: float = 30.0,
) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return np.round(mean_ms + rng.normal(0.0, spread_ms, count)).astype(np.float64)


def _frames(rr_ms: np.ndarray, *, seed: int = 3, gap: range | None = None):
    """One frame per beat, at its completion time plus arrival jitter."""

    rng = np.random.default_rng(seed)
    ends_s = np.cumsum(rr_ms) / 1000.0
    times = ends_s + rng.uniform(-JITTER_S, JITTER_S, rr_ms.size)
    batches = [np.asarray([value], dtype=np.float64) for value in rr_ms]
    if gap is not None:
        for index in gap:
            batches[index] = np.empty(0, dtype=np.float64)
    return times, batches


def test_the_intervals_are_chained_exactly_through_arrival_jitter() -> None:
    rr_ms = _resting_rr()
    times, batches = _frames(rr_ms)

    series = build_beat_series(times, batches)
    assert series is not None

    # The reconstructed intervals are the reported ones, not the distance between the
    # times the notifications arrived.
    assert np.diff(series.times_s) * 1000.0 == pytest.approx(rr_ms, abs=1e-6)
    assert series.rr_ms == pytest.approx(rr_ms, abs=1e-6)
    assert series.count == rr_ms.size
    assert np.all(np.diff(series.times_s) > 0)


def test_a_frame_without_intervals_does_not_break_the_run() -> None:
    # Below about 60 bpm a notification can carry no complete interval. The next one
    # carries the intervals that completed in the meantime, so nothing is missing.
    rr_ms = _resting_rr()
    times, batches = _frames(rr_ms)
    batches[100] = np.empty(0, dtype=np.float64)
    batches[101] = np.asarray([rr_ms[100], rr_ms[101]])

    series = build_beat_series(times, batches)
    assert series is not None

    assert series.run_start.sum() == 0
    assert series.count == rr_ms.size


def test_a_gap_starts_a_new_run_and_no_difference_spans_it() -> None:
    rr_ms = _resting_rr()
    times, batches = _frames(rr_ms, gap=range(100, 150))

    series = build_beat_series(times, batches)
    assert series is not None

    assert series.count == rr_ms.size - 50
    assert np.count_nonzero(series.run_start) == 1
    # The break is one entry without an interval, so a successive difference cannot be
    # taken across it.
    boundary = int(np.nonzero(series.run_start)[0][0])
    assert np.isnan(series.rr_ms[boundary])
    assert series.times_s[boundary + 1] - series.times_s[boundary] > 1.0


def _packets(rr_ms: np.ndarray, duration_s: int) -> list[list[float]]:
    """One notification per second, carrying the intervals that completed in it."""

    packets: list[list[float]] = [[] for _ in range(duration_s)]
    for interval, end_s in zip(rr_ms, np.cumsum(rr_ms) / 1000.0, strict=True):
        second = int(math.ceil(end_s))
        if second >= duration_s:
            break
        packets[second].append(float(interval))
    return packets


def test_a_backlog_does_not_break_the_run() -> None:
    # Four notifications carry no interval, then one carries the intervals that completed
    # in the meantime. The chain runs ahead of the clock for a moment and the offset comes
    # back by itself; no beat was lost.
    rr_ms = np.full(200, 1333.0)
    packets = _packets(rr_ms, 200)
    backlog: list[float] = []
    for second in range(60, 64):
        backlog.extend(packets[second])
        packets[second] = []
    packets[64] = backlog + packets[64]

    series = build_beat_series(np.arange(1, len(packets) + 1), packets)
    assert series is not None
    assert series.run_start.sum() == 0
    assert series.count == int(sum(len(packet) for packet in packets))


def test_a_persistent_step_after_lost_beats_breaks_the_run() -> None:
    # Fifteen seconds of notifications and the beats in them really disappear; the offset
    # stays up by that time afterwards, which is the only thing that makes a break.
    rr_ms = np.full(200, 1333.0)
    packets = _packets(rr_ms, 200)
    for second in range(61, 76):
        packets[second] = []

    times = np.concatenate(
        [np.arange(1, 61, dtype=np.float64), np.arange(76, 201, dtype=np.float64)]
    )
    schedule = packets[:60] + packets[75:]
    series = build_beat_series(times, schedule)
    assert series is not None

    breaks = np.nonzero(series.run_start)[0]
    assert breaks.size == 1
    boundary = int(breaks[0])
    assert series.times_s[boundary + 1] - series.times_s[boundary] > 10.0
    assert series.count == int(sum(len(packet) for packet in schedule))


def test_a_clean_resting_series_has_no_artifact() -> None:
    rr_ms = _resting_rr()
    series = build_beat_series(*_frames(rr_ms))

    artifacts = classify_artifacts(series)

    assert artifacts.share == 0.0
    assert set(artifacts.labels) == {NORMAL}


def test_an_ectopic_pair_is_flagged_and_removed_from_the_beat_series() -> None:
    rr_ms = _resting_rr()
    rr_ms[300] = 600.0
    rr_ms[301] = 1400.0
    series = build_beat_series(*_frames(rr_ms))

    artifacts = classify_artifacts(series)

    flagged = set(np.nonzero(artifacts.flagged)[0])
    assert flagged == {300, 301}
    assert ECTOPIC in set(artifacts.labels[[300, 301]])


def test_a_missed_beat_is_flagged_as_a_missed_beat() -> None:
    rr_ms = _resting_rr()
    rr_ms[300] = 2.0 * rr_ms[300]
    series = build_beat_series(*_frames(rr_ms))

    artifacts = classify_artifacts(series)

    flagged = set(np.nonzero(artifacts.flagged)[0])
    assert flagged == {300}
    assert artifacts.labels[300] == MISSED


def test_an_extra_beat_is_flagged_as_an_extra_beat() -> None:
    rr_ms = _resting_rr()
    rr_ms[300] = rr_ms[300] / 2.0
    rr_ms[301] = rr_ms[301] / 2.0
    series = build_beat_series(*_frames(rr_ms))

    artifacts = classify_artifacts(series)

    flagged = set(np.nonzero(artifacts.flagged)[0])
    assert flagged == {300, 301}
    assert EXTRA in set(artifacts.labels[[300, 301]])
    assert SHORT in set(artifacts.labels[[300, 301]])


def test_one_run_is_classified_on_its_own_so_a_threshold_never_crosses_a_gap() -> None:
    rr_ms = _resting_rr()
    rr_ms[300] = 600.0
    rr_ms[301] = 1400.0
    times, batches = _frames(rr_ms, gap=range(150, 200))

    series = build_beat_series(times, batches)
    artifacts = classify_artifacts(series)

    # The pair sits after the gap and is still found, and no interval of the short run
    # before the gap borrows its neighbours from the long one.
    shifted = 300 - 50 + 1
    assert artifacts.labels[shifted] != NORMAL
    assert artifacts.labels[shifted + 1] != NORMAL
    assert artifacts.count(NORMAL) == series.count - 2
    assert artifacts.count("gap") == 1
