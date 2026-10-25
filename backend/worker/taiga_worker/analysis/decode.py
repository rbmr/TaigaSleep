"""Decode the append-only recording format into normalized numpy streams.

The decoder is deliberately shared by every stager. It owns CRC checking, unit
conversion (µV→mV, mG→g), Polar/phone/strap clock handling, contact and
saturation masks, gaps, and stream coverage. Algorithms never parse bytes.
"""

from __future__ import annotations

import json
import math
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Sequence

import numpy as np

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

HEADER_SIZE = 8
MAX_PAYLOAD = 1 << 20
POLAR_EPOCH_OFFSET_NS = 946_684_800_000_000_000
ECG_RAIL_UV = 18_000.0
HR_MIN_BPM = 20.0
HR_MAX_BPM = 240.0
RR_MIN_MS = 300.0
RR_MAX_MS = 2000.0
GAP_FACTOR = 2.5

FLAG_CONTACT_SUPPORTED = 1
FLAG_CONTACT = 2
FLAG_RR_AVAILABLE = 4


@dataclass
class FrameLogInfo:
    present: bool
    frames: int
    valid_bytes: int
    truncated_tail: bool
    error: str | None


@dataclass
class _RawEcg:
    phone_ns: list[np.ndarray]
    elapsed_ns: list[np.ndarray]
    t_polar: list[np.ndarray]
    values_uv: list[np.ndarray]


@dataclass
class _RawAcc:
    phone_ns: list[np.ndarray]
    elapsed_ns: list[np.ndarray]
    t_polar: list[np.ndarray]
    values_mg: list[np.ndarray]


def _read_bytes(path: Path) -> bytes | None:
    try:
        return path.read_bytes()
    except FileNotFoundError:
        return None


def _iter_payloads(data: bytes, expected_stream: int) -> Iterator[tuple[bytes, int]]:
    """Yield payloads until the first truncated or bad-CRC frame."""

    if len(data) == 0:
        return
    if len(data) < HEADER_SIZE:
        raise _FrameError(0, "truncated frame-log header")
    if data[:4] != b"TSLG":
        raise _FrameError(0, "invalid frame-log magic")
    if data[4] != 1:
        raise _FrameError(0, f"unsupported frame-log version {data[4]}")
    if data[5] != expected_stream:
        raise _FrameError(0, f"wrong stream id {data[5]} (expected {expected_stream})")
    if data[6] != 0 or data[7] != 0:
        raise _FrameError(0, "invalid frame-log header reserved bytes")

    pos = HEADER_SIZE
    while pos < len(data):
        if len(data) - pos < 8:
            raise _FrameError(pos, "truncated frame")
        length = struct.unpack_from("<I", data, pos)[0]
        if length > MAX_PAYLOAD:
            raise _FrameError(pos, f"frame payload too large ({length})")
        end = pos + 8 + length
        if end > len(data):
            raise _FrameError(pos, "truncated frame")
        payload = data[pos + 4 : pos + 4 + length]
        expected_crc = struct.unpack_from("<I", data, pos + 4 + length)[0]
        actual_crc = zlib.crc32(payload) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise _FrameError(pos, "frame CRC mismatch")
        yield payload, pos + 4 + length
        pos = end


class _FrameError(Exception):
    def __init__(self, offset: int, message: str) -> None:
        super().__init__(message)
        self.offset = offset


def _scan(path: Path, expected_stream: int, on_payload) -> FrameLogInfo:
    data = _read_bytes(path)
    if data is None:
        return FrameLogInfo(False, 0, 0, False, None)
    if len(data) == 0:
        return FrameLogInfo(True, 0, 0, False, "empty frame log")

    frames = 0
    valid_bytes = 0
    try:
        for payload, end in _iter_payloads(data, expected_stream):
            on_payload(payload)
            frames += 1
            valid_bytes = end
    except _FrameError as exc:
        valid_bytes = valid_bytes or 0
        return FrameLogInfo(
            True,
            frames,
            valid_bytes,
            True,
            f"{exc} at byte {exc.offset}",
        )
    return FrameLogInfo(True, frames, valid_bytes, False, None)


def _decode_hr(path: Path) -> tuple[list[np.ndarray], list[np.ndarray], list[np.ndarray], list[np.ndarray], list[np.ndarray], FrameLogInfo]:
    phone: list[np.ndarray] = []
    elapsed: list[np.ndarray] = []
    bpm: list[np.ndarray] = []
    flags: list[np.ndarray] = []
    rr: list[np.ndarray] = []

    def on_payload(payload: bytes) -> None:
        if len(payload) < 20:
            raise _FrameError(0, "HR payload is too short")
        phone_ns, elapsed_ns, hr, flag, n_rr = struct.unpack_from("<qqHBB", payload, 0)
        expected = 20 + 2 * n_rr
        if len(payload) != expected:
            raise _FrameError(0, "HR payload length mismatch")
        rr_values = np.frombuffer(payload, dtype="<u2", count=n_rr, offset=20).astype(
            np.float32, copy=False
        )
        phone.append(np.asarray([phone_ns], dtype=np.int64))
        elapsed.append(np.asarray([elapsed_ns], dtype=np.int64))
        bpm.append(np.asarray([float(hr)], dtype=np.float32))
        flags.append(np.asarray([int(flag)], dtype=np.uint8))
        rr.append(rr_values)

    info = _scan(path, 1, on_payload)

    def cat(items: list[np.ndarray], dtype) -> np.ndarray:
        if not items:
            return np.empty(0, dtype=dtype)
        return np.concatenate(items).astype(dtype, copy=False)

    return (
        cat(phone, np.int64),
        cat(elapsed, np.int64),
        cat(bpm, np.float32),
        cat(flags, np.uint8),
        rr,
        info,
    )


def _decode_ecg(path: Path) -> tuple[_RawEcg, FrameLogInfo]:
    raw = _RawEcg([], [], [], [])

    def on_payload(payload: bytes) -> None:
        if len(payload) < 19:
            raise _FrameError(0, "ECG payload is too short")
        phone_ns, elapsed_ns, mode, n = struct.unpack_from("<qqBH", payload, 0)
        if n == 0:
            return
        if mode == 0:
            if len(payload) < 35:
                raise _FrameError(0, "ECG linear payload is too short")
            first, last = struct.unpack_from("<qq", payload, 19)
            t = _reconstruct_linear(first, last, n)
            values_offset = 35
        elif mode == 1:
            values_offset = 19 + 8 * n
            if len(payload) < values_offset:
                raise _FrameError(0, "ECG explicit payload is too short")
            t = np.frombuffer(payload, dtype="<i8", count=n, offset=19).astype(np.int64, copy=True)
        else:
            raise _FrameError(0, f"unknown ECG timestamp mode {mode}")

        expected = values_offset + 4 * n
        if len(payload) != expected:
            raise _FrameError(0, "ECG payload length mismatch")
        values = np.frombuffer(payload, dtype="<i4", count=n, offset=values_offset).astype(
            np.float32, copy=False
        )
        raw.phone_ns.append(np.asarray([phone_ns], dtype=np.int64))
        raw.elapsed_ns.append(np.asarray([elapsed_ns], dtype=np.int64))
        raw.t_polar.append(t)
        raw.values_uv.append(values)

    info = _scan(path, 2, on_payload)
    return raw, info


def _decode_acc(path: Path) -> tuple[_RawAcc, FrameLogInfo]:
    raw = _RawAcc([], [], [], [])

    def on_payload(payload: bytes) -> None:
        if len(payload) < 19:
            raise _FrameError(0, "ACC payload is too short")
        phone_ns, elapsed_ns, mode, n = struct.unpack_from("<qqBH", payload, 0)
        if n == 0:
            return
        if mode == 0:
            if len(payload) < 35:
                raise _FrameError(0, "ACC linear payload is too short")
            first, last = struct.unpack_from("<qq", payload, 19)
            t = _reconstruct_linear(first, last, n)
            values_offset = 35
        elif mode == 1:
            values_offset = 19 + 8 * n
            if len(payload) < values_offset:
                raise _FrameError(0, "ACC explicit payload is too short")
            t = np.frombuffer(payload, dtype="<i8", count=n, offset=19).astype(np.int64, copy=True)
        else:
            raise _FrameError(0, f"unknown ACC timestamp mode {mode}")

        expected = values_offset + 6 * n
        if len(payload) != expected:
            raise _FrameError(0, "ACC payload length mismatch")
        values = np.frombuffer(payload, dtype="<i2", count=3 * n, offset=values_offset).astype(
            np.float32, copy=False
        )
        raw.phone_ns.append(np.asarray([phone_ns], dtype=np.int64))
        raw.elapsed_ns.append(np.asarray([elapsed_ns], dtype=np.int64))
        raw.t_polar.append(t)
        raw.values_mg.append(values.reshape(n, 3))

    info = _scan(path, 3, on_payload)
    return raw, info


def _reconstruct_linear(first: int, last: int, n: int) -> np.ndarray:
    if n <= 1:
        return np.asarray([first], dtype=np.int64)
    span = last - first
    idx = np.arange(n, dtype=np.int64)
    return first + (span * idx) // (n - 1)


def _cat(items: Sequence[np.ndarray], dtype, shape_tail: tuple[int, ...] = ()) -> np.ndarray:
    if not items:
        return np.empty((0,) + shape_tail, dtype=dtype)
    if shape_tail:
        return np.concatenate(items, axis=0).astype(dtype, copy=False)
    return np.concatenate(items).astype(dtype, copy=False)


def _relative_from_elapsed(
    elapsed_ns: np.ndarray,
    phone_ns: np.ndarray,
    start_unix_ns: int,
    elapsed0: int,
    warnings: list[str],
    label: str,
) -> np.ndarray:
    if elapsed_ns.size and np.any(elapsed_ns > 0):
        t = (elapsed_ns.astype(np.float64) - float(elapsed0)) / 1e9
        if np.any(np.diff(t) < -1e-6):
            warnings.append(f"{label}: elapsed clock moved backwards; using phone clock")
            t = (phone_ns.astype(np.float64) - float(start_unix_ns)) / 1e9
        return t
    return (phone_ns.astype(np.float64) - float(start_unix_ns)) / 1e9


def _sample_times_from_batches(
    elapsed_ns: np.ndarray,
    phone_ns: np.ndarray,
    t_polar: Sequence[np.ndarray],
    start_unix_ns: int,
    elapsed0: int,
    warnings: list[str],
    label: str,
) -> np.ndarray:
    """Anchor each batch on the phone clock and use strap time for spacing."""

    anchor_s = _relative_from_elapsed(
        elapsed_ns, phone_ns, start_unix_ns, elapsed0, warnings, label
    )
    parts: list[np.ndarray] = []
    for i, polar in enumerate(t_polar):
        if polar.size == 0:
            continue
        if polar.size > 1 and np.any(np.diff(polar) < 0):
            warnings.append(f"{label}: non-monotonic strap timestamps in a batch")
        delta_s = (polar.astype(np.float64) - float(polar[-1])) / 1e9
        parts.append(anchor_s[i] + delta_s)
    if not parts:
        return np.empty(0, dtype=np.float64)
    return np.concatenate(parts)


def _gaps(t_s: np.ndarray, rate_hz: float, stream: str) -> list[Gap]:
    if t_s.size < 2:
        return []
    threshold = max(1.5, GAP_FACTOR / max(rate_hz, 0.1))
    diffs = np.diff(t_s)
    gaps: list[Gap] = []
    for index in np.nonzero(diffs > threshold)[0]:
        gaps.append(Gap(stream, float(t_s[index]), float(t_s[index + 1])))
    return gaps


def _median_rate(t_s: np.ndarray, fallback: float) -> float:
    if t_s.size < 2:
        return fallback
    diffs = np.diff(t_s)
    diffs = diffs[(diffs > 0) & (diffs < 1.0)]
    if diffs.size == 0:
        return fallback
    return float(1.0 / np.median(diffs))


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


def load_recording(directory: str | Path) -> Recording:
    """Load one recording directory into the normalized :class:`Recording`."""

    root = Path(directory)
    if not root.is_dir():
        raise AnalysisError("missing_recording", f"recording directory not found: {root}")

    meta = _read_meta(root / "meta.json")
    warnings: list[str] = []
    events = _read_events(root / "events.jsonl", warnings)

    hr_phone, hr_elapsed, hr_bpm, hr_flags, hr_rr, hr_info = _decode_hr(root / "hr.log")
    ecg_raw, ecg_info = _decode_ecg(root / "ecg.log")
    acc_raw, acc_info = _decode_acc(root / "acc.log")

    elapsed_candidates = [
        array
        for array in (hr_elapsed, np.concatenate(ecg_raw.elapsed_ns) if ecg_raw.elapsed_ns else np.empty(0, dtype=np.int64), np.concatenate(acc_raw.elapsed_ns) if acc_raw.elapsed_ns else np.empty(0, dtype=np.int64))
        if array.size and np.any(array > 0)
    ]
    elapsed0 = int(min(int(array.min()) for array in elapsed_candidates)) if elapsed_candidates else 0
    start_unix_ns = meta.start_utc_ms * 1_000_000
    if not elapsed_candidates:
        warnings.append("no monotonic elapsed timestamps; using the phone wall clock")

    hr: HrStream | None = None
    hr_t = np.empty(0, dtype=np.float64)
    if hr_info.present and hr_phone.size:
        hr_t = _relative_from_elapsed(
            hr_elapsed, hr_phone, start_unix_ns, elapsed0, warnings, "hr"
        )
        order = np.argsort(hr_t, kind="stable")
        hr_t = hr_t[order]
        hr_bpm = hr_bpm[order]
        hr_flags = hr_flags[order]
        hr_phone = hr_phone[order]
        hr_rr = tuple(hr_rr[i] for i in order)
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

    ecg: EcgStream | None = None
    ecg_t = np.empty(0, dtype=np.float64)
    if ecg_info.present and ecg_raw.values_uv:
        ecg_elapsed = np.concatenate(ecg_raw.elapsed_ns)
        ecg_phone = np.concatenate(ecg_raw.phone_ns)
        ecg_t = _sample_times_from_batches(
            ecg_elapsed,
            ecg_phone,
            ecg_raw.t_polar,
            start_unix_ns,
            elapsed0,
            warnings,
            "ecg",
        )
        ecg_uv = np.concatenate(ecg_raw.values_uv)
        ecg_mv = (ecg_uv / 1000.0).astype(np.float32, copy=False)
        order = np.argsort(ecg_t, kind="stable")
        ecg_t = ecg_t[order]
        ecg_mv = ecg_mv[order]
        ecg_phone = np.repeat(ecg_phone, [len(x) for x in ecg_raw.values_uv])[order]
        saturated = np.abs(ecg_mv) >= (ECG_RAIL_UV / 1000.0)
        ecg = EcgStream(
            t_s=ecg_t,
            voltage_mv=ecg_mv,
            saturated=saturated,
            rate_hz=_median_rate(ecg_t, meta.ecg_rate_hz),
            phone_ns=ecg_phone,
        )

    acc: AccStream | None = None
    acc_t = np.empty(0, dtype=np.float64)
    if acc_info.present and acc_raw.values_mg:
        acc_elapsed = np.concatenate(acc_raw.elapsed_ns)
        acc_phone = np.concatenate(acc_raw.phone_ns)
        acc_t = _sample_times_from_batches(
            acc_elapsed,
            acc_phone,
            acc_raw.t_polar,
            start_unix_ns,
            elapsed0,
            warnings,
            "acc",
        )
        acc_mg = np.concatenate(acc_raw.values_mg)
        acc_g = (acc_mg / 1000.0).astype(np.float32, copy=False)
        order = np.argsort(acc_t, kind="stable")
        acc_t = acc_t[order]
        acc_g = acc_g[order]
        acc_phone = np.repeat(acc_phone, [len(x) for x in acc_raw.values_mg])[order]
        acc = AccStream(
            t_s=acc_t,
            xyz_g=acc_g,
            rate_hz=_median_rate(acc_t, meta.acc_rate_hz),
            phone_ns=acc_phone,
        )

    last_sample = max(
        [start_unix_ns]
        + ([int(hr.phone_ns.max())] if hr is not None and hr.phone_ns.size else [])
        + ([int(ecg.phone_ns.max())] if ecg is not None and ecg.phone_ns.size else [])
        + ([int(acc.phone_ns.max())] if acc is not None and acc.phone_ns.size else [])
        + ([events[-1].phone_ns] if events else [])
    )
    end_unix_ns = max(start_unix_ns, last_sample)
    if meta.end_utc_ms is not None:
        end_unix_ns = max(end_unix_ns, meta.end_utc_ms * 1_000_000)
    duration_s = max(0.0, (end_unix_ns - start_unix_ns) / 1e9)

    hr_rate = hr.rate_hz if hr is not None else (1.0 if hr_info.present else None)
    ecg_rate = ecg.rate_hz if ecg is not None else (meta.ecg_rate_hz if ecg_info.present else None)
    acc_rate = acc.rate_hz if acc is not None else (meta.acc_rate_hz if acc_info.present else None)
    hr_samples = int(hr.t_s.size) if hr is not None else 0
    ecg_samples = int(ecg.t_s.size) if ecg is not None else 0
    acc_samples = int(acc.t_s.size) if acc is not None else 0

    hr_report = StreamReport(
        hr_info.present,
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
    gaps.extend(_gaps(hr_t, hr_rate or 1.0, "hr"))
    gaps.extend(_gaps(ecg_t, ecg_rate or meta.ecg_rate_hz, "ecg"))
    gaps.extend(_gaps(acc_t, acc_rate or meta.acc_rate_hz, "acc"))

    clock_syncs, clock_jumps = _clock_events(
        events, start_unix_ns, elapsed0, ecg_raw, acc_raw, warnings
    )
    for report in (hr_report, ecg_report, acc_report):
        if report.truncated_tail and report.error:
            warnings.append(report.error)
    if not hr_report.present and meta.hr_enabled:
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
        ecg_saturated_fraction=None
        if ecg is None or ecg.saturated.size == 0
        else float(np.mean(ecg.saturated)),
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
    )


def _clock_events(
    events: Sequence[RecordingEvent],
    start_unix_ns: int,
    elapsed0: int,
    ecg_raw: _RawEcg,
    acc_raw: _RawAcc,
    warnings: list[str],
) -> tuple[list[ClockSync], list[ClockJump]]:
    syncs: list[ClockSync] = []
    for event in events:
        if event.type == "clock_sync":
            if event.phone_ns:
                at_s = (event.phone_ns - start_unix_ns) / 1e9
            else:
                at_s = (event.elapsed_ns - elapsed0) / 1e9
            syncs.append(
                ClockSync(
                    stream="strap",
                    at_s=at_s,
                    source="event",
                    message=event.message,
                )
            )

    jumps: list[ClockJump] = []
    for label, phone_batches, elapsed_batches, polar_batches in (
        ("ecg", ecg_raw.phone_ns, ecg_raw.elapsed_ns, ecg_raw.t_polar),
        ("acc", acc_raw.phone_ns, acc_raw.elapsed_ns, acc_raw.t_polar),
    ):
        if not phone_batches or not elapsed_batches or not polar_batches:
            continue
        phone = np.concatenate(phone_batches).astype(np.int64)
        elapsed = np.concatenate(elapsed_batches).astype(np.int64)
        if not np.any(elapsed > 0) or phone.size != len(polar_batches):
            continue
        if any(polar.size == 0 for polar in polar_batches):
            continue

        # How far the phone wall clock is from the monotonic clock. A stable
        # recording has a constant offset; an NTP/manual change moves it.
        residual = (phone.astype(np.float64) - start_unix_ns) / 1e9 - (
            elapsed.astype(np.float64) - elapsed0
        ) / 1e9
        phone_changes = np.diff(residual)

        # How far the strap's last sample is from the phone wall clock. A
        # battery reset or reconnect can move this without moving the phone.
        strap_offsets = np.asarray(
            [
                (float(polar[-1]) - POLAR_EPOCH_OFFSET_NS - float(phone[index])) / 1e9
                for index, polar in enumerate(polar_batches)
            ],
            dtype=np.float64,
        )
        strap_changes = np.diff(strap_offsets)

        for index in range(phone_changes.size):
            at_s = float((elapsed[index + 1] - elapsed0) / 1e9)
            phone_delta = float(phone_changes[index])
            strap_delta = float(strap_changes[index])
            if abs(phone_delta) > 5.0:
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
