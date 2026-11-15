"""Decode the append-only recording format into normalized numpy streams.

The decoder is deliberately shared by every stager. It owns CRC checking, unit
conversion (µV→mV, mG→g), Polar/phone/strap clock handling, contact and
saturation masks, gaps, and stream coverage. Algorithms never parse bytes.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import numpy as np

from ..frames import (
    POLAR_EPOCH_OFFSET_NS,
    FrameError,
    FrameGap,
    iter_frames,
    read_acc,
    read_ecg,
    read_hr,
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
    gaps: tuple[FrameGap, ...] = ()
    # For every gap, the sample indices just before and just after it, or None
    # when the gap is at the start or the end of the log.
    gap_samples: tuple[tuple[int | None, int | None], ...] = ()


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


def _scan(path: Path, expected_stream: int, on_payload) -> FrameLogInfo:
    """Scan one frame log, keeping every frame that checks out.

    ``on_payload`` parses a payload, hands it to the caller's arrays and returns
    how many samples it held, so a skipped byte range can be tied to the sample
    indices on either side of it.
    """

    data = _read_bytes(path)
    if data is None:
        return FrameLogInfo(False, 0, 0, False, None)
    if len(data) == 0:
        return FrameLogInfo(True, 0, 0, False, "empty frame log")

    gaps: list[FrameGap] = []
    gap_samples: list[tuple[int | None, int | None]] = []
    frames = 0
    samples = 0
    valid_bytes = 0
    try:
        for frame in iter_frames(data, expected_stream, gaps):
            while len(gap_samples) < len(gaps):
                gap_samples.append((samples - 1 if samples else None, samples))
            samples += on_payload(frame.payload)
            frames += 1
            # The report counts up to the payload end, not the CRC that follows it.
            valid_bytes = frame.payload_end
    except FrameError as exc:
        return FrameLogInfo(True, 0, 0, True, f"{exc} at byte {exc.offset}")
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
) -> tuple[
    list[np.ndarray],
    list[np.ndarray],
    list[np.ndarray],
    list[np.ndarray],
    list[np.ndarray],
    FrameLogInfo,
]:
    phone: list[np.ndarray] = []
    elapsed: list[np.ndarray] = []
    bpm: list[np.ndarray] = []
    flags: list[np.ndarray] = []
    rr: list[np.ndarray] = []

    def on_payload(payload: bytes) -> int:
        sample = read_hr(payload)
        phone.append(np.asarray([sample.phone_ns], dtype=np.int64))
        elapsed.append(np.asarray([sample.elapsed_ns], dtype=np.int64))
        bpm.append(np.asarray([float(sample.hr_bpm)], dtype=np.float32))
        flags.append(np.asarray([int(sample.flags)], dtype=np.uint8))
        rr.append(np.asarray(sample.rr_ms, dtype=np.float32))
        return 1

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

    def on_payload(payload: bytes) -> int:
        batch = read_ecg(payload)
        if not batch.timestamps_ns:
            return 0
        raw.phone_ns.append(np.asarray([batch.phone_ns], dtype=np.int64))
        raw.elapsed_ns.append(np.asarray([batch.elapsed_ns], dtype=np.int64))
        raw.t_polar.append(np.asarray(batch.timestamps_ns, dtype=np.int64))
        raw.values_uv.append(np.asarray(batch.values, dtype=np.float32))
        return len(batch.timestamps_ns)

    info = _scan(path, 2, on_payload)
    return raw, info


def _decode_acc(path: Path) -> tuple[_RawAcc, FrameLogInfo]:
    raw = _RawAcc([], [], [], [])

    def on_payload(payload: bytes) -> int:
        batch = read_acc(payload)
        if not batch.timestamps_ns:
            return 0
        raw.phone_ns.append(np.asarray([batch.phone_ns], dtype=np.int64))
        raw.elapsed_ns.append(np.asarray([batch.elapsed_ns], dtype=np.int64))
        raw.t_polar.append(np.asarray(batch.timestamps_ns, dtype=np.int64))
        raw.values_mg.append(np.asarray(batch.values, dtype=np.float32).reshape(-1, 3))
        return len(batch.timestamps_ns)

    info = _scan(path, 3, on_payload)
    return raw, info


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
    t_polar: Sequence[np.ndarray],
    anchor_s: np.ndarray,
    warnings: list[str],
    label: str,
) -> np.ndarray:
    """Anchor each batch on the phone clock and use strap time for spacing."""

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

    start_unix_ns = meta.start_utc_ms * 1_000_000
    ecg_elapsed = (
        np.concatenate(ecg_raw.elapsed_ns) if ecg_raw.elapsed_ns else np.empty(0, dtype=np.int64)
    )
    ecg_phone_batches = (
        np.concatenate(ecg_raw.phone_ns) if ecg_raw.phone_ns else np.empty(0, dtype=np.int64)
    )
    acc_elapsed = (
        np.concatenate(acc_raw.elapsed_ns) if acc_raw.elapsed_ns else np.empty(0, dtype=np.int64)
    )
    acc_phone_batches = (
        np.concatenate(acc_raw.phone_ns) if acc_raw.phone_ns else np.empty(0, dtype=np.int64)
    )

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
    if hr_info.present and hr_phone.size:
        hr_t = _anchored_times(hr_phone, hr_elapsed, start_unix_ns, warnings, "hr")
        hr_byte_gaps = _byte_gaps("hr", hr_info.gap_samples, hr_t)
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
    ecg_byte_gaps: list[Gap] = []
    ecg_anchor_s = np.empty(0, dtype=np.float64)
    if ecg_info.present and ecg_raw.values_uv:
        ecg_phone = ecg_phone_batches
        ecg_anchor_s = _anchored_times(ecg_phone, ecg_elapsed, start_unix_ns, warnings, "ecg")
        ecg_t = _sample_times_from_batches(
            ecg_raw.t_polar,
            ecg_anchor_s,
            warnings,
            "ecg",
        )
        ecg_byte_gaps = _byte_gaps("ecg", ecg_info.gap_samples, ecg_t)
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
    acc_byte_gaps: list[Gap] = []
    acc_anchor_s = np.empty(0, dtype=np.float64)
    if acc_info.present and acc_raw.values_mg:
        acc_phone = acc_phone_batches
        acc_anchor_s = _anchored_times(acc_phone, acc_elapsed, start_unix_ns, warnings, "acc")
        acc_t = _sample_times_from_batches(
            acc_raw.t_polar,
            acc_anchor_s,
            warnings,
            "acc",
        )
        acc_byte_gaps = _byte_gaps("acc", acc_info.gap_samples, acc_t)
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
    gaps.extend(_merge_gaps(_gaps(hr_t, hr_rate or 1.0, "hr") + hr_byte_gaps))
    gaps.extend(_merge_gaps(_gaps(ecg_t, ecg_rate or meta.ecg_rate_hz, "ecg") + ecg_byte_gaps))
    gaps.extend(_merge_gaps(_gaps(acc_t, acc_rate or meta.acc_rate_hz, "acc") + acc_byte_gaps))

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
        ("hr.log", hr_info),
        ("ecg.log", ecg_info),
        ("acc.log", acc_info),
    ):
        for gap in info.gaps:
            warnings.append(f"{name}: {gap.describe()}")
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
    elapsed_offset_ns: float,
    ecg_raw: _RawEcg,
    ecg_anchor_s: np.ndarray,
    acc_raw: _RawAcc,
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
    for label, phone_batches, elapsed_batches, polar_batches, anchors in (
        ("ecg", ecg_raw.phone_ns, ecg_raw.elapsed_ns, ecg_raw.t_polar, ecg_anchor_s),
        ("acc", acc_raw.phone_ns, acc_raw.elapsed_ns, acc_raw.t_polar, acc_anchor_s),
    ):
        if not phone_batches or not elapsed_batches or not polar_batches:
            continue
        phone = np.concatenate(phone_batches).astype(np.int64)
        elapsed = np.concatenate(elapsed_batches).astype(np.int64)
        if not np.any(elapsed > 0) or phone.size != len(polar_batches):
            continue
        if anchors.size != elapsed.size:
            continue
        if any(polar.size == 0 for polar in polar_batches):
            continue

        # How far the phone wall clock is from the monotonic clock. A stable
        # recording has a constant offset; an NTP/manual change moves it.
        residual = (phone.astype(np.float64) - start_unix_ns - elapsed.astype(np.float64)) / 1e9
        phone_changes = np.diff(residual)
        device_resets = np.diff(elapsed) < 0

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
