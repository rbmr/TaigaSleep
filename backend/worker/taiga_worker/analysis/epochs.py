"""Canonical 30-second epochs and resampling helpers.

Epochs are aligned to the recording start and cover the whole recording. A
stager may use any internal window size, but its output is aggregated to this
grid so hypnograms and metrics remain comparable across algorithms.
"""

from __future__ import annotations

import math
from typing import Mapping

import numpy as np

from .contract import (
    AccStream,
    AnalysisError,
    EcgStream,
    EpochGrid,
    HrStream,
    Recording,
    ResampledStream,
    StreamName,
)


def _bins(t_s: np.ndarray, starts_s: np.ndarray, ends_s: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    if t_s.size == 0:
        return np.zeros(starts_s.size, dtype=np.int64), np.zeros(starts_s.size, dtype=np.int64)
    left = np.searchsorted(t_s, starts_s, side="left")
    right = np.searchsorted(t_s, ends_s, side="left")
    counts = np.maximum(0, right - left)
    return left, counts


def _coverage(counts: np.ndarray, epoch_seconds: int, rate_hz: float | None) -> np.ndarray:
    if rate_hz is None or rate_hz <= 0:
        return np.zeros(counts.size, dtype=np.float64)
    expected = epoch_seconds * rate_hz
    return np.clip(counts.astype(np.float64) / expected, 0.0, 1.0)


def build_epoch_grid(recording: Recording, epoch_seconds: int = 30) -> EpochGrid:
    if epoch_seconds <= 0:
        raise ValueError("epoch_seconds must be positive")

    duration_s = max(0.0, recording.duration_s)
    count = max(1, int(math.ceil(duration_s / epoch_seconds)))
    starts_s = np.arange(count, dtype=np.float64) * epoch_seconds
    ends_s = starts_s + epoch_seconds

    coverage: dict[str, np.ndarray] = {}
    hr_contact = np.zeros(count, dtype=np.float64)
    hr_mean = np.full(count, np.nan, dtype=np.float64)
    hr_std = np.full(count, np.nan, dtype=np.float64)
    hr_count = np.zeros(count, dtype=np.int64)
    ecg_saturated = np.zeros(count, dtype=np.float64)
    movement = np.full(count, np.nan, dtype=np.float64)
    acc_count = np.zeros(count, dtype=np.int64)

    hr = recording.hr
    if hr is not None and hr.t_s.size:
        left, counts = _bins(hr.t_s, starts_s, ends_s)
        hr_count = counts
        coverage["hr"] = _coverage(counts, epoch_seconds, hr.rate_hz or 1.0)

        contact_prefix = np.concatenate(([0], np.cumsum(hr.contact.astype(np.int64))))
        contact_count = contact_prefix[left + counts] - contact_prefix[left]
        hr_contact = np.divide(
            contact_count.astype(np.float64),
            counts,
            out=np.zeros(count, dtype=np.float64),
            where=counts > 0,
        )

        valid = hr.valid & np.isfinite(hr.hr_bpm)
        values = np.where(valid, hr.hr_bpm, 0.0).astype(np.float64)
        values_sq = np.where(valid, hr.hr_bpm.astype(np.float64) ** 2, 0.0)
        valid_prefix = np.concatenate(([0], np.cumsum(valid.astype(np.int64))))
        value_prefix = np.concatenate(([0], np.cumsum(values)))
        value_sq_prefix = np.concatenate(([0], np.cumsum(values_sq)))
        right = left + counts
        valid_count = valid_prefix[right] - valid_prefix[left]
        value_sum = value_prefix[right] - value_prefix[left]
        value_sq_sum = value_sq_prefix[right] - value_sq_prefix[left]
        mean = np.divide(
            value_sum,
            valid_count,
            out=np.full(count, np.nan, dtype=np.float64),
            where=valid_count > 0,
        )
        variance = np.divide(
            value_sq_sum,
            valid_count,
            out=np.full(count, np.nan, dtype=np.float64),
            where=valid_count > 0,
        ) - mean**2
        hr_mean = mean
        hr_std = np.sqrt(np.clip(variance, 0.0, None))
    else:
        coverage["hr"] = np.zeros(count, dtype=np.float64)

    ecg = recording.ecg
    if ecg is not None and ecg.t_s.size:
        left, counts = _bins(ecg.t_s, starts_s, ends_s)
        coverage["ecg"] = _coverage(counts, epoch_seconds, ecg.rate_hz)
        sat_prefix = np.concatenate(([0], np.cumsum(ecg.saturated.astype(np.int64))))
        right = left + counts
        sat_count = sat_prefix[right] - sat_prefix[left]
        ecg_saturated = np.divide(
            sat_count.astype(np.float64),
            counts,
            out=np.zeros(count, dtype=np.float64),
            where=counts > 0,
        )
    else:
        coverage["ecg"] = np.zeros(count, dtype=np.float64)

    acc = recording.acc
    if acc is not None and acc.t_s.size:
        left, counts = _bins(acc.t_s, starts_s, ends_s)
        acc_count = counts
        coverage["acc"] = _coverage(counts, epoch_seconds, acc.rate_hz)
        magnitude = acc.magnitude_g
        deviation = np.abs(magnitude - 1.0)
        dev_prefix = np.concatenate(([0], np.cumsum(deviation)))
        right = left + counts
        dev_sum = dev_prefix[right] - dev_prefix[left]
        movement = np.divide(
            dev_sum,
            counts,
            out=np.full(count, np.nan, dtype=np.float64),
            where=counts > 0,
        )
    else:
        coverage["acc"] = np.zeros(count, dtype=np.float64)

    return EpochGrid(
        epoch_seconds=epoch_seconds,
        start_unix_ns=recording.start_unix_ns,
        count=count,
        starts_s=starts_s,
        ends_s=ends_s,
        coverage=coverage,
        hr_contact_fraction=hr_contact,
        hr_mean_bpm=hr_mean,
        hr_std_bpm=hr_std,
        hr_sample_count=hr_count,
        ecg_saturated_fraction=ecg_saturated,
        movement_g=movement,
        acc_sample_count=acc_count,
    )


def _uniform_grid(duration_s: float, rate_hz: float) -> np.ndarray:
    if rate_hz <= 0:
        raise ValueError("rate_hz must be positive")
    count = max(1, int(math.ceil(max(0.0, duration_s) * rate_hz)))
    return np.arange(count, dtype=np.float64) / rate_hz


def _nearest_indices(source_t: np.ndarray, target_t: np.ndarray) -> np.ndarray:
    if source_t.size == 0:
        return np.zeros(target_t.size, dtype=np.int64)
    right = np.clip(np.searchsorted(source_t, target_t, side="left"), 0, source_t.size - 1)
    left = np.maximum(right - 1, 0)
    choose_left = np.abs(target_t - source_t[left]) <= np.abs(target_t - source_t[right])
    return np.where(choose_left, left, right)


def _nearest_valid(
    source_t: np.ndarray,
    target_t: np.ndarray,
    *,
    source_valid: np.ndarray | None = None,
    max_gap_s: float,
) -> np.ndarray:
    if source_t.size == 0:
        return np.zeros(target_t.size, dtype=bool)
    nearest = _nearest_indices(source_t, target_t)
    valid = np.abs(target_t - source_t[nearest]) <= max_gap_s
    if source_valid is not None:
        valid &= source_valid[nearest]
    return valid


def resample_stream(recording: Recording, name: StreamName, rate_hz: float) -> ResampledStream:
    stream = getattr(recording, name)
    if stream is None:
        raise AnalysisError("missing_stream", f"recording has no {name} stream")
    t_target = _uniform_grid(recording.duration_s, rate_hz)
    max_gap_s = max(2.0 / max(rate_hz, 0.1), 2.0 / max(stream.rate_hz, 0.1), 1.0)

    if name == "hr":
        assert isinstance(stream, HrStream)
        if stream.t_s.size == 0:
            values = np.full(t_target.shape, np.nan, dtype=np.float32)
            return ResampledStream(t_target, values, rate_hz, np.zeros(t_target.shape, dtype=bool))
        values = np.interp(t_target, stream.t_s, stream.hr_bpm).astype(np.float32)
        valid = _nearest_valid(
            stream.t_s,
            t_target,
            source_valid=stream.valid,
            max_gap_s=max_gap_s,
        )
        values[~valid] = np.nan
        return ResampledStream(t_target, values, rate_hz, valid)

    if name == "ecg":
        assert isinstance(stream, EcgStream)
        if stream.t_s.size == 0:
            values = np.full(t_target.shape, np.nan, dtype=np.float32)
            return ResampledStream(t_target, values, rate_hz, np.zeros(t_target.shape, dtype=bool))
        values = np.interp(t_target, stream.t_s, stream.voltage_mv).astype(np.float32)
        valid = _nearest_valid(
            stream.t_s,
            t_target,
            source_valid=~stream.saturated,
            max_gap_s=max_gap_s,
        )
        values[~valid] = np.nan
        return ResampledStream(t_target, values, rate_hz, valid)

    assert isinstance(stream, AccStream)
    if stream.t_s.size == 0:
        values = np.full((t_target.size, 3), np.nan, dtype=np.float32)
        return ResampledStream(t_target, values, rate_hz, np.zeros(t_target.shape, dtype=bool))
    values = np.stack(
        [np.interp(t_target, stream.t_s, stream.xyz_g[:, axis]) for axis in range(3)],
        axis=1,
    ).astype(np.float32)
    valid = _nearest_valid(stream.t_s, t_target, max_gap_s=max_gap_s)
    values[~valid, :] = np.nan
    return ResampledStream(t_target, values, rate_hz, valid)


__all__ = ["build_epoch_grid", "resample_stream"]
