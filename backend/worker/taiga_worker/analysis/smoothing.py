"""Small post-processing helpers shared by the built-in stagers.

Sleep is a slowly changing state, but a per-epoch classifier can flip labels
for a single 30 s window. A centered majority/mean filter removes that
flicker without changing the overall stage distribution. Unknown epochs are
never crossed: an artifact gap stays a gap, and its neighbours are smoothed
inside their own runs.
"""

from __future__ import annotations

from typing import Sequence

import numpy as np

from .contract import Stage


def smooth_labels(labels: Sequence[Stage], window_epochs: int) -> list[Stage]:
    """Centered majority filter over each contiguous non-unknown run."""

    values = list(labels)
    if window_epochs <= 1 or len(values) < 2:
        return values

    half = window_epochs // 2
    result = list(values)
    index = 0
    while index < len(values):
        if values[index] == "unknown":
            index += 1
            continue
        end = index
        while end < len(values) and values[end] != "unknown":
            end += 1
        run = values[index:end]
        if len(run) >= window_epochs:
            smoothed: list[Stage] = []
            for position in range(len(run)):
                low = max(0, position - half)
                high = min(len(run), position + half + 1)
                window = run[low:high]
                counts: dict[Stage, int] = {}
                for stage in window:
                    counts[stage] = counts.get(stage, 0) + 1
                best = max(counts.values())
                # Tie-break towards the original label so a stable run does not
                # drift just because a neighbouring stage is equally common.
                smoothed.append(run[position] if counts[run[position]] == best else max(counts, key=counts.get))
            result[index:end] = smoothed
        index = end
    return result


def smooth_values(
    values: Sequence[float | None],
    known: Sequence[bool],
    window_epochs: int,
) -> list[float | None]:
    """Centered mean over each contiguous known run; ``None`` stays ``None``."""

    result = list(values)
    mask = list(known)
    if window_epochs <= 1 or len(result) < 2:
        return result

    half = window_epochs // 2
    index = 0
    while index < len(result):
        if not mask[index]:
            index += 1
            continue
        end = index
        while end < len(result) and mask[end]:
            end += 1
        if end - index >= window_epochs:
            run = np.asarray(
                [np.nan if value is None else float(value) for value in result[index:end]],
                dtype=np.float64,
            )
            padded = np.pad(run, (half, half), mode="edge")
            smoothed = np.convolve(padded, np.ones(window_epochs) / window_epochs, mode="valid")
            for offset, value in enumerate(smoothed):
                result[index + offset] = None if not np.isfinite(value) else float(value)
        index = end
    return result


def smooth_probabilities(
    probabilities: np.ndarray,
    known: Sequence[bool],
    window_epochs: int,
) -> np.ndarray:
    """Centered moving average per class over each contiguous known run."""

    values = np.asarray(probabilities, dtype=np.float64).copy()
    mask = np.asarray(known, dtype=bool)
    if window_epochs <= 1 or values.shape[0] < 2:
        return values

    half = window_epochs // 2
    index = 0
    while index < values.shape[0]:
        if not mask[index]:
            index += 1
            continue
        end = index
        while end < values.shape[0] and mask[end]:
            end += 1
        if end - index >= window_epochs:
            for column in range(values.shape[1]):
                run = values[index:end, column]
                padded = np.pad(run, (half, half), mode="edge")
                values[index:end, column] = np.convolve(
                    padded, np.ones(window_epochs) / window_epochs, mode="valid"
                )
        index = end

    total = values.sum(axis=1, keepdims=True)
    values = np.divide(
        values,
        total,
        out=np.full_like(values, 1.0 / max(values.shape[1], 1), dtype=np.float64),
        where=total > 0,
    )
    return values


def unknown_runs(unusable: Sequence[bool], min_epochs: int) -> np.ndarray:
    """Return the unusable runs long enough to be reported as ``unknown``.

    A single bad epoch is still staged (and flagged as an artifact); it is not
    turned into a visible gap in the night.
    """

    mask = np.asarray(unusable, dtype=bool)
    if min_epochs <= 1 or mask.size == 0:
        return mask.copy()
    result = np.zeros(mask.shape, dtype=bool)
    index = 0
    while index < mask.size:
        if not mask[index]:
            index += 1
            continue
        end = index
        while end < mask.size and mask[end]:
            end += 1
        if end - index >= min_epochs:
            result[index:end] = True
        index = end
    return result


__all__ = ["smooth_labels", "smooth_probabilities", "smooth_values", "unknown_runs"]
