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
import mmap
import os
import struct
import zlib
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence

POLAR_HR_CODEC = "polar-hr-v1"
POLAR_ECG_CODEC = "polar-ecg-v1"
POLAR_ECG_V2_CODEC = "polar-ecg-v2"
POLAR_ACC_CODEC = "polar-acc-v1"
POLAR_PPG_CODEC = "polar-ppg-v1"
POLAR_PPI_CODEC = "polar-ppi-v1"
POLAR_GYRO_CODEC = "polar-gyro-v1"
POLAR_MAG_CODEC = "polar-mag-v1"
BLE_HRS_CODEC = "ble-hrs-v1"

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
STREAM_PPG = 4
STREAM_PPI = 5
STREAM_GYRO = 6
STREAM_MAG = 7
STREAM_IDS = {"hr.log": STREAM_HR, "ecg.log": STREAM_ECG, "acc.log": STREAM_ACC}
STREAM_NAMES = {
    STREAM_HR: "hr",
    STREAM_ECG: "ecg",
    STREAM_ACC: "acc",
    STREAM_PPG: "ppg",
    STREAM_PPI: "ppi",
    STREAM_GYRO: "gyro",
    STREAM_MAG: "mag",
}

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


@contextmanager
def mapped_log(path: str | Path) -> Iterator[bytes | mmap.mmap | None]:
    """A frame log's bytes without reading the file whole, or ``None`` if it is missing.

    The mapping is read-only and lives as long as the ``with`` block, so a reader
    walks the frames of a whole night without holding the file in memory. The file
    cannot be changed while the block runs.
    """

    try:
        handle = open(path, "rb")
    except FileNotFoundError:
        yield None
        return
    with handle:
        if os.fstat(handle.fileno()).st_size == 0:
            yield b""
            return
        with mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) as data:
            yield data


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


def _frame_at(
    data: bytes,
    stream: int,
    offset: int,
    *,
    checked: bool = False,
    codec: str | None = None,
) -> Frame | None:
    """The frame at ``offset``, or None when its length, CRC or payload is off.

    The payload has to decode as the stream's own layout, so a length and a CRC
    that happen to match random bytes still do not make a frame. ``checked`` says
    the log was verified already: the frame's length is still bounds-checked, but
    its CRC and payload are taken as they are.
    """

    if len(data) - offset < 8:
        return None
    length = struct.unpack_from("<I", data, offset)[0]
    end = offset + 8 + length
    if length > MAX_PAYLOAD or end > len(data):
        return None
    payload = data[offset + 4 : offset + 4 + length]
    if checked:
        return Frame(payload, offset, end)
    expected_crc = struct.unpack_from("<I", data, offset + 4 + length)[0]
    if expected_crc != zlib.crc32(payload) & 0xFFFFFFFF:
        return None
    try:
        read_payload(stream, payload, codec)
    except (ValueError, struct.error):
        return None
    return Frame(payload, offset, end)


def iter_frames(
    data: bytes,
    stream: int,
    gaps: list[FrameGap],
    *,
    checked: Sequence[FrameGap] | None = None,
    codec: str | None = None,
) -> Iterator[Frame]:
    """Yield every frame of a log that checks out, over damaged regions if need be.

    A region that does not hold an acceptable frame is skipped to the next offset
    where a whole frame's length, CRC and payload check out, so one damaged frame
    does not hide the frames after it. Each skipped range is appended to ``gaps``
    in order; one that ends at ``len(data)`` is the log's damaged tail, after
    which there is nothing left to read. An invalid log header raises
    :class:`FrameError`, because then no byte of the file can be trusted.

    ``checked`` names the ranges a previous check already skipped, as
    :func:`taiga_worker.upload.verify_recording_file` reports them. Giving it,
    even as an empty sequence, says the log was verified already: the ranges are
    stepped over as before and no frame's CRC is computed a second time. The same
    ``data`` must then be the file that was checked.
    """

    check_header(data, stream)
    verified = checked is not None
    skipped = {gap.start: gap for gap in checked or ()}
    pos = HEADER_SIZE
    while pos < len(data):
        if pos in skipped:
            gap = skipped[pos]
            gaps.append(gap)
            pos = gap.end
            continue
        frame = _frame_at(data, stream, pos, checked=verified, codec=codec)
        if frame is not None:
            yield frame
            pos = frame.end
            continue
        if verified:
            # The file was checked and its damaged ranges are known, so bytes here
            # are neither a frame nor a range that was reported. Trusting them is
            # not an option and resynchronising would read what was never checked.
            raise FrameError(pos, "frame does not fit the checked log")
        reason = _damage(data, stream, pos)
        next_pos = None
        # A frame needs its 8-byte length-and-CRC shell, so the last candidate
        # starts at len(data) - 8.
        for candidate in range(pos + 1, len(data) - 7):
            if _frame_at(data, stream, candidate, codec) is not None:
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


@dataclass(frozen=True)
class BatchLayout:
    """Where a batch payload's fields are, without unpacking its samples.

    The timestamps of a linear (mode 0) batch are two values and its samples are a
    plain array, so a reader that needs the samples without materializing them as
    Python objects reads them from these offsets. ``values_i16`` says the samples
    are stored as 16-bit integers, which only an ECG payload may be.
    """

    phone_ns: int
    elapsed_ns: int
    mode: int
    count: int
    timestamps_offset: int
    values_offset: int
    values_bytes: int
    values_i16: bool


def batch_layout(
    payload: bytes,
    values_per_sample: int,
    value_format: str,
    *,
    values_i16_allowed: bool,
) -> BatchLayout:
    """The header and offsets of one ECG or ACC payload, checking its length."""

    phone_ns, elapsed_ns, stored_mode, count = struct.unpack_from("<qqBH", payload, 0)
    values_i16 = bool(stored_mode & TS_VALUES_I16)
    if values_i16 and not values_i16_allowed:
        raise ValueError("16-bit values in a stream that has none")
    mode = stored_mode & ~TS_VALUES_I16
    offset = 19
    if mode == TS_LINEAR:
        offset += 16
    elif mode == TS_EXPLICIT:
        offset += 8 * count
    else:
        raise ValueError("unknown ts_mode %d" % mode)
    value_pack = "h" if values_i16 else value_format
    values_bytes = count * values_per_sample * struct.calcsize(value_pack)
    if offset + values_bytes != len(payload):
        raise ValueError("batch payload length mismatch")
    return BatchLayout(
        phone_ns,
        elapsed_ns,
        mode,
        count,
        19,
        offset,
        values_bytes,
        values_i16,
    )


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


@dataclass(frozen=True)
class BleHrsSample:
    """One standard Heart Rate Measurement notification, kept as received."""

    phone_ns: int
    elapsed_ns: int
    value: bytes
    hr_bpm: int
    contact_supported: bool
    contact: bool
    rr_ms: tuple[int, ...]
    energy_expended: int | None
    rr_present: bool


def read_ble_hrs(payload: bytes) -> BleHrsSample:
    """The stored bytes of one 0x2A37 notification and their decoded fields."""

    phone_ns, elapsed_ns, length = struct.unpack_from("<qqH", payload, 0)
    value = memoryview(payload)[18 : 18 + length]
    if len(value) != length or len(payload) != 18 + length:
        raise ValueError("ble heart rate payload length mismatch")
    decoded = decode_heart_rate_measurement(bytes(value))
    return BleHrsSample(
        phone_ns,
        elapsed_ns,
        bytes(value),
        decoded["hr_bpm"],
        decoded["contact_supported"],
        decoded["contact"],
        tuple(decoded["rr_ms"]),
        decoded["energy_expended"],
        decoded["rr_present"],
    )


def decode_heart_rate_measurement(value: bytes) -> dict:
    """The fields of the 0x2A37 characteristic, for every flag combination."""

    if not value:
        raise ValueError("empty heart rate measurement")
    flags = value[0]
    offset = 1
    wide = flags & 0x01
    if wide:
        if offset + 2 > len(value):
            raise ValueError("truncated 16-bit heart rate")
        hr_bpm = struct.unpack_from("<H", value, offset)[0]
        offset += 2
    else:
        if offset + 1 > len(value):
            raise ValueError("truncated 8-bit heart rate")
        hr_bpm = value[offset]
        offset += 1
    energy = None
    if flags & 0x08:
        if offset + 2 > len(value):
            raise ValueError("truncated energy expended")
        energy = struct.unpack_from("<H", value, offset)[0]
        offset += 2
    rr: list[int] = []
    rr_present = bool(flags & 0x10)
    if rr_present:
        count = (len(value) - offset) // 2
        if offset + count * 2 != len(value):
            raise ValueError("odd RR interval length")
        for index in range(count):
            raw = struct.unpack_from("<H", value, offset + index * 2)[0]
            # Units of 1/1024 s, rounded to the nearest millisecond like the app.
            rr.append((raw * 1000 + 512) // 1024)
    elif offset != len(value):
        raise ValueError("unexpected bytes after the heart rate")
    return {
        "hr_bpm": hr_bpm,
        "contact_supported": bool(flags & 0x04),
        "contact": bool(flags & 0x02),
        "rr_present": rr_present,
        "rr_ms": rr,
        "energy_expended": energy,
    }


@dataclass(frozen=True)
class PpgBatch:
    phone_ns: int
    elapsed_ns: int
    timestamps_ns: tuple[int, ...]
    channel: tuple[int, ...]
    status: tuple[int, ...]


def read_ppg(payload: bytes) -> PpgBatch:
    phone_ns, elapsed_ns, count = struct.unpack_from("<qqH", payload, 0)
    if len(payload) != 18 + count * 14:
        raise ValueError("ppg payload length mismatch")
    timestamps = []
    channel = []
    status = []
    for index in range(count):
        t, value, bits = struct.unpack_from("<qih", payload, 18 + index * 14)
        timestamps.append(t)
        channel.append(value)
        status.append(bits)
    return PpgBatch(phone_ns, elapsed_ns, tuple(timestamps), tuple(channel), tuple(status))


@dataclass(frozen=True)
class PpiBatch:
    phone_ns: int
    elapsed_ns: int
    timestamps_ns: tuple[int, ...]
    ppi_ms: tuple[int, ...]
    error_ms: tuple[int, ...]
    hr_bpm: tuple[int, ...]
    flags: tuple[int, ...]


def read_ppi(payload: bytes) -> PpiBatch:
    phone_ns, elapsed_ns, count = struct.unpack_from("<qqH", payload, 0)
    if len(payload) != 18 + count * 14:
        raise ValueError("ppi payload length mismatch")
    timestamps, ppi, error, hr, flags = [], [], [], [], []
    for index in range(count):
        t, interval, estimate, beats, bits = struct.unpack_from("<qHHBB", payload, 18 + index * 14)
        timestamps.append(t)
        ppi.append(interval)
        error.append(estimate)
        hr.append(beats)
        flags.append(bits)
    return PpiBatch(phone_ns, elapsed_ns, tuple(timestamps), tuple(ppi), tuple(error), tuple(hr), tuple(flags))


@dataclass(frozen=True)
class VectorBatch:
    phone_ns: int
    elapsed_ns: int
    timestamps_ns: tuple[int, ...]
    xyz: tuple[float, ...]


def read_vector(payload: bytes) -> VectorBatch:
    phone_ns, elapsed_ns, count = struct.unpack_from("<qqH", payload, 0)
    if len(payload) != 18 + count * 20:
        raise ValueError("vector payload length mismatch")
    timestamps = []
    xyz = []
    for index in range(count):
        t, x, y, z = struct.unpack_from("<qfff", payload, 18 + index * 20)
        timestamps.append(t)
        xyz.extend((x, y, z))
    return VectorBatch(phone_ns, elapsed_ns, tuple(timestamps), tuple(xyz))


def stream_id_of(data: bytes) -> int | None:
    """The stream id in a log's header, or None when it has no valid header."""

    if len(data) < HEADER_SIZE or data[:4] != MAGIC or data[4] != VERSION:
        return None
    return data[5]


def read_payload(stream: int, payload: bytes, codec: str | None = None):
    """Parse one payload as the layout of ``stream``, raising ValueError if it is not.

    ``codec`` names the payload layout when a stream can carry more than one, such
    as a standard heart-rate measurement next to the Polar one.
    """

    if stream == STREAM_HR:
        if codec == BLE_HRS_CODEC:
            return read_ble_hrs(payload)
        if codec == POLAR_HR_CODEC:
            return read_hr(payload)
        try:
            return read_hr(payload)
        except (ValueError, struct.error):
            return read_ble_hrs(payload)
    if stream == STREAM_ECG:
        return read_ecg(payload)
    if stream == STREAM_ACC:
        return read_acc(payload)
    if stream == STREAM_PPG:
        return read_ppg(payload)
    if stream == STREAM_PPI:
        return read_ppi(payload)
    if stream in (STREAM_GYRO, STREAM_MAG):
        return read_vector(payload)
    raise ValueError(f"unknown stream id {stream}")


def _read_batch(
    payload: bytes,
    values_per_sample: int,
    value_format: str,
    *,
    values_i16_allowed: bool,
) -> Batch:
    layout = batch_layout(
        payload,
        values_per_sample,
        value_format,
        values_i16_allowed=values_i16_allowed,
    )
    if layout.mode == TS_LINEAR:
        first, last = struct.unpack_from("<qq", payload, layout.timestamps_offset)
        timestamps = tuple(linear_timestamps(first, last, layout.count))
    else:
        timestamps = struct.unpack_from(f"<{layout.count}q", payload, layout.timestamps_offset)
    value_pack = "h" if layout.values_i16 else value_format
    values = struct.unpack_from(
        f"<{layout.count * values_per_sample}{value_pack}", payload, layout.values_offset
    )
    return Batch(
        layout.phone_ns,
        layout.elapsed_ns,
        layout.mode,
        timestamps,
        values,
        layout.values_i16,
    )


__all__ = [
    "BLE_HRS_CODEC",
    "Batch",
    "BatchLayout",
    "BleHrsSample",
    "Frame",
    "FrameError",
    "FrameGap",
    "HEADER_SIZE",
    "HrSample",
    "MAGIC",
    "MAX_PAYLOAD",
    "POLAR_ACC_CODEC",
    "POLAR_ECG_CODEC",
    "POLAR_ECG_V2_CODEC",
    "POLAR_EPOCH_OFFSET_NS",
    "POLAR_GYRO_CODEC",
    "POLAR_HR_CODEC",
    "POLAR_MAG_CODEC",
    "POLAR_PPG_CODEC",
    "POLAR_PPI_CODEC",
    "PpgBatch",
    "PpiBatch",
    "STREAM_ACC",
    "STREAM_ECG",
    "STREAM_GYRO",
    "STREAM_HR",
    "STREAM_IDS",
    "STREAM_MAG",
    "STREAM_NAMES",
    "STREAM_PPG",
    "STREAM_PPI",
    "TS_EXPLICIT",
    "TS_LINEAR",
    "TS_VALUES_I16",
    "VERSION",
    "acc_payload",
    "batch_layout",
    "check_header",
    "ecg_payload",
    "encode_frame",
    "encode_timestamps",
    "event_line",
    "frame_header",
    "hr_payload",
    "iter_frames",
    "linear_timestamps",
    "mapped_log",
    "read_acc",
    "read_ble_hrs",
    "read_ecg",
    "read_hr",
    "read_ppg",
    "read_ppi",
    "read_vector",
    "decode_heart_rate_measurement",
    "stream_id_of",
    "read_payload",
    "trunc_div",
    "VectorBatch",
]
