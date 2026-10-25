"""Synthetic recording directories for analysis tests.

Everything is generated at test time. The layout follows
docs/recording-format.md; no health data is committed.
"""

from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path
from typing import Iterable, Sequence

POLAR_EPOCH_OFFSET_NS = 946_684_800_000_000_000
STREAM_HR = 1
STREAM_ECG = 2
STREAM_ACC = 3

START_UTC_MS = 1_792_798_200_000
START_NS = START_UTC_MS * 1_000_000
POLAR_START_NS = START_NS - POLAR_EPOCH_OFFSET_NS


def frame_header(stream: int) -> bytes:
    return b"TSLG" + bytes([1, stream]) + b"\x00\x00"


def frame(payload: bytes, *, bad_crc: bool = False) -> bytes:
    crc = zlib.crc32(payload) & 0xFFFFFFFF
    if bad_crc:
        crc ^= 0xFFFFFFFF
    return struct.pack("<I", len(payload)) + payload + struct.pack("<I", crc)


def hr_payload(
    phone_ns: int,
    elapsed_ns: int,
    hr_bpm: int,
    flags: int = 3,
    rr_ms: Iterable[int] = (1000,),
) -> bytes:
    rr = list(rr_ms)
    return (
        struct.pack("<qqHBB", phone_ns, elapsed_ns, hr_bpm, flags, len(rr))
        + b"".join(struct.pack("<H", value) for value in rr)
    )


def ecg_payload(
    phone_ns: int,
    elapsed_ns: int,
    t_first: int,
    t_last: int,
    voltage_uv: Sequence[int],
) -> bytes:
    values = list(voltage_uv)
    return (
        struct.pack("<qqBHqq", phone_ns, elapsed_ns, 0, len(values), t_first, t_last)
        + b"".join(struct.pack("<i", value) for value in values)
    )


def acc_payload(
    phone_ns: int,
    elapsed_ns: int,
    t_first: int,
    t_last: int,
    xyz_mg: Sequence[int],
) -> bytes:
    values = list(xyz_mg)
    return (
        struct.pack("<qqBHqq", phone_ns, elapsed_ns, 0, len(values) // 3, t_first, t_last)
        + b"".join(struct.pack("<h", value) for value in values)
    )


def event_line(phone_ns: int, elapsed_ns: int, event_type: str, message: str) -> bytes:
    return (
        json.dumps(
            {
                "phone_ns": phone_ns,
                "elapsed_ns": elapsed_ns,
                "type": event_type,
                "message": message,
            },
            separators=(",", ":"),
        ).encode()
        + b"\n"
    )


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


def hr_log(
    *,
    duration_s: int,
    hr_values: Sequence[int] | None = None,
    contact: bool = True,
    rr_ms: Iterable[int] = (1000, 980, 1020),
) -> bytes:
    data = bytearray(frame_header(STREAM_HR))
    values = list(hr_values) if hr_values is not None else [60] * duration_s
    if len(values) != duration_s:
        raise ValueError("hr_values must contain one value per second")
    flags = 3 if contact else 1
    for second, value in enumerate(values):
        data += frame(
            hr_payload(
                START_NS + second * 1_000_000_000,
                second * 1_000_000_000,
                value,
                flags=flags,
                rr_ms=rr_ms,
            )
        )
    return bytes(data)


def ecg_log(*, duration_s: int, rate_hz: int = 130) -> bytes:
    n = max(1, duration_s * rate_hz)
    # A small deterministic waveform; unit conversion is what matters here.
    values = [int(1000 * ((i % 20) - 10)) for i in range(n)]
    t_first = POLAR_START_NS
    t_last = POLAR_START_NS + (n - 1) * (1_000_000_000 // rate_hz)
    return frame_header(STREAM_ECG) + frame(
        ecg_payload(START_NS + duration_s * 1_000_000_000, duration_s * 1_000_000_000, t_first, t_last, values)
    )


def acc_log(
    *,
    duration_s: int,
    rate_hz: int = 50,
    movement_windows: Sequence[tuple[float, float]] = (),
) -> bytes:
    n = max(1, duration_s * rate_hz)
    values: list[int] = []
    for index in range(n):
        second = index / rate_hz
        moving = any(start <= second < end for start, end in movement_windows)
        x = 500 if moving else 0
        values.extend([x, 0, 1000])
    t_first = POLAR_START_NS
    t_last = POLAR_START_NS + (n - 1) * (1_000_000_000 // rate_hz)
    return frame_header(STREAM_ACC) + frame(
        acc_payload(START_NS + duration_s * 1_000_000_000, duration_s * 1_000_000_000, t_first, t_last, values)
    )


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
    bad_hr_tail: bytes | None = None,
) -> Path:
    path = Path(root)
    path.mkdir(parents=True, exist_ok=True)
    (path / "meta.json").write_text(
        json.dumps(meta(session_id, duration_s=duration_s, ecg=include_ecg, acc=include_acc), indent=2),
        encoding="utf-8",
    )
    hr = hr_log(duration_s=duration_s, hr_values=hr_values, contact=contact)
    if bad_hr_tail:
        hr += bad_hr_tail
    (path / "hr.log").write_bytes(hr)
    if include_ecg:
        (path / "ecg.log").write_bytes(ecg_log(duration_s=duration_s))
    if include_acc:
        (path / "acc.log").write_bytes(acc_log(duration_s=duration_s, movement_windows=movement_windows))
    (path / "events.jsonl").write_bytes(
        event_line(START_NS, 0, "start", "recording started")
        + event_line(START_NS + duration_s * 1_000_000_000, duration_s * 1_000_000_000, "stop", "recording stopped")
    )
    return path
