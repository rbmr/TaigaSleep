"""Synthetic recordings for the upload and analysis tests.

These are generated at test time; no health data is committed. The byte layout
follows docs/recording-format.md exactly.
"""

from __future__ import annotations

import json
import struct
import zlib
from typing import Iterable

POLAR_EPOCH_OFFSET_NS = 946_684_800_000_000_000
STREAM_HR = 1
STREAM_ECG = 2
STREAM_ACC = 3

MAGIC = b"TSLG"
VERSION = 1


def frame_header(stream: int) -> bytes:
    return MAGIC + bytes([VERSION, stream]) + b"\x00\x00"


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
    voltage_uv: Iterable[int],
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
    xyz_mg: Iterable[int],
) -> bytes:
    values = list(xyz_mg)
    if len(values) % 3:
        raise ValueError("xyz_mg must contain whole x, y, z triples")
    return (
        struct.pack("<qqBHqq", phone_ns, elapsed_ns, 0, len(values) // 3, t_first, t_last)
        + b"".join(struct.pack("<h", value) for value in values)
    )


def event_line(
    phone_ns: int,
    elapsed_ns: int,
    event_type: str,
    message: str,
) -> bytes:
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
    session_id: str,
    *,
    start_utc_ms: int = 1_792_798_200_000,
    duration_s: int = 60,
    state: str = "stopped",
    hr: bool = True,
    ecg: bool = True,
    acc: bool = True,
) -> dict:
    end_utc_ms = None if state == "recording" else start_utc_ms + duration_s * 1000
    return {
        "format": "taiga-recording",
        "version": 1,
        "session_id": session_id,
        "state": state,
        "start_utc_ms": start_utc_ms,
        "end_utc_ms": end_utc_ms,
        "timezone": "Europe/Amsterdam",
        "app": {"version": "0.1.0", "version_code": 1},
        "phone": {
            "manufacturer": "Test",
            "model": "Synthetic",
            "android": "16",
            "sdk": 36,
        },
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


def recording(
    session_id: str = "20261023_223000",
    *,
    hr_frames: int = 3,
    include_ecg: bool = True,
    include_acc: bool = True,
    state: str = "stopped",
) -> dict[str, object]:
    """A small but structurally valid recording."""

    start_ms = 1_792_798_200_000
    start_ns = start_ms * 1_000_000
    polar_start_ns = start_ns - POLAR_EPOCH_OFFSET_NS

    hr = bytearray(frame_header(STREAM_HR))
    for i in range(hr_frames):
        hr += frame(
            hr_payload(
                start_ns + i * 1_000_000_000,
                i * 1_000_000_000,
                60 + (i % 3),
                flags=3,
                rr_ms=(1000, 990, 1010),
            )
        )

    files: dict[str, object] = {
        "meta": meta(
            session_id,
            start_utc_ms=start_ms,
            duration_s=hr_frames,
            state=state,
        ),
        "hr.log": bytes(hr),
        "events.jsonl": event_line(start_ns, 0, "start", "recording started")
        + event_line(start_ns + hr_frames * 1_000_000_000, hr_frames * 1_000_000_000, "stop", "recording stopped"),
    }

    if include_ecg:
        samples = 130
        ecg = bytearray(frame_header(STREAM_ECG))
        ecg += frame(
            ecg_payload(
                start_ns,
                0,
                polar_start_ns,
                polar_start_ns + (samples - 1) * (1_000_000_000 // 130),
                [int(1000 * (i % 10 - 5)) for i in range(samples)],
            )
        )
        files["ecg.log"] = bytes(ecg)

    if include_acc:
        samples = 50
        acc = bytearray(frame_header(STREAM_ACC))
        acc += frame(
            acc_payload(
                start_ns,
                0,
                polar_start_ns,
                polar_start_ns + (samples - 1) * (1_000_000_000 // 50),
                [0, 0, 1000] * samples,
            )
        )
        files["acc.log"] = bytes(acc)

    return files


def chunks(data: bytes, size: int) -> list[bytes]:
    return [data[i : i + size] for i in range(0, len(data), size)]
