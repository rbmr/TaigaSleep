from __future__ import annotations

import random
import zlib

import pytest
from taiga_worker import frames

from tests import analysis_fixture as fx

STREAMS = [frames.STREAM_HR, frames.STREAM_ECG, frames.STREAM_ACC]
STREAM_IDS = {frames.STREAM_HR: "hr", frames.STREAM_ECG: "ecg", frames.STREAM_ACC: "acc"}


def _encoded(stream: int, count: int) -> list[bytes]:
    return [fx.frame(fx.stream_payload(stream, index)) for index in range(count)]


def _corrupt(encoded_frame: bytes, at: int = 6) -> bytes:
    """Flip a byte inside the payload, so the length stays valid and the CRC catches it."""

    return encoded_frame[:at] + bytes([encoded_frame[at] ^ 0xFF]) + encoded_frame[at + 1 :]


@pytest.mark.parametrize("stream", STREAMS, ids=STREAM_IDS.get)
def test_a_damaged_frame_in_the_middle_is_skipped(stream: int) -> None:
    encoded = _encoded(stream, 8)
    damaged = _corrupt(encoded[3])
    data = fx.frame_header(stream) + b"".join(encoded[:3] + [damaged] + encoded[4:])
    start = frames.HEADER_SIZE + sum(len(item) for item in encoded[:3])

    gaps: list[frames.FrameGap] = []
    payloads = [frame.payload for frame in frames.iter_frames(data, stream, gaps)]

    assert payloads == [fx.stream_payload(stream, index) for index in (0, 1, 2, 4, 5, 6, 7)]
    assert len(gaps) == 1
    assert (gaps[0].start, gaps[0].end) == (start, start + len(damaged))
    assert gaps[0].size == len(damaged)
    assert gaps[0].reason == "frame CRC mismatch"
    assert gaps[0].describe() == (
        f"frame CRC mismatch at byte {start}; "
        f"skipped {len(damaged)} bytes to byte {start + len(damaged)}"
    )


@pytest.mark.parametrize("stream", STREAMS, ids=STREAM_IDS.get)
def test_a_damaged_tail_still_ends_the_log(stream: int) -> None:
    encoded = _encoded(stream, 5)
    tail = b"\x10\x00\x00\x00broken"
    data = fx.frame_header(stream) + b"".join(encoded) + tail

    gaps: list[frames.FrameGap] = []
    payloads = [frame.payload for frame in frames.iter_frames(data, stream, gaps)]

    assert payloads == [fx.stream_payload(stream, index) for index in range(5)]
    assert len(gaps) == 1
    assert gaps[0].start == len(data) - len(tail)
    assert gaps[0].end == len(data)
    assert "truncated" in gaps[0].reason


@pytest.mark.parametrize("stream", STREAMS, ids=STREAM_IDS.get)
def test_a_length_that_does_not_decode_is_not_a_frame(stream: int) -> None:
    """Random bytes whose length and CRC happen to fit still need a readable payload."""

    raw = b"\x14\x00\x00\x00" + b"\xff" * 20  # a 20-byte payload that is no HR, ECG or ACC sample
    data = fx.frame_header(stream) + raw + zlib.crc32(raw[4:]).to_bytes(4, "little")
    gaps: list[frames.FrameGap] = []
    payloads = [frame.payload for frame in frames.iter_frames(data, stream, gaps)]

    assert payloads == []
    assert len(gaps) == 1
    assert gaps[0].reason == "payload does not decode"
    assert gaps[0].end == len(data)


def test_fixed_seed_garbage_between_frames_produces_no_spurious_frame() -> None:
    rng = random.Random(0x240)
    for stream in STREAMS:
        expected: list[bytes] = []
        parts = [fx.frame_header(stream)]
        regions = 0
        for index in range(40):
            if index:
                parts.append(bytes(rng.randrange(256) for _ in range(rng.randrange(1, 200))))
                regions += 1
            payload = fx.stream_payload(stream, index)
            expected.append(payload)
            parts.append(fx.frame(payload))
        data = b"".join(parts)

        gaps: list[frames.FrameGap] = []
        decoded = [frame.payload for frame in frames.iter_frames(data, stream, gaps)]

        assert decoded == expected
        assert len(gaps) == regions
        assert all(gap.size > 0 for gap in gaps)
        assert all(gap.end < len(data) for gap in gaps)


def test_an_invalid_header_is_never_stepped_over() -> None:
    data = frames.frame_header(frames.STREAM_ECG) + fx.frame(
        fx.stream_payload(frames.STREAM_ECG, 0)
    )

    with pytest.raises(frames.FrameError):
        list(frames.iter_frames(data, frames.STREAM_HR, []))


def _linear_timestamps(count: int) -> list[int]:
    return [fx.POLAR_START_NS + index * (1_000_000_000 // 130) for index in range(count)]


def test_ecg_random_batches_round_trip_as_16_bit() -> None:
    rng = random.Random(251)
    for _ in range(200):
        count = rng.randint(1, 300)
        timestamps = _linear_timestamps(count)
        values = [rng.randint(-32768, 32767) for _ in range(count)]
        payload = frames.ecg_payload(fx.START_NS, 0, timestamps, values)

        assert payload[16] & frames.TS_VALUES_I16
        batch = frames.read_ecg(payload)
        assert batch.values == tuple(values)
        assert batch.timestamps_ns == tuple(timestamps)
        assert batch.values_i16
        # A linear grid and two bytes per sample: 19-byte header, 16 timestamp bytes.
        assert len(payload) == 19 + 16 + 2 * count


@pytest.mark.parametrize("value", [-32768, 32767], ids=["min", "max"])
def test_ecg_values_at_the_16_bit_limits_stay_exact(value: int) -> None:
    values = [0, value, 0]
    payload = frames.ecg_payload(fx.START_NS, 0, _linear_timestamps(len(values)), values)

    assert payload[16] & frames.TS_VALUES_I16
    assert frames.read_ecg(payload).values == tuple(values)


@pytest.mark.parametrize("value", [-32769, 32768], ids=["below", "above"])
def test_ecg_values_beyond_the_16_bit_limits_fall_back_to_32_bit(value: int) -> None:
    values = [0, value, 1]
    payload = frames.ecg_payload(fx.START_NS, 0, _linear_timestamps(len(values)), values)

    assert not payload[16] & frames.TS_VALUES_I16
    batch = frames.read_ecg(payload)
    assert not batch.values_i16
    assert batch.values == tuple(values)
    assert len(payload) == 19 + 16 + 4 * len(values)


def test_a_saturated_ecg_batch_is_stored_as_16_bit_and_stays_exact() -> None:
    values = [18_000, -18_000] * 65
    payload = frames.ecg_payload(fx.START_NS, 0, _linear_timestamps(len(values)), values)

    batch = frames.read_ecg(payload)
    assert batch.values_i16
    assert batch.values == tuple(values)


def test_an_ecg_payload_in_the_old_codec_decodes_to_the_same_samples() -> None:
    values = [1234, -5678, 32767, -32768, 0]
    timestamps = _linear_timestamps(len(values))
    old = frames.ecg_payload(fx.START_NS, 0, timestamps, values, values_i16=False)
    new = frames.ecg_payload(fx.START_NS, 0, timestamps, values)

    assert not old[16] & frames.TS_VALUES_I16
    old_batch = frames.read_ecg(old)
    new_batch = frames.read_ecg(new)
    assert not old_batch.values_i16
    assert old_batch.values == new_batch.values == tuple(values)
    assert old_batch.timestamps_ns == new_batch.timestamps_ns


def test_forcing_16_bit_for_a_value_that_does_not_fit_is_an_error() -> None:
    with pytest.raises(ValueError):
        frames.ecg_payload(fx.START_NS, 0, _linear_timestamps(1), [32768], values_i16=True)


def test_an_acc_payload_cannot_carry_the_16_bit_flag() -> None:
    payload = bytearray(frames.acc_payload(fx.START_NS, 0, _linear_timestamps(1), [1, 2, 3]))
    payload[16] |= frames.TS_VALUES_I16

    with pytest.raises(ValueError):
        frames.read_acc(bytes(payload))


@pytest.mark.parametrize("stream", STREAMS, ids=STREAM_IDS.get)
def test_a_checked_log_is_read_with_the_ranges_a_check_found(stream: int) -> None:
    encoded = _encoded(stream, 8)
    damaged = _corrupt(encoded[3])
    data = fx.frame_header(stream) + b"".join(encoded[:3] + [damaged] + encoded[4:])

    found: list[frames.FrameGap] = []
    expected = [frame.payload for frame in frames.iter_frames(data, stream, found)]

    gaps: list[frames.FrameGap] = []
    checked = [frame.payload for frame in frames.iter_frames(data, stream, gaps, checked=found)]

    assert checked == expected
    assert gaps == found


def test_a_checked_log_whose_bytes_do_not_fit_is_an_error() -> None:
    data = (
        fx.frame_header(frames.STREAM_HR)
        + b"\x00\x10\x00\x00"
        + fx.stream_payload(frames.STREAM_HR, 0)
    )

    with pytest.raises(frames.FrameError):
        list(frames.iter_frames(data, frames.STREAM_HR, [], checked=()))


@pytest.mark.parametrize("stream", STREAMS, ids=STREAM_IDS.get)
def test_the_batch_layout_points_at_the_fields_a_reader_needs(stream: int) -> None:
    payload = fx.stream_payload(stream, 0)
    if stream == frames.STREAM_HR:
        sample = frames.read_hr(payload)
        assert sample.hr_bpm == 60
        return
    per_sample, value_format, allowed = {
        frames.STREAM_ECG: (1, "i", True),
        frames.STREAM_ACC: (3, "h", False),
    }[stream]
    layout = frames.batch_layout(payload, per_sample, value_format, values_i16_allowed=allowed)
    batch = frames.read_ecg(payload) if stream == frames.STREAM_ECG else frames.read_acc(payload)

    assert layout.phone_ns == batch.phone_ns
    assert layout.elapsed_ns == batch.elapsed_ns
    assert layout.mode == batch.mode
    assert layout.count == len(batch.timestamps_ns)
    assert layout.values_i16 == batch.values_i16
    assert layout.values_bytes == len(payload) - layout.values_offset
    assert layout.values_offset + layout.values_bytes == len(payload)


def test_the_batch_layout_reads_a_32_bit_ecg_batch() -> None:
    values = [0, 40_000, -40_000]  # beyond 16 bits, so the batch keeps 32-bit values
    payload = fx.ecg_payload(
        fx.START_NS,
        0,
        fx.POLAR_START_NS,
        fx.POLAR_START_NS + 2 * (1_000_000_000 // 130),
        values,
    )
    layout = frames.batch_layout(payload, 1, "i", values_i16_allowed=True)
    batch = frames.read_ecg(payload)

    assert not layout.values_i16
    assert not batch.values_i16
    assert layout.values_bytes == 4 * len(values)
    assert batch.values == tuple(values)
