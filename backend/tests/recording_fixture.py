"""Synthetic recordings for the upload and analysis tests.

These are generated at test time; no health data is committed. The byte layout
follows docs/recording-format.md and comes from the worker's frame module.
"""

from __future__ import annotations

import json
from typing import Iterable

from taiga_worker import frames

POLAR_EPOCH_OFFSET_NS = frames.POLAR_EPOCH_OFFSET_NS
STREAM_HR = frames.STREAM_HR
STREAM_ECG = frames.STREAM_ECG
STREAM_ACC = frames.STREAM_ACC

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
    voltage_uv: Iterable[int],
) -> bytes:
    values = list(voltage_uv)
    return frames.ecg_payload(
        phone_ns,
        elapsed_ns,
        frames.linear_timestamps(t_first, t_last, len(values)),
        values,
    )


def acc_payload(
    phone_ns: int,
    elapsed_ns: int,
    t_first: int,
    t_last: int,
    xyz_mg: Iterable[int],
) -> bytes:
    values = list(xyz_mg)
    return frames.acc_payload(
        phone_ns,
        elapsed_ns,
        frames.linear_timestamps(t_first, t_last, len(values) // 3),
        values,
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
