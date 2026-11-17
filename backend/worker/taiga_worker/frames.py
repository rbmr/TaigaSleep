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

# A batch's ts_mode byte holds the timestamp mode in its low bit and, for ECG, whether the
# sample values are 16-bit integers in the bit above it. See docs/recording-format.md.
TS_LINEAR = 0
TS_EXPLICIT = 1
TS_VALUES_I16 = 1 << 1

I16_MIN = -(1 << 15)
I16_MAX = (1 << 15) - 1

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


@dataclass(frozen=True)
class FrameGap:
    """A byte range a reader stepped over because no frame in it checks out.

    ``start`` is where the damaged bytes begin and ``end`` is where the reader
    read on: the start of the next frame that checks out, or the end of the log
    when the damage is its tail.
    """

    start: int
    end: int
    reason: str

    @property
    def size(self) -> int:
        """How many bytes the reader could not use."""

        return self.end - self.start

    def describe(self) -> str:
        """One line for a warning: why the bytes were skipped and how many."""

        return f"{self.reason} at byte {self.start}; skipped {self.size} bytes to byte {self.end}"


def _damage(data: bytes, stream: int, offset: int) -> str:
    """Why the bytes at ``offset`` are not a frame this log can use."""

    if len(data) - offset < 8:
        return "truncated frame"
    length = struct.unpack_from("<I", data, offset)[0]
    if length > MAX_PAYLOAD:
        return f"frame payload too large ({length})"
    end = offset + 8 + length
    if end > len(data):
        return "truncated frame"
    payload = memoryview(data)[offset + 4 : offset + 4 + length]
    expected_crc = struct.unpack_from("<I", data, offset + 4 + length)[0]
    if expected_crc != zlib.crc32(payload) & 0xFFFFFFFF:
        return "frame CRC mismatch"
    return "payload does not decode"


def _frame_at(data: bytes, stream: int, offset: int) -> Frame | None:
    """The frame at ``offset``, or None when its length, CRC or payload is off.

    The payload has to decode as the stream's own layout, so a length and a CRC
    that happen to match random bytes still do not make a frame.
    """

    if len(data) - offset < 8:
        return None
    length = struct.unpack_from("<I", data, offset)[0]
    end = offset + 8 + length
    if length > MAX_PAYLOAD or end > len(data):
        return None
    payload = memoryview(data)[offset + 4 : offset + 4 + length]
    expected_crc = struct.unpack_from("<I", data, offset + 4 + length)[0]
    if expected_crc != zlib.crc32(payload) & 0xFFFFFFFF:
        return None
    try:
        read_payload(stream, payload)
    except (ValueError, struct.error):
        return None
    return Frame(bytes(payload), offset, end)


def iter_frames(data: bytes, stream: int, gaps: list[FrameGap]) -> Iterator[Frame]:
    """Yield every frame of a log that checks out, over damaged regions if need be.

    A region that does not hold an acceptable frame is skipped to the next offset
    where a whole frame's length, CRC and payload check out, so one damaged frame
    does not hide the frames after it. Each skipped range is appended to ``gaps``
    in order; one that ends at ``len(data)`` is the log's damaged tail, after
    which there is nothing left to read. An invalid log header raises
    :class:`FrameError`, because then no byte of the file can be trusted.
    """

    check_header(data, stream)
    pos = HEADER_SIZE
    while pos < len(data):
        frame = _frame_at(data, stream, pos)
        if frame is not None:
            yield frame
            pos = frame.end
            continue
        reason = _damage(data, stream, pos)
        next_pos = None
        # A frame needs its 8-byte length-and-CRC shell, so the last candidate
        # starts at len(data) - 8.
        for candidate in range(pos + 1, len(data) - 7):
            if _frame_at(data, stream, candidate) is not None:
                next_pos = candidate
                break
        if next_pos is None:
            gaps.append(FrameGap(pos, len(data), reason))
            return
        gaps.append(FrameGap(pos, next_pos, reason))
        pos = next_pos


def encode_timestamps(values: Sequence[int]) -> tuple[int, bytes]:
    """The compact form of a batch's timestamps: mode 0 when linear, else mode 1."""

    count = len(values)
    if count <= 1:
        return TS_LINEAR, struct.pack("<qq", values[0], values[0])
    first, last = values[0], values[-1]
    if all(
        first + trunc_div((last - first) * index, count - 1) == value
        for index, value in enumerate(values)
    ):
        return TS_LINEAR, struct.pack("<qq", first, last)
    return TS_EXPLICIT, struct.pack(f"<{count}q", *values)


def hr_payload(
    phone_ns: int,
    elapsed_ns: int,
    hr_bpm: int,
    flags: int = 0,
    rr_ms: Iterable[int] = (),
) -> bytes:
    """One HR sample; flags bit0 = contact supported, bit1 = contact, bit2 = RR."""

    rr = [int(value) for value in rr_ms]
    return struct.pack("<qqHBB", phone_ns, elapsed_ns, int(hr_bpm), flags, len(rr)) + struct.pack(
        f"<{len(rr)}H", *rr
    )


def ecg_payload(
    phone_ns: int,
    elapsed_ns: int,
    timestamps_ns: Sequence[int],
    voltage_uv: Iterable[int],
    *,
    values_i16: bool | None = None,
) -> bytes:
    """One ECG batch in the polar-ecg-v2 layout; values are microvolts from the sensor.

    ``values_i16`` left at None stores the batch as 16-bit samples when every value fits,
    which is what a recording made now does, and as 32-bit otherwise. True or False forces
    one width, for compatibility tests. Nothing is clamped: a value that does not fit in the
    requested width is an error.
    """

    values = [int(value) for value in voltage_uv]
    mode, timestamps = encode_timestamps(timestamps_ns)
    if values_i16 is None:
        values_i16 = all(I16_MIN <= value <= I16_MAX for value in values)
    if values_i16:
        if any(not I16_MIN <= value <= I16_MAX for value in values):
            raise ValueError("ECG value does not fit in 16 bits")
        mode |= TS_VALUES_I16
        packed = struct.pack(f"<{len(values)}h", *values)
    else:
        packed = struct.pack(f"<{len(values)}i", *values)
    return (
        struct.pack("<qq", phone_ns, elapsed_ns)
        + struct.pack("<BH", mode, len(values))
        + timestamps
        + packed
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
    # True when the payload stored its values as 16-bit integers.
    values_i16: bool = False


def read_hr(payload: bytes) -> HrSample:
    """The fields of one HR payload."""

    phone_ns, elapsed_ns, hr_bpm, flags, count = struct.unpack_from("<qqHBB", payload, 0)
    rr_ms = struct.unpack_from(f"<{count}H", payload, 20)
    if len(payload) != 20 + 2 * count:
        raise ValueError("hr payload length mismatch")
    return HrSample(phone_ns, elapsed_ns, hr_bpm, flags, rr_ms)


def read_ecg(payload: bytes) -> Batch:
    """The timestamps and microvolt values of one ECG payload.

    A batch written now may hold 16-bit values, flagged in its ts_mode byte; a batch without
    the flag holds 32-bit values, the way every recording made before it did.
    """

    return _read_batch(payload, values_per_sample=1, value_format="i", values_i16_allowed=True)


def read_acc(payload: bytes) -> Batch:
    """The timestamps and milli-g values of one ACC payload."""

    return _read_batch(payload, values_per_sample=3, value_format="h", values_i16_allowed=False)


def read_payload(stream: int, payload: bytes) -> HrSample | Batch:
    """Parse one payload as the layout of ``stream``, raising ValueError if it is not."""

    if stream == STREAM_HR:
        return read_hr(payload)
    if stream == STREAM_ECG:
        return read_ecg(payload)
    if stream == STREAM_ACC:
        return read_acc(payload)
    raise ValueError(f"unknown stream id {stream}")


def _read_batch(
    payload: bytes,
    values_per_sample: int,
    value_format: str,
    *,
    values_i16_allowed: bool,
) -> Batch:
    phone_ns, elapsed_ns, stored_mode, count = struct.unpack_from("<qqBH", payload, 0)
    values_i16 = bool(stored_mode & TS_VALUES_I16)
    if values_i16 and not values_i16_allowed:
        raise ValueError("16-bit values in a stream that has none")
    mode = stored_mode & ~TS_VALUES_I16
    offset = 19
    if mode == TS_LINEAR:
        first, last = struct.unpack_from("<qq", payload, offset)
        offset += 16
        timestamps = tuple(linear_timestamps(first, last, count))
    elif mode == TS_EXPLICIT:
        timestamps = struct.unpack_from(f"<{count}q", payload, offset)
        offset += 8 * count
    else:
        raise ValueError("unknown ts_mode %d" % mode)
    value_pack = "h" if values_i16 else value_format
    values = struct.unpack_from(f"<{count * values_per_sample}{value_pack}", payload, offset)
    offset += count * values_per_sample * struct.calcsize(value_pack)
    if offset != len(payload):
        raise ValueError("batch payload length mismatch")
    return Batch(phone_ns, elapsed_ns, mode, timestamps, values, values_i16)


__all__ = [
    "Batch",
    "Frame",
    "FrameError",
    "FrameGap",
    "HEADER_SIZE",
    "HrSample",
    "MAGIC",
    "MAX_PAYLOAD",
    "POLAR_EPOCH_OFFSET_NS",
    "STREAM_ACC",
    "STREAM_ECG",
    "STREAM_HR",
    "STREAM_IDS",
    "TS_EXPLICIT",
    "TS_LINEAR",
    "TS_VALUES_I16",
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
    "read_payload",
    "trunc_div",
]
