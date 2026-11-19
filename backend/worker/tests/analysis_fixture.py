"""Synthetic recording directories for analysis tests.

Everything is generated at test time. The layout follows
docs/recording-format.md and comes from the worker's frame module; no health
data is committed.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
from taiga_worker import frames

POLAR_EPOCH_OFFSET_NS = frames.POLAR_EPOCH_OFFSET_NS
STREAM_HR = frames.STREAM_HR
STREAM_ECG = frames.STREAM_ECG
STREAM_ACC = frames.STREAM_ACC
STREAM_SOUND = frames.STREAM_SOUND

START_UTC_MS = 1_792_798_200_000
START_NS = START_UTC_MS * 1_000_000
POLAR_START_NS = START_NS - POLAR_EPOCH_OFFSET_NS

# A plausible resting R-R series: about one beat per second, with a few tens of
# milliseconds of beat-to-beat variation. It is spread over the heart-rate frames by
# the time each beat completes, the way the strap reports it.
DEFAULT_RR_MS = (1000, 980, 1020, 990, 1010)

frame_header = frames.frame_header
event_line = frames.event_line


def frame(payload: bytes, *, bad_crc: bool = False) -> bytes:
    """One frame; bad_crc breaks its checksum, for the recovery rule."""

    encoded = frames.encode_frame(payload)
    if not bad_crc:
        return encoded
    broken = int.from_bytes(encoded[-4:], "little") ^ 0xFFFFFFFF
    return encoded[:-4] + broken.to_bytes(4, "little")


def hr_payload(
    phone_ns: int,
    elapsed_ns: int,
    hr_bpm: int,
    flags: int = 3,
    rr_ms: Iterable[int] = (1000,),
) -> bytes:
    return frames.hr_payload(phone_ns, elapsed_ns, hr_bpm, flags, rr_ms)


def ecg_payload(
    phone_ns: int,
    elapsed_ns: int,
    t_first: int,
    t_last: int,
    voltage_uv: Sequence[int],
    *,
    values_i16: bool | None = None,
) -> bytes:
    values = list(voltage_uv)
    return frames.ecg_payload(
        phone_ns,
        elapsed_ns,
        frames.linear_timestamps(t_first, t_last, len(values)),
        values,
        values_i16=values_i16,
    )


def acc_payload(
    phone_ns: int,
    elapsed_ns: int,
    t_first: int,
    t_last: int,
    xyz_mg: Sequence[int],
) -> bytes:
    values = list(xyz_mg)
    return frames.acc_payload(
        phone_ns,
        elapsed_ns,
        frames.linear_timestamps(t_first, t_last, len(values) // 3),
        values,
    )


def stream_payload(stream: int, index: int, *, samples: int = 10) -> bytes:
    """One payload of ``stream`` for second ``index`` of a synthetic frame log."""

    phone_ns = START_NS + index * 1_000_000_000
    elapsed_ns = index * 1_000_000_000
    t_first = POLAR_START_NS + index * 1_000_000_000
    if stream == STREAM_HR:
        return hr_payload(phone_ns, elapsed_ns, 60 + index % 10)
    spacing = 1_000_000_000 // samples
    t_last = t_first + (samples - 1) * spacing
    if stream == STREAM_ECG:
        return ecg_payload(phone_ns, elapsed_ns, t_first, t_last, [0] * samples)
    values = [0, 0, 1000] * samples
    return acc_payload(phone_ns, elapsed_ns, t_first, t_last, values)


def meta(
    session_id: str = "20261023_223000",
    *,
    duration_s: int = 120,
    state: str = "stopped",
    hr: bool = True,
    ecg: bool = True,
    acc: bool = True,
) -> dict:
    end = None if state == "recording" else START_UTC_MS + duration_s * 1000
    return {
        "format": "taiga-recording",
        "version": 1,
        "session_id": session_id,
        "state": state,
        "start_utc_ms": START_UTC_MS,
        "end_utc_ms": end,
        "timezone": "Europe/Amsterdam",
        "app": {"version": "0.1.0", "version_code": 1},
        "phone": {"manufacturer": "Test", "model": "Synthetic", "android": "16", "sdk": 36},
        "sensor": {
            "kind": "polar-h10",
            "id": "TEST0001",
            "name": "Polar H10 TEST0001",
            "firmware": "3.0.35",
            "battery_percent_at_start": 90,
        },
        "config": {
            "hr": hr,
            "ecg": ecg,
            "acc": acc,
            "ecg_rate_hz": 130,
            "acc_rate_hz": 50,
            "acc_range_g": 8,
        },
    }


def default_rr_series(duration_s: int) -> list[int]:
    """A resting R-R series that covers the whole recording, about one beat a second."""

    series: list[int] = []
    elapsed_ms = 0
    index = 0
    while elapsed_ms < duration_s * 1000:
        interval = DEFAULT_RR_MS[index % len(DEFAULT_RR_MS)]
        series.append(interval)
        elapsed_ms += interval
        index += 1
    return series


def rr_frames(
    rr_series_ms: Sequence[int], duration_s: int
) -> tuple[list[tuple[int, ...]], list[float]]:
    """Spread a whole R-R series over 1-second heart-rate frames.

    Every interval goes into the frame in which its beat completed, which is what the
    strap reports; the frames in between stay empty, as they are below about 60 bpm.
    """

    per_frame: list[list[int]] = [[] for _ in range(duration_s)]
    elapsed_ms = 0
    for interval in rr_series_ms:
        if interval <= 0:
            raise ValueError("R-R intervals must be positive")
        elapsed_ms += interval
        frame_index = int(math.ceil(elapsed_ms / 1000.0))
        if frame_index >= duration_s:
            break
        per_frame[frame_index].append(int(interval))
    return [tuple(values) for values in per_frame], [float(second) for second in range(duration_s)]


@dataclass(frozen=True)
class RrNotifications:
    """A synthetic R-R stream laid out as the strap reports it.

    ``intervals_ms[i]`` ends at ``interval_ends_s[i]``. ``times_s`` and ``batches`` are the
    notifications that reached the phone: one per second, carrying the intervals that
    completed since the previous one, with the ones inside a gap lost on the way.
    """

    intervals_ms: np.ndarray
    interval_ends_s: np.ndarray
    kept: np.ndarray
    times_s: np.ndarray
    batches: tuple[tuple[int, ...], ...]
    gaps: tuple[tuple[float, float], ...]


def rr_notifications(
    *,
    duration_s: int,
    mean_rr_ms: float,
    spread: float = 0.03,
    jitter_s: float = 0.2,
    gaps: Sequence[tuple[float, float]] = (),
    catchups: Sequence[tuple[float, float]] = (),
    seed: int = 0,
) -> RrNotifications:
    """A resting night's R-R stream and the notifications the phone received.

    The intervals vary by ``spread`` of their mean. A notification arrives once a second
    and carries the intervals whose beat completed in the second before it, so a slow
    heart rate leaves some notifications without an interval and puts two in others;
    ``times_s`` jitters by ``jitter_s`` around that second. A gap drops the notifications
    in its range, so the beats of those seconds are lost and the next notification keeps
    its own time. A catch-up ``(start_s, span_s)`` empties the notifications of ``span_s``
    seconds and delivers their intervals all at once in the next one, the way a buffered
    backlog arrives; no beat is lost.
    """

    rng = np.random.default_rng(seed)
    intervals: list[int] = []
    ends: list[float] = []
    elapsed_ms = 0.0
    while True:
        interval = int(round(float(mean_rr_ms) * (1.0 + rng.normal(0.0, spread))))
        if interval <= 0:
            continue
        elapsed_ms += interval
        if elapsed_ms > duration_s * 1000:
            break
        intervals.append(interval)
        ends.append(elapsed_ms / 1000.0)

    carried: dict[int, list[int]] = {}
    kept = np.zeros(len(intervals), dtype=bool)
    for position, (interval, end_s) in enumerate(zip(intervals, ends, strict=True)):
        second = int(math.ceil(end_s))
        if second >= duration_s:
            continue
        if any(start <= second < end for start, end in gaps):
            continue
        kept[position] = True
        carried.setdefault(second, []).append(interval)

    for start, span in catchups:
        target = int(start + span)
        if target >= duration_s or any(a <= target < b for a, b in gaps):
            continue
        moved: list[int] = []
        for second in range(int(start), target):
            moved.extend(carried.pop(second, ()))
        carried[target] = moved + carried.get(target, [])

    times: list[float] = []
    batches: list[tuple[int, ...]] = []
    for second in range(duration_s):
        if any(start <= second < end for start, end in gaps):
            continue
        times.append(second + float(rng.uniform(-jitter_s, jitter_s)))
        batches.append(tuple(carried.get(second, ())))
    return RrNotifications(
        intervals_ms=np.asarray(intervals, dtype=np.int64),
        interval_ends_s=np.asarray(ends, dtype=np.float64),
        kept=kept,
        times_s=np.asarray(times, dtype=np.float64),
        batches=tuple(batches),
        gaps=tuple(gaps),
    )


def hr_log(
    *,
    duration_s: int,
    hr_values: Sequence[int] | None = None,
    contact: bool = True,
    rr_ms: Iterable[int] | None = None,
    rr_per_sample: Sequence[Iterable[int]] | None = None,
    rr_series_ms: Sequence[int] | None = None,
    stream_start_s: float = 0.0,
) -> bytes:
    data = bytearray(frame_header(STREAM_HR))
    values = list(hr_values) if hr_values is not None else [60] * duration_s
    if len(values) != duration_s:
        raise ValueError("hr_values must contain one value per second")
    if rr_per_sample is not None and len(rr_per_sample) != duration_s:
        raise ValueError("rr_per_sample must contain one entry per second")
    if rr_ms is not None:
        per_frame: Sequence[Iterable[int]] = [rr_ms] * duration_s
    elif rr_per_sample is not None:
        per_frame = rr_per_sample
    else:
        per_frame, _ = rr_frames(rr_series_ms or default_rr_series(duration_s), duration_s)
    flags = 3 if contact else 1
    for second, value in enumerate(values):
        wall_s = stream_start_s + second
        data += frame(
            hr_payload(
                START_NS + round(wall_s * 1_000_000_000),
                round(wall_s * 1_000_000_000),
                value,
                flags=flags,
                rr_ms=per_frame[second],
            )
        )
    return bytes(data)


# A batch carries at most ten seconds of samples, so a frame's uint16 sample count
# holds for a whole night as well as for a short recording.
BATCH_SECONDS = 10


def _batch_starts(n: int, rate_hz: int) -> list[tuple[int, int]]:
    size = max(1, rate_hz * BATCH_SECONDS)
    return [(first, min(size, n - first)) for first in range(0, n, size)]


def ecg_log(
    *,
    duration_s: int,
    rate_hz: int = 130,
    stream_start_s: float = 0.0,
    values_i16: bool | None = None,
) -> bytes:
    n = max(1, duration_s * rate_hz)
    data = bytearray(frame_header(STREAM_ECG))
    spacing = 1_000_000_000 // rate_hz
    start_ns = POLAR_START_NS + round(stream_start_s * 1_000_000_000)
    for first, count in _batch_starts(n, rate_hz):
        # A small deterministic waveform; unit conversion is what matters here.
        values = [int(1000 * (((first + index) % 20) - 10)) for index in range(count)]
        end_s = stream_start_s + (first + count) / rate_hz
        data += frame(
            ecg_payload(
                START_NS + round(end_s * 1_000_000_000),
                round(end_s * 1_000_000_000),
                start_ns + first * spacing,
                start_ns + (first + count - 1) * spacing,
                values,
                values_i16=values_i16,
            )
        )
    return bytes(data)


def acc_log(
    *,
    duration_s: int,
    rate_hz: int = 50,
    movement_windows: Sequence[tuple[float, float]] = (),
    movement_mg: int = 500,
    offset_mg: int = 0,
    stream_start_s: float = 0.0,
) -> bytes:
    n = max(1, duration_s * rate_hz)
    values: list[int] = []
    for index in range(n):
        second = index / rate_hz
        moving = any(start <= second < end for start, end in movement_windows)
        x = movement_mg if moving else 0
        values.extend([x + offset_mg, offset_mg, 1000 + offset_mg])
    data = bytearray(frame_header(STREAM_ACC))
    spacing = 1_000_000_000 // rate_hz
    start_ns = POLAR_START_NS + round(stream_start_s * 1_000_000_000)
    for first, count in _batch_starts(n, rate_hz):
        end_s = stream_start_s + (first + count) / rate_hz
        data += frame(
            acc_payload(
                START_NS + round(end_s * 1_000_000_000),
                round(end_s * 1_000_000_000),
                start_ns + first * spacing,
                start_ns + (first + count - 1) * spacing,
                values[first * 3 : (first + count) * 3],
            )
        )
    return bytes(data)


def sound_log(
    *,
    duration_s: int,
    codec: str = frames.SOUND_OPUS_CODEC,
    rate_hz: int = 16_000,
    stream_start_s: float = 0.0,
) -> bytes:
    """One second of mono microphone audio per frame, in either sound codec."""

    if codec not in (frames.SOUND_PCM_CODEC, frames.SOUND_OPUS_CODEC):
        raise ValueError(f"unknown sound codec {codec}")
    data = bytearray(frame_header(STREAM_SOUND))
    for second in range(duration_s):
        end_s = stream_start_s + second + 1
        phone_ns = START_NS + round(end_s * 1_000_000_000)
        elapsed_ns = round(end_s * 1_000_000_000)
        if codec == frames.SOUND_PCM_CODEC:
            samples = [(index % 200) - 100 for index in range(rate_hz)]
            payload = frames.pcm_payload(phone_ns, elapsed_ns, rate_hz, samples)
        else:
            # 20 ms packets; their bytes stand in for what the encoder emits.
            packets = [bytes([0xFC, index & 0xFF]) for index in range(50)]
            payload = frames.opus_payload(phone_ns, elapsed_ns, rate_hz, packets, rate_hz)
        data += frame(payload)
    return bytes(data)


def write_recording(
    root: str | Path,
    *,
    session_id: str = "20261023_223000",
    duration_s: int = 120,
    hr_values: Sequence[int] | None = None,
    contact: bool = True,
    include_ecg: bool = True,
    include_acc: bool = True,
    movement_windows: Sequence[tuple[float, float]] = (),
    movement_mg: int = 500,
    acc_offset_mg: int = 0,
    rr_ms: Iterable[int] | None = None,
    rr_per_sample: Sequence[Iterable[int]] | None = None,
    rr_series_ms: Sequence[int] | None = None,
    bad_hr_tail: bytes | None = None,
    stream_start_s: float = 0.0,
    ecg_values_i16: bool | None = None,
) -> Path:
    path = Path(root)
    path.mkdir(parents=True, exist_ok=True)
    (path / "meta.json").write_text(
        json.dumps(
            meta(session_id, duration_s=duration_s, ecg=include_ecg, acc=include_acc), indent=2
        ),
        encoding="utf-8",
    )
    hr = hr_log(
        duration_s=duration_s,
        hr_values=hr_values,
        contact=contact,
        rr_ms=rr_ms,
        rr_per_sample=rr_per_sample,
        rr_series_ms=rr_series_ms,
        stream_start_s=stream_start_s,
    )
    if bad_hr_tail:
        hr += bad_hr_tail
    (path / "hr.log").write_bytes(hr)
    if include_ecg:
        (path / "ecg.log").write_bytes(
            ecg_log(
                duration_s=duration_s,
                stream_start_s=stream_start_s,
                values_i16=ecg_values_i16,
            )
        )
    if include_acc:
        (path / "acc.log").write_bytes(
            acc_log(
                duration_s=duration_s,
                movement_windows=movement_windows,
                movement_mg=movement_mg,
                offset_mg=acc_offset_mg,
                stream_start_s=stream_start_s,
            )
        )
    (path / "events.jsonl").write_bytes(
        event_line(START_NS, 0, "start", "recording started")
        + event_line(
            START_NS + duration_s * 1_000_000_000,
            duration_s * 1_000_000_000,
            "stop",
            "recording stopped",
        )
    )
    return path
