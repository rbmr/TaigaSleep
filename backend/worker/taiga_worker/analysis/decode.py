"""Decode the append-only recording format into normalized numpy streams.

The decoder is deliberately shared by every stager. It owns CRC checking, unit
conversion (µV→mV, mG→g), Polar/phone/strap clock handling, contact and
saturation masks, gaps, and stream coverage. Algorithms never parse bytes.

A log is read from a memory map instead of a whole-file read, and a log that was
checked when it was downloaded is read with that check's skipped ranges, so no
frame's CRC is computed twice.

Only the streams that will be used are decoded into arrays. HR and ACC are
always decoded because the shared derivation reads them; a stream that neither
the stager nor the derivation asks for is still checked and reported — frames,
samples, rate, coverage, gaps and saturation — but its samples are not kept.
"""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence

import numpy as np

from ..frames import (
    BLE_HRS_CODEC,
    POLAR_EPOCH_OFFSET_NS,
    STREAM_ACC,
    STREAM_ECG,
    STREAM_HR,
    TS_LINEAR,
    Frame,
    FrameError,
    FrameGap,
    batch_layout,
    iter_frames,
    mapped_log,
    read_ble_hrs,
    read_hr,
    read_ppg,
    read_ppi,
    read_vector,
)
from .contract import (
    AccStream,
    AnalysisError,
    ClockJump,
    ClockSync,
    EcgStream,
    Gap,
    HrStream,
    InputQuality,
    Recording,
    RecordingEvent,
    RecordingMeta,
    StreamReport,
)
from .signals import ecg_saturation

ECG_RAIL_UV = 18_000.0
HR_MIN_BPM = 20.0
HR_MAX_BPM = 240.0
RR_MIN_MS = 300.0
RR_MAX_MS = 2000.0
GAP_FACTOR = 2.5

FLAG_CONTACT_SUPPORTED = 1
FLAG_CONTACT = 2
FLAG_RR_AVAILABLE = 4

#: The sample layout of the two batched streams: samples per timestamp, the payload
#: struct format of a wide batch, and whether the batch may carry 16-bit values.
#: Only an ECG batch can, and its ts_mode byte says so.
_VALUE_LAYOUT = {
    STREAM_ECG: (1, "i", True),
    STREAM_ACC: (3, "h", False),
}
_NARROW = np.dtype("<h")


@dataclass
class FrameLogInfo:
    present: bool
    frames: int
    valid_bytes: int
    truncated_tail: bool
    error: str | None
    gaps: tuple[FrameGap, ...] = ()
    # For every gap, the sample indices just before and just after it, or None
    # when the gap is at the start or the end of the log.
    gap_samples: tuple[tuple[int | None, int | None], ...] = ()


@dataclass
class _Batch:
    """One payload of a batched stream: its anchors and, when wanted, its samples.

    The strap timestamps of a linear (mode 0) batch are two numbers, so a night's
    worth of them costs two numbers per batch instead of one per sample; only a
    non-linear (mode 1) batch carries its own timestamps.
    """

    phone_ns: int
    elapsed_ns: int
    count: int
    first_ns: int
    last_ns: int
    stamps_ns: np.ndarray | None
    # Voltages in mV for ECG, g per axis for ACC, or None when only reported.
    values: np.ndarray | None

    def polar_ns(self) -> np.ndarray:
        """The strap timestamps of this batch's samples."""

        if self.stamps_ns is not None:
            return self.stamps_ns
        return _linear_ns(self.first_ns, self.last_ns, self.count)


@dataclass
class _RawStream:
    """A batched stream before it is put on the recording's time base."""

    batches: list[_Batch]
    info: FrameLogInfo
    last_phone_ns: int
    samples: int
    #: Whether the samples themselves were kept, or only the file was reported.
    kept: bool
    # Samples at or beyond the rail, for the ECG saturation share.
    saturated: int = 0

    def phone_ns(self) -> np.ndarray:
        return np.asarray([batch.phone_ns for batch in self.batches], dtype=np.int64)

    def elapsed_ns(self) -> np.ndarray:
        return np.asarray([batch.elapsed_ns for batch in self.batches], dtype=np.int64)

    def last_stamps_ns(self) -> np.ndarray:
        return np.asarray([batch.last_ns for batch in self.batches], dtype=np.int64)


def _linear_ns(first: int, last: int, count: int) -> np.ndarray:
    """The timestamps of a linear batch, spaced the way the app and Polar store them."""

    if count <= 0:
        return np.empty(0, dtype=np.int64)
    if count == 1:
        return np.full(1, first, dtype=np.int64)
    index = np.arange(count, dtype=np.int64)
    scaled = np.multiply(last - first, index)
    magnitude = np.abs(scaled) // (count - 1)
    return first + np.where(scaled < 0, -magnitude, magnitude)


def _scan(
    path: Path,
    expected_stream: int,
    on_payload,
    checked: Sequence[FrameGap] | None = None,
    codec: str | None = None,
) -> FrameLogInfo:
    """Scan one frame log, keeping every frame that checks out.

    ``on_payload`` parses a payload, hands it to the caller's arrays and returns
    how many samples it held, so a skipped byte range can be tied to the sample
    indices on either side of it. ``checked`` is passed on to
    :func:`taiga_worker.frames.iter_frames`.
    """

    with mapped_log(path) as data:
        if data is None:
            return FrameLogInfo(False, 0, 0, False, None)
        if len(data) == 0:
            return FrameLogInfo(True, 0, 0, False, "empty frame log")

        gaps: list[FrameGap] = []
        gap_samples: list[tuple[int | None, int | None]] = []
        frames = 0
        samples = 0
        valid_bytes = 0
        frame: Frame | None = None
        try:
            for frame in iter_frames(data, expected_stream, gaps, checked=checked, codec=codec):
                while len(gap_samples) < len(gaps):
                    gap_samples.append((samples - 1 if samples else None, samples))
                samples += on_payload(frame.payload)
                frames += 1
                # The report counts up to the payload end, not the CRC that follows it.
                valid_bytes = frame.payload_end
        except FrameError as exc:
            return FrameLogInfo(True, 0, 0, True, f"{exc} at byte {exc.offset}")
        except (ValueError, struct.error) as exc:
            offset = 0 if frame is None else frame.offset
            return FrameLogInfo(True, 0, 0, True, f"invalid frame at byte {offset}: {exc}")
        while len(gap_samples) < len(gaps):
            # A damaged tail: there is no sample after it, the stream ends there.
            gap_samples.append((samples - 1 if samples else None, None))

        truncated_tail = bool(gaps) and gaps[-1].end == len(data)
        error = gaps[-1].describe() if truncated_tail else None
        return FrameLogInfo(
            True,
            frames,
            valid_bytes,
            truncated_tail,
            error,
            tuple(gaps),
            tuple(gap_samples),
        )


def _decode_hr(
    path: Path,
    checked: Sequence[FrameGap] | None = None,
    codec: str | None = None,
) -> tuple[
    np.ndarray,
    np.ndarray,
    np.ndarray,
    np.ndarray,
    list[np.ndarray],
    FrameLogInfo,
]:
    phone: list[int] = []
    elapsed: list[int] = []
    bpm: list[float] = []
    flags: list[int] = []
    rr: list[np.ndarray] = []

    def on_payload(payload: bytes) -> int:
        if codec == BLE_HRS_CODEC:
            sample = read_ble_hrs(payload)
            value = (
                (FLAG_CONTACT_SUPPORTED if sample.contact_supported else 0)
                | (FLAG_CONTACT if sample.contact else 0)
                | (FLAG_RR_AVAILABLE if sample.rr_present else 0)
            )
            phone.append(int(sample.phone_ns))
            elapsed.append(int(sample.elapsed_ns))
            bpm.append(float(sample.hr_bpm))
            flags.append(value)
            rr.append(np.asarray(sample.rr_ms, dtype=np.float32))
            return 1
        sample = read_hr(payload)
        phone.append(int(sample.phone_ns))
        elapsed.append(int(sample.elapsed_ns))
        bpm.append(float(sample.hr_bpm))
        flags.append(int(sample.flags))
        rr.append(np.asarray(sample.rr_ms, dtype=np.float32))
        return 1

    info = _scan(path, STREAM_HR, on_payload, checked, codec)
    return (
        np.asarray(phone, dtype=np.int64),
        np.asarray(elapsed, dtype=np.int64),
        np.asarray(bpm, dtype=np.float32),
        np.asarray(flags, dtype=np.uint8),
        rr,
        info,
    )


def _decode_batches(
    path: Path,
    stream: int,
    *,
    with_values: bool,
    checked: Sequence[FrameGap] | None = None,
) -> _RawStream:
    """Decode one batched stream, keeping its samples only when they are wanted."""

    per_sample, value_format, values_i16_allowed = _VALUE_LAYOUT[stream]
    wide = np.dtype("<" + value_format)
    batches: list[_Batch] = []
    saturated = 0
    samples = 0
    last_phone_ns = 0

    def on_payload(payload: bytes) -> int:
        nonlocal saturated, samples, last_phone_ns
        layout = batch_layout(
            payload,
            per_sample,
            value_format,
            values_i16_allowed=values_i16_allowed,
        )
        if layout.count < 1:
            return 0
        if layout.mode == TS_LINEAR:
            first, last = struct.unpack_from("<qq", payload, layout.timestamps_offset)
            stamps = None
        else:
            stamps = np.frombuffer(
                payload, dtype="<i8", count=layout.count, offset=layout.timestamps_offset
            )
            first, last = int(stamps[0]), int(stamps[-1])
        values = np.frombuffer(
            payload,
            dtype=_NARROW if layout.values_i16 else wide,
            count=layout.count * per_sample,
            offset=layout.values_offset,
        ).astype(np.float32)
        values /= np.float32(1000.0)
        if stream == STREAM_ECG:
            saturated += int(np.count_nonzero(ecg_saturation(values)))
        else:
            values = values.reshape(-1, 3)
        batches.append(
            _Batch(
                phone_ns=layout.phone_ns,
                elapsed_ns=layout.elapsed_ns,
                count=layout.count,
                first_ns=first,
                last_ns=last,
                stamps_ns=stamps,
                values=values if with_values else None,
            )
        )
        samples += layout.count
        last_phone_ns = max(last_phone_ns, layout.phone_ns)
        return layout.count

    info = _scan(path, stream, on_payload, checked)
    return _RawStream(
        batches=batches,
        info=info,
        last_phone_ns=last_phone_ns,
        samples=samples,
        kept=with_values,
        saturated=saturated,
    )


def _decode_extra(
    path: Path,
    stream_id: int,
    codec: str,
    checked: Sequence[FrameGap] | None = None,
) -> tuple[dict, FrameLogInfo]:
    """Decode a stream no stager uses yet: PPG, pulse intervals, gyroscope or magnetometer."""

    batches: dict[str, list] = {
        "phone_ns": [],
        "elapsed_ns": [],
        "t": [],
        "values": [],
        "status": [],
        "flags": [],
        "hr": [],
        "channel_counts": [],
    }

    def on_payload(payload: bytes) -> int:
        if stream_id == 4:
            batch = read_ppg(payload)
            batches["phone_ns"].append(np.asarray([batch.phone_ns], dtype=np.int64))
            batches["elapsed_ns"].append(np.asarray([batch.elapsed_ns], dtype=np.int64))
            batches["t"].append(np.asarray(batch.timestamps_ns, dtype=np.int64))
            batches["values"].append(
                np.asarray([value for sample in batch.channels for value in sample], dtype=np.float32)
            )
            batches["channel_counts"].append(
                np.asarray([len(sample) for sample in batch.channels], dtype=np.int32)
            )
            batches["status"].append(np.asarray(batch.status, dtype=np.int32))
            return len(batch.timestamps_ns)
        if stream_id == 5:
            batch = read_ppi(payload)
            batches["phone_ns"].append(np.asarray([batch.phone_ns], dtype=np.int64))
            batches["elapsed_ns"].append(np.asarray([batch.elapsed_ns], dtype=np.int64))
            batches["t"].append(np.asarray(batch.timestamps_ns, dtype=np.int64))
            batches["values"].append(np.asarray(batch.ppi_ms, dtype=np.float32))
            batches["status"].append(np.asarray(batch.error_ms, dtype=np.int32))
            batches["flags"].append(np.asarray(batch.flags, dtype=np.int32))
            batches["hr"].append(np.asarray(batch.hr_bpm, dtype=np.int32))
            return len(batch.timestamps_ns)
        batch = read_vector(payload)
        batches["phone_ns"].append(np.asarray([batch.phone_ns], dtype=np.int64))
        batches["elapsed_ns"].append(np.asarray([batch.elapsed_ns], dtype=np.int64))
        batches["t"].append(np.asarray(batch.timestamps_ns, dtype=np.int64))
        batches["values"].append(np.asarray(batch.xyz, dtype=np.float32))
        return len(batch.timestamps_ns)

    info = _scan(path, stream_id, on_payload, checked, codec)
    decoded = {
        key: np.concatenate(items).astype(dtype, copy=False) if items else np.empty(0, dtype=dtype)
        for key, items, dtype in (
            ("phone_ns", batches["phone_ns"], np.int64),
            ("elapsed_ns", batches["elapsed_ns"], np.int64),
            ("t", batches["t"], np.int64),
            ("values", batches["values"], np.float32),
            ("status", batches["status"], np.int32),
            ("flags", batches["flags"], np.int32),
            ("hr", batches["hr"], np.int32),
            ("channel_counts", batches["channel_counts"], np.int32),
        )
    }
    return decoded, info


def _anchored_times(
    phone_ns: np.ndarray,
    elapsed_ns: np.ndarray,
    start_unix_ns: int,
    warnings: list[str],
    label: str,
) -> np.ndarray:
    """Put one stream's device clock on the recording's time base.

    The device clock (``elapsed_ns``) gives the spacing between samples. The
    phone wall clock anchors the stream to the recording start through a robust
    offset: the median of phone time minus start minus elapsed time. A backward
    move of the device clock starts a new segment with its own anchor, so the
    stream stays on the phone clock instead of one stream switching bases.
    """

    if elapsed_ns.size and np.any(elapsed_ns > 0):
        elapsed = elapsed_ns.astype(np.int64)
        residual_ns = (phone_ns.astype(np.int64) - np.int64(start_unix_ns) - elapsed).astype(
            np.float64
        )
        backwards = np.diff(elapsed) < 0
        if np.any(backwards):
            warnings.append(f"{label}: device clock moved backwards; using a new anchor")
            segment = np.concatenate(([0], np.cumsum(backwards)))
            offsets = np.empty(elapsed.size, dtype=np.float64)
            for index in np.unique(segment):
                members = segment == index
                offsets[members] = np.median(residual_ns[members])
        else:
            offsets = np.full(elapsed.size, np.median(residual_ns), dtype=np.float64)
        return (elapsed.astype(np.float64) + offsets) / 1e9
    return (phone_ns.astype(np.float64) - float(start_unix_ns)) / 1e9


def _sample_times_from_batches(
    batches: Sequence[_Batch],
    anchor_s: np.ndarray,
    warnings: list[str],
    label: str,
) -> np.ndarray:
    """Anchor each batch on the phone clock and use strap time for spacing."""

    total = sum(batch.count for batch in batches)
    if not batches or total == 0:
        return np.empty(0, dtype=np.float64)
    times = np.empty(total, dtype=np.float64)
    at = 0
    for i, batch in enumerate(batches):
        polar = batch.polar_ns()
        if polar.size > 1 and np.any(np.diff(polar) < 0):
            warnings.append(f"{label}: non-monotonic strap timestamps in a batch")
        end = at + batch.count
        np.subtract(polar, float(polar[-1]), out=times[at:end], dtype=np.float64)
        times[at:end] /= 1e9
        times[at:end] += anchor_s[i]
        at = end
    return times


def _gaps(t_s: np.ndarray, rate_hz: float, stream: str) -> list[Gap]:
    if t_s.size < 2:
        return []
    threshold = max(1.5, GAP_FACTOR / max(rate_hz, 0.1))
    diffs = np.diff(t_s)
    gaps: list[Gap] = []
    for index in np.nonzero(diffs > threshold)[0]:
        gaps.append(Gap(stream, float(t_s[index]), float(t_s[index + 1])))
    return gaps


def _byte_gaps(
    stream: str,
    gap_samples: Sequence[tuple[int | None, int | None]],
    t_s: np.ndarray,
) -> list[Gap]:
    """The time that the samples around every skipped byte range do not cover.

    The samples are those in frame order, before they are sorted by time. A
    range with no sample after it is the log's damaged tail: the stream simply
    ends there, as it did before, so it is not a gap in time.
    """

    gaps: list[Gap] = []
    for before, after in gap_samples:
        if after is None or after >= t_s.size:
            continue
        start = 0.0 if before is None else float(t_s[before])
        end = float(t_s[after])
        if end > start:
            gaps.append(Gap(stream, start, end))
    return gaps


def _merge_gaps(gaps: Sequence[Gap]) -> list[Gap]:
    """One missing stretch, whether it was found by bytes or by timestamps."""

    merged: list[Gap] = []
    for gap in sorted(gaps, key=lambda item: item.start_s):
        if merged and gap.start_s <= merged[-1].end_s:
            last = merged[-1]
            merged[-1] = Gap(last.stream, last.start_s, max(last.end_s, gap.end_s))
        else:
            merged.append(gap)
    return merged


def _median_rate(t_s: np.ndarray, fallback: float) -> float:
    if t_s.size < 2:
        return fallback
    diffs = np.diff(t_s)
    diffs = diffs[(diffs > 0) & (diffs < 1.0)]
    if diffs.size == 0:
        return fallback
    return float(1.0 / np.median(diffs))


def _ordered(t_s: np.ndarray, values: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Put samples in time order, without copying what is in order already."""

    order = _time_order(t_s)
    if order is None:
        return t_s, values
    return t_s[order], values[order]


def _time_order(t_s: np.ndarray) -> np.ndarray | None:
    """The sort a stream needs, or ``None`` when its samples are in time order."""

    if t_s.size > 1 and np.any(np.diff(t_s) < 0):
        return np.argsort(t_s, kind="stable")
    return None


def _availability(samples: int, duration_s: float, rate_hz: float) -> float:
    expected = duration_s * max(rate_hz, 0.0)
    if expected <= 0:
        return 0.0
    return float(min(1.0, max(0.0, samples / expected)))


def _read_events(path: Path, warnings: list[str]) -> tuple[RecordingEvent, ...]:
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ()
    events: list[RecordingEvent] = []
    for number, line in enumerate(text.splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
            events.append(
                RecordingEvent(
                    phone_ns=int(item["phone_ns"]),
                    elapsed_ns=int(item.get("elapsed_ns", 0)),
                    type=str(item["type"]),
                    message=str(item.get("message", "")),
                )
            )
        except (ValueError, TypeError, KeyError):
            warnings.append(f"events.jsonl: ignored invalid line {number}")
            break
    return tuple(events)


def _read_meta(path: Path) -> RecordingMeta:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise AnalysisError("missing_meta", "meta.json is missing") from exc
    except ValueError as exc:
        raise AnalysisError("invalid_meta", f"meta.json is not valid JSON: {exc}") from exc
    return RecordingMeta.from_dict(raw)


def _stream(meta: RecordingMeta, stream_type: str):
    for stream in meta.streams:
        if stream.type == stream_type:
            return stream
    return None


def _stream_name(stream_type: str) -> str | None:
    return {
        "heart_rate": "hr",
        "rr_interval": "hr",
        "ecg": "ecg",
        "accelerometer": "acc",
        "ppg": "ppg",
        "pulse_interval": "ppi",
        "gyroscope": "gyro",
        "magnetometer": "mag",
    }.get(stream_type)


def _rate(meta: RecordingMeta, stream_type: str, fallback: float) -> float:
    stream = _stream(meta, stream_type)
    return stream.rate_hz if stream is not None and stream.rate_hz else fallback


def load_recording(
    directory: str | Path,
    *,
    streams: Sequence[str] | None = None,
    checked: Mapping[str, Sequence[FrameGap]] | None = None,
) -> Recording:
    """Load one recording directory into the normalized :class:`Recording`.

    ``streams`` names the streams to decode into arrays; ``None`` decodes all of
    them. A stream left out is still reported in the input quality, but its
    samples are not kept, so a stager that does not read it costs no memory.

    ``checked`` maps a file name to the byte ranges a download check skipped, as
    :func:`taiga_worker.upload.verify_recording_file` reports them. Those ranges
    are stepped over and no frame's CRC is checked again; without it every log is
    checked here, which is what a directory read outside the worker gets.
    """

    root = Path(directory)
    if not root.is_dir():
        raise AnalysisError("missing_recording", f"recording directory not found: {root}")

    meta = _read_meta(root / "meta.json")
    declared = {_stream_name(stream.type) for stream in meta.streams}
    declared.discard(None)
    wanted = set(streams) if streams is not None else declared
    checked = checked or {}

    warnings: list[str] = []
    events = _read_events(root / "events.jsonl", warnings)

    hr_stream = _stream(meta, "heart_rate") or _stream(meta, "rr_interval")
    ecg_stream = _stream(meta, "ecg")
    acc_stream = _stream(meta, "accelerometer")
    hr_file = hr_stream.file if hr_stream else "hr.log"
    ecg_file = ecg_stream.file if ecg_stream else "ecg.log"
    acc_file = acc_stream.file if acc_stream else "acc.log"
    hr_phone, hr_elapsed, hr_bpm, hr_flags, hr_rr, hr_info = _decode_hr(
        root / hr_file,
        checked.get(hr_file),
        hr_stream.codec if hr_stream else None,
    )
    ecg_raw = _decode_batches(
        root / ecg_file, STREAM_ECG, with_values="ecg" in wanted, checked=checked.get(ecg_file)
    )
    acc_raw = _decode_batches(
        root / acc_file, STREAM_ACC, with_values="acc" in wanted, checked=checked.get(acc_file)
    )
    ecg_info = ecg_raw.info
    acc_info = acc_raw.info

    extra_streams: dict[str, object] = {}
    for stream in meta.streams:
        stream_id = {
            "ppg": 4,
            "pulse_interval": 5,
            "gyroscope": 6,
            "magnetometer": 7,
        }.get(stream.type)
        if stream_id is None:
            continue
        # Pulse intervals become the heart-rate series when there is no HR log;
        # the other extra streams are decoded when the caller asked for them.
        if stream.type != "pulse_interval" and _stream_name(stream.type) not in wanted:
            continue
        decoded, info = _decode_extra(
            root / stream.file, stream_id, stream.codec, checked.get(stream.file)
        )
        extra_streams[stream.type] = {**decoded, "info": info, "codec": stream.codec}

    start_unix_ns = meta.start_utc_ms * 1_000_000
    ecg_elapsed = ecg_raw.elapsed_ns()
    ecg_phone_batches = ecg_raw.phone_ns()
    acc_elapsed = acc_raw.elapsed_ns()
    acc_phone_batches = acc_raw.phone_ns()

    # One offset from the device clock to the phone clock, robust against the
    # jitter of individual callbacks. Event times without a phone timestamp use
    # it too, so every time in a result shares the recording's time base.
    residuals: list[np.ndarray] = []
    for phone, elapsed in (
        (hr_phone, hr_elapsed),
        (ecg_phone_batches, ecg_elapsed),
        (acc_phone_batches, acc_elapsed),
    ):
        if phone.size and elapsed.size and np.any(elapsed > 0):
            residuals.append((phone - np.int64(start_unix_ns) - elapsed).astype(np.float64))
    if not residuals:
        warnings.append("no monotonic elapsed timestamps; using the phone wall clock")
        elapsed_offset_ns = 0.0
    else:
        elapsed_offset_ns = float(np.median(np.concatenate(residuals)))

    hr: HrStream | None = None
    hr_t = np.empty(0, dtype=np.float64)
    hr_byte_gaps: list[Gap] = []
    hr_samples = 0
    if hr_info.present and hr_phone.size:
        hr_t = _anchored_times(hr_phone, hr_elapsed, start_unix_ns, warnings, "hr")
        hr_byte_gaps = _byte_gaps("hr", hr_info.gap_samples, hr_t)
        order = _time_order(hr_t)
        if order is not None:
            hr_t = hr_t[order]
            hr_bpm = hr_bpm[order]
            hr_flags = hr_flags[order]
            hr_phone = hr_phone[order]
            hr_rr = tuple(hr_rr[i] for i in order)
        hr_samples = int(hr_t.size)
        contact_supported = (hr_flags & FLAG_CONTACT_SUPPORTED) != 0
        contact = np.where(contact_supported, (hr_flags & FLAG_CONTACT) != 0, True)
        valid = contact & (hr_bpm >= HR_MIN_BPM) & (hr_bpm <= HR_MAX_BPM)
        hr = HrStream(
            t_s=hr_t,
            hr_bpm=hr_bpm,
            contact_supported=contact_supported,
            contact=contact,
            valid=valid,
            rr_ms=hr_rr,
            phone_ns=hr_phone,
        )

    # A device that reports pulse intervals instead of beat intervals still gives the
    # stagers a heart-rate series: the intervals are the beats and each sample carries
    # its own heart rate.
    ppi = extra_streams.get("pulse_interval")
    if hr is None and isinstance(ppi, dict) and ppi["values"].size:
        intervals = ppi["values"].astype(np.float64)
        beats = np.cumsum(intervals) / 1000.0
        ppi_hr = ppi["hr"].astype(np.float32)
        ppi_flags = ppi["flags"]
        hr_t = beats
        hr_samples = int(beats.size)
        hr = HrStream(
            t_s=hr_t,
            hr_bpm=ppi_hr,
            contact_supported=(ppi_flags & FLAG_CONTACT_SUPPORTED) != 0,
            contact=(ppi_flags & FLAG_CONTACT) != 0,
            valid=(ppi_hr >= HR_MIN_BPM) & (ppi_hr <= HR_MAX_BPM),
            rr_ms=tuple(np.asarray([value], dtype=np.float32) for value in intervals),
            phone_ns=np.full(beats.size, np.int64(start_unix_ns), dtype=np.int64),
        )

    ecg: EcgStream | None = None
    ecg_t = np.empty(0, dtype=np.float64)
    ecg_byte_gaps: list[Gap] = []
    ecg_time_gaps: list[Gap] = []
    ecg_anchor_s = np.empty(0, dtype=np.float64)
    ecg_default = _rate(meta, "ecg", meta.ecg_rate_hz)
    acc_default = _rate(meta, "accelerometer", meta.acc_rate_hz)
    ecg_rate: float | None = ecg_default if ecg_info.present else None
    ecg_samples = 0
    if ecg_info.present and ecg_raw.batches:
        ecg_anchor_s = _anchored_times(
            ecg_phone_batches, ecg_elapsed, start_unix_ns, warnings, "ecg"
        )
        ecg_t = _sample_times_from_batches(ecg_raw.batches, ecg_anchor_s, warnings, "ecg")
        ecg_byte_gaps = _byte_gaps("ecg", ecg_info.gap_samples, ecg_t)
        ecg_samples = int(ecg_t.size)
        if ecg_raw.kept:
            voltage = np.concatenate([batch.values for batch in ecg_raw.batches])
            ecg_t, voltage = _ordered(ecg_t, voltage)
            ecg_rate = _median_rate(ecg_t, ecg_default)
            ecg_time_gaps = _gaps(ecg_t, ecg_rate, "ecg")
            ecg = EcgStream(
                t_s=ecg_t,
                voltage_mv=voltage,
                saturated=ecg_saturation(voltage),
                rate_hz=ecg_rate,
            )
        else:
            # Reported, not kept: only the rate and the gaps are read from it.
            ecg_t.sort()
            ecg_rate = _median_rate(ecg_t, ecg_default)
            ecg_time_gaps = _gaps(ecg_t, ecg_rate, "ecg")
            ecg_t = np.empty(0, dtype=np.float64)

    acc: AccStream | None = None
    acc_t = np.empty(0, dtype=np.float64)
    acc_byte_gaps: list[Gap] = []
    acc_time_gaps: list[Gap] = []
    acc_anchor_s = np.empty(0, dtype=np.float64)
    acc_rate: float | None = acc_default if acc_info.present else None
    acc_samples = 0
    if acc_info.present and acc_raw.batches:
        acc_anchor_s = _anchored_times(
            acc_phone_batches, acc_elapsed, start_unix_ns, warnings, "acc"
        )
        acc_t = _sample_times_from_batches(acc_raw.batches, acc_anchor_s, warnings, "acc")
        acc_byte_gaps = _byte_gaps("acc", acc_info.gap_samples, acc_t)
        acc_samples = int(acc_t.size)
        if acc_raw.kept:
            xyz = np.concatenate([batch.values for batch in acc_raw.batches])
            acc_t, xyz = _ordered(acc_t, xyz)
            acc_rate = _median_rate(acc_t, acc_default)
            acc_time_gaps = _gaps(acc_t, acc_rate, "acc")
            acc = AccStream(t_s=acc_t, xyz_g=xyz, rate_hz=acc_rate)
        else:
            acc_t.sort()
            acc_rate = _median_rate(acc_t, acc_default)
            acc_time_gaps = _gaps(acc_t, acc_rate, "acc")
            acc_t = np.empty(0, dtype=np.float64)

    last_sample = max(
        [start_unix_ns]
        + ([int(hr.phone_ns.max())] if hr is not None and hr.phone_ns.size else [])
        + ([ecg_raw.last_phone_ns] if ecg_raw.batches else [])
        + ([acc_raw.last_phone_ns] if acc_raw.batches else [])
        + ([events[-1].phone_ns] if events else [])
    )
    end_unix_ns = max(start_unix_ns, last_sample)
    if meta.end_utc_ms is not None:
        end_unix_ns = max(end_unix_ns, meta.end_utc_ms * 1_000_000)
    duration_s = max(0.0, (end_unix_ns - start_unix_ns) / 1e9)

    hr_rate = hr.rate_hz if hr is not None else (1.0 if hr_info.present else None)

    hr_report = StreamReport(
        hr_info.present or hr is not None,
        hr_samples,
        hr_rate,
        hr_info.valid_bytes,
        hr_info.frames,
        hr_info.truncated_tail,
        hr_info.error,
        _availability(hr_samples, duration_s, hr_rate or 0.0),
    )
    ecg_report = StreamReport(
        ecg_info.present,
        ecg_samples,
        ecg_rate,
        ecg_info.valid_bytes,
        ecg_info.frames,
        ecg_info.truncated_tail,
        ecg_info.error,
        _availability(ecg_samples, duration_s, ecg_rate or 0.0),
    )
    acc_report = StreamReport(
        acc_info.present,
        acc_samples,
        acc_rate,
        acc_info.valid_bytes,
        acc_info.frames,
        acc_info.truncated_tail,
        acc_info.error,
        _availability(acc_samples, duration_s, acc_rate or 0.0),
    )

    gaps: list[Gap] = []
    gaps.extend(_merge_gaps(_gaps(hr_t, hr_rate or 1.0, "hr") + hr_byte_gaps))
    gaps.extend(_merge_gaps(ecg_time_gaps + ecg_byte_gaps))
    gaps.extend(_merge_gaps(acc_time_gaps + acc_byte_gaps))

    clock_syncs, clock_jumps = _clock_events(
        events,
        start_unix_ns,
        elapsed_offset_ns,
        ecg_raw,
        ecg_anchor_s,
        acc_raw,
        acc_anchor_s,
        warnings,
    )
    for name, info in (
        (hr_file, hr_info),
        (ecg_file, ecg_info),
        (acc_file, acc_info),
    ):
        for gap in info.gaps:
            warnings.append(f"{name}: {gap.describe()}")
    if hr is None and meta.hr_enabled:
        warnings.append("no HR stream")
    if not ecg_report.present and meta.ecg_enabled:
        warnings.append("no ECG stream")
    if not acc_report.present and meta.acc_enabled:
        warnings.append("no ACC stream")

    quality = InputQuality(
        streams={"hr": hr_report, "ecg": ecg_report, "acc": acc_report},
        hr_contact_fraction=None
        if hr is None or hr.contact.size == 0
        else float(np.mean(hr.contact)),
        ecg_saturated_fraction=None if ecg_samples == 0 else ecg_raw.saturated / ecg_samples,
        gaps=tuple(gaps),
        clock_syncs=tuple(clock_syncs),
        clock_jumps=tuple(clock_jumps),
        warnings=tuple(warnings),
    )

    return Recording(
        meta=meta,
        start_unix_ns=start_unix_ns,
        end_unix_ns=end_unix_ns,
        hr=hr,
        ecg=ecg,
        acc=acc,
        events=events,
        quality=quality,
        extra_streams=extra_streams,
    )


def _clock_events(
    events: Sequence[RecordingEvent],
    start_unix_ns: int,
    elapsed_offset_ns: float,
    ecg_raw: _RawStream,
    ecg_anchor_s: np.ndarray,
    acc_raw: _RawStream,
    acc_anchor_s: np.ndarray,
    warnings: list[str],
) -> tuple[list[ClockSync], list[ClockJump]]:
    syncs: list[ClockSync] = []
    for event in events:
        if event.type == "clock_sync":
            if event.phone_ns:
                at_s = (event.phone_ns - start_unix_ns) / 1e9
            else:
                at_s = (event.elapsed_ns + elapsed_offset_ns) / 1e9
            syncs.append(
                ClockSync(
                    stream="strap",
                    at_s=at_s,
                    source="event",
                    message=event.message,
                )
            )

    jumps: list[ClockJump] = []
    for label, raw, anchors in (("ecg", ecg_raw, ecg_anchor_s), ("acc", acc_raw, acc_anchor_s)):
        if not raw.batches:
            continue
        phone = raw.phone_ns()
        elapsed = raw.elapsed_ns()
        if not np.any(elapsed > 0) or anchors.size != elapsed.size:
            continue

        # How far the phone wall clock is from the monotonic clock. A stable
        # recording has a constant offset; an NTP/manual change moves it.
        residual = (phone.astype(np.float64) - start_unix_ns - elapsed.astype(np.float64)) / 1e9
        phone_changes = np.diff(residual)
        device_resets = np.diff(elapsed) < 0

        # How far the strap's last sample is from the phone wall clock. A
        # battery reset or reconnect can move this without moving the phone.
        strap_offsets = (
            raw.last_stamps_ns() - POLAR_EPOCH_OFFSET_NS - phone.astype(np.float64)
        ) / 1e9
        strap_changes = np.diff(strap_offsets)

        for index in range(phone_changes.size):
            at_s = float(anchors[index + 1])
            phone_delta = float(phone_changes[index])
            strap_delta = float(strap_changes[index])
            # A backward move of the device clock is a new segment, not a phone
            # clock change, so it is not reported as one.
            if not device_resets[index] and abs(phone_delta) > 5.0:
                jumps.append(
                    ClockJump(
                        stream=label,
                        at_s=at_s,
                        delta_s=phone_delta,
                        source="phone_wall_clock",
                    )
                )
            elif abs(strap_delta) > 5.0:
                jumps.append(
                    ClockJump(
                        stream=label,
                        at_s=at_s,
                        delta_s=strap_delta,
                        source="strap",
                    )
                )

    phone_count = sum(1 for jump in jumps if jump.source == "phone_wall_clock")
    strap_count = sum(1 for jump in jumps if jump.source == "strap")
    parts: list[str] = []
    if phone_count:
        parts.append(f"{phone_count} phone wall-clock")
    if strap_count:
        parts.append(f"{strap_count} strap")
    if parts:
        warnings.append("detected " + " and ".join(parts) + " clock jump(s)")
    return syncs, jumps


__all__ = ["FrameLogInfo", "load_recording"]
