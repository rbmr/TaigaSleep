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
