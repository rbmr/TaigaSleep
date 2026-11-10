"""The Taiga recording frame format, in one implementation.

``docs/recording-format.md`` is the specification: the 8-byte log header, the
``length | payload | crc32`` frames, the payload layout of each stream and the
timestamps of a batch. Everything that reads or writes a recording goes through
this module: the worker's decoder and upload check, the tools in ``tools/`` and
the test fixtures. Tools run it from the worker's environment, see
``tools/README.md``.
"""

from __future__ import annotations

import json
import struct
import zlib
from dataclasses import dataclass
from typing import Iterable, Iterator, Sequence

MAGIC = b"TSLG"
VERSION = 1
HEADER_SIZE = 8
MAX_PAYLOAD = 1 << 20

STREAM_HR = 1
STREAM_ECG = 2
STREAM_ACC = 3
STREAM_IDS = {"hr.log": STREAM_HR, "ecg.log": STREAM_ECG, "acc.log": STREAM_ACC}

# Polar sample timestamps count nanoseconds from 2000-01-01 00:00:00 UTC.
POLAR_EPOCH_OFFSET_NS = 946_684_800_000_000_000


class FrameError(Exception):
    """A log is unreadable from ``offset`` on: its header, a frame or its payload."""

    def __init__(self, offset: int, message: str) -> None:
        super().__init__(message)
        self.offset = offset


def trunc_div(a: int, b: int) -> int:
    """Integer division truncating toward zero, the way the app and Polar divide."""

    quotient = abs(a) // abs(b)
    return quotient if (a >= 0) == (b >= 0) else -quotient


def linear_timestamps(first: int, last: int, count: int) -> list[int]:
    """The timestamps of a linear (mode 0) batch of ``count`` samples."""

    if count <= 1:
        return [first]
    span = last - first
    return [first + trunc_div(span * index, count - 1) for index in range(count)]


def frame_header(stream: int) -> bytes:
    """The 8-byte header that opens a stream's log."""

    return MAGIC + bytes([VERSION, stream]) + b"\x00\x00"


def encode_frame(payload: bytes) -> bytes:
    """One frame: its length, the payload and the CRC of the payload."""

    if len(payload) > MAX_PAYLOAD:
        raise ValueError(f"frame payload is {len(payload)} bytes (max {MAX_PAYLOAD})")
    return (
        struct.pack("<I", len(payload))
        + payload
        + struct.pack("<I", zlib.crc32(payload) & 0xFFFFFFFF)
    )


def check_header(data: bytes, stream: int) -> None:
    """Refuse a log whose header is not the one this stream must have."""

    if len(data) < HEADER_SIZE:
        raise FrameError(0, "truncated frame-log header")
    if data[:4] != MAGIC:
        raise FrameError(0, "invalid frame-log magic")
    if data[4] != VERSION:
        raise FrameError(0, f"unsupported frame-log version {data[4]}")
    if data[5] != stream:
        raise FrameError(0, f"wrong stream id {data[5]} (expected {stream})")
    if data[6] != 0 or data[7] != 0:
        raise FrameError(0, "invalid frame-log header reserved bytes")


@dataclass(frozen=True)
class Frame:
    payload: bytes
    # offset is where the frame starts, end where the next frame starts.
    offset: int
    end: int

    @property
    def payload_end(self) -> int:
        """The byte just after the payload, where its CRC begins."""

        return self.offset + 4 + len(self.payload)


def iter_frames(data: bytes, stream: int) -> Iterator[Frame]:
    """Yield the frames of a log up to the first truncated or bad-CRC one.

    A damaged frame ends the iteration with a :class:`FrameError` at its offset,
    so a caller keeps everything before it (the recovery rule of the format).
    """

    check_header(data, stream)
    pos = HEADER_SIZE
    while pos < len(data):
        if len(data) - pos < 8:
            raise FrameError(pos, "truncated frame")
        length = struct.unpack_from("<I", data, pos)[0]
        if length > MAX_PAYLOAD:
            raise FrameError(pos, f"frame payload too large ({length})")
        end = pos + 8 + length
        if end > len(data):
            raise FrameError(pos, "truncated frame")
        payload = data[pos + 4 : pos + 4 + length]
        expected_crc = struct.unpack_from("<I", data, pos + 4 + length)[0]
        if expected_crc != zlib.crc32(payload) & 0xFFFFFFFF:
            raise FrameError(pos, "frame CRC mismatch")
        yield Frame(payload, pos, end)
        pos = end


def encode_timestamps(values: Sequence[int]) -> tuple[int, bytes]:
    """The compact form of a batch's timestamps: mode 0 when linear, else mode 1."""

    count = len(values)
    if count <= 1:
        return 0, struct.pack("<qq", values[0], values[0])
    first, last = values[0], values[-1]
    if all(
        first + trunc_div((last - first) * index, count - 1) == value
        for index, value in enumerate(values)
    ):
        return 0, struct.pack("<qq", first, last)
    return 1, struct.pack(f"<{count}q", *values)


def hr_payload(
    phone_ns: int,
    elapsed_ns: int,
    hr_bpm: int,
    flags: int = 0,
    rr_ms: Iterable[int] = (),
) -> bytes:
    """One HR sample; flags bit0 = contact supported, bit1 = contact, bit2 = RR."""

    rr = [int(value) for value in rr_ms]
    return (
        struct.pack("<qqHBB", phone_ns, elapsed_ns, int(hr_bpm), flags, len(rr))
        + struct.pack(f"<{len(rr)}H", *rr)
    )


def ecg_payload(
    phone_ns: int,
    elapsed_ns: int,
    timestamps_ns: Sequence[int],
    voltage_uv: Iterable[int],
) -> bytes:
    """One ECG batch; the values are microvolts, verbatim from the sensor."""

    values = [int(value) for value in voltage_uv]
    mode, timestamps = encode_timestamps(timestamps_ns)
    return (
        struct.pack("<qq", phone_ns, elapsed_ns)
        + struct.pack("<BH", mode, len(values))
        + timestamps
        + struct.pack(f"<{len(values)}i", *values)
    )


def acc_payload(
    phone_ns: int,
    elapsed_ns: int,
    timestamps_ns: Sequence[int],
    xyz_mg: Iterable[int],
) -> bytes:
    """One ACC batch; the values are milli-g, interleaved x, y, z."""

    values = [int(value) for value in xyz_mg]
    if len(values) % 3:
        raise ValueError("xyz_mg must contain whole x, y, z triples")
    mode, timestamps = encode_timestamps(timestamps_ns)
    return (
        struct.pack("<qq", phone_ns, elapsed_ns)
        + struct.pack("<BH", mode, len(values) // 3)
        + timestamps
        + struct.pack(f"<{len(values)}h", *values)
    )


def event_line(phone_ns: int, elapsed_ns: int, event_type: str, message: str) -> bytes:
    """One events.jsonl entry, including its newline."""

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


@dataclass(frozen=True)
class HrSample:
    phone_ns: int
    elapsed_ns: int
    hr_bpm: int
    flags: int
    rr_ms: tuple[int, ...]


@dataclass(frozen=True)
class Batch:
    phone_ns: int
    elapsed_ns: int
    # mode 0 stores the first and last timestamp, mode 1 every timestamp.
    mode: int
    timestamps_ns: tuple[int, ...]
    # ECG voltages in microvolts, or ACC milli-g interleaved x, y, z.
    values: tuple[int, ...]


def read_hr(payload: bytes) -> HrSample:
    """The fields of one HR payload."""

    phone_ns, elapsed_ns, hr_bpm, flags, count = struct.unpack_from("<qqHBB", payload, 0)
    rr_ms = struct.unpack_from(f"<{count}H", payload, 20)
    if len(payload) != 20 + 2 * count:
        raise ValueError("hr payload length mismatch")
    return HrSample(phone_ns, elapsed_ns, hr_bpm, flags, rr_ms)


def read_ecg(payload: bytes) -> Batch:
    """The timestamps and microvolt values of one ECG payload."""

    return _read_batch(payload, values_per_sample=1, value_format="i")


def read_acc(payload: bytes) -> Batch:
    """The timestamps and milli-g values of one ACC payload."""

    return _read_batch(payload, values_per_sample=3, value_format="h")


def _read_batch(payload: bytes, values_per_sample: int, value_format: str) -> Batch:
    phone_ns, elapsed_ns, mode, count = struct.unpack_from("<qqBH", payload, 0)
    offset = 19
    if mode == 0:
        first, last = struct.unpack_from("<qq", payload, offset)
        offset += 16
        timestamps = tuple(linear_timestamps(first, last, count))
    elif mode == 1:
        timestamps = struct.unpack_from(f"<{count}q", payload, offset)
        offset += 8 * count
    else:
        raise ValueError("unknown ts_mode %d" % mode)
    values = struct.unpack_from(
        f"<{count * values_per_sample}{value_format}", payload, offset
    )
    offset += count * values_per_sample * struct.calcsize(value_format)
    if offset != len(payload):
        raise ValueError("batch payload length mismatch")
    return Batch(phone_ns, elapsed_ns, mode, timestamps, values)


__all__ = [
    "Batch",
    "Frame",
    "FrameError",
    "HEADER_SIZE",
    "HrSample",
    "MAGIC",
    "MAX_PAYLOAD",
    "POLAR_EPOCH_OFFSET_NS",
    "STREAM_ACC",
    "STREAM_ECG",
    "STREAM_HR",
    "STREAM_IDS",
    "VERSION",
    "acc_payload",
    "check_header",
    "ecg_payload",
    "encode_frame",
    "encode_timestamps",
    "event_line",
    "frame_header",
    "hr_payload",
    "iter_frames",
    "linear_timestamps",
    "read_acc",
    "read_ecg",
    "read_hr",
    "trunc_div",
]
