from __future__ import annotations

import numpy as np
from taiga_worker.analysis.smoothing import (
    smooth_labels,
    smooth_probabilities,
    unknown_runs,
)


def test_smooth_labels_removes_single_epoch_flicker() -> None:
    labels = ["light"] * 4 + ["deep"] + ["light"] * 4
    smoothed = smooth_labels(labels, 5)
    assert smoothed == ["light"] * 9


def test_smooth_labels_does_not_cross_unknown_gaps() -> None:
    labels = ["light", "light", "unknown", "unknown", "deep", "deep"]
    assert smooth_labels(labels, 5) == labels


def test_smooth_labels_is_identity_for_window_one() -> None:
    labels = ["wake", "light", "deep", "rem"]
    assert smooth_labels(labels, 1) == labels


def test_smooth_probabilities_renormalizes_and_preserves_unknown() -> None:
    probabilities = np.asarray(
        [
            [0.9, 0.1],
            [0.1, 0.9],
            [0.9, 0.1],
        ],
        dtype=np.float64,
    )
    smoothed = smooth_probabilities(probabilities, [True, False, True], 3)
    assert np.allclose(smoothed.sum(axis=1), 1.0)
    assert np.array_equal(smoothed[0], probabilities[0])
    assert np.array_equal(smoothed[2], probabilities[2])


def test_unknown_runs_keeps_only_long_gaps() -> None:
    unusable = [False, True, False, True, True, True, False, True]
    assert np.array_equal(
        unknown_runs(unusable, 1),
        np.asarray(unusable, dtype=bool),
    )
    assert np.array_equal(
        unknown_runs(unusable, 3),
        np.asarray([False, False, False, True, True, True, False, False]),
    )
