"""The sound stream: both payload codecs, and a recording that carries one next to HR."""

from __future__ import annotations

import json
import struct
from pathlib import Path

import pytest
from taiga_worker import frames
from taiga_worker.analysis.decode import load_recording
from taiga_worker.upload import verify_recording_file

from tests import analysis_fixture as fx

SAMPLE_RATE_HZ = 16_000
CODECS = (frames.SOUND_PCM_CODEC, frames.SOUND_OPUS_CODEC)

# Pinned payloads: phone_ns 1, elapsed_ns 0, 16 kHz, so the byte layout is the contract.
FRAMES = {
    frames.SOUND_PCM_CODEC: bytes.fromhex(
        "01000000000000000000000000000000803e0000040000000000e80318fcff7f"
    ),
    frames.SOUND_OPUS_CODEC: bytes.fromhex(
        "01000000000000000000000000000000803e000080020000020003000200fc0102fc03"
    ),
}
READERS = {
    frames.SOUND_PCM_CODEC: frames.read_pcm,
    frames.SOUND_OPUS_CODEC: frames.read_opus,
}
# Where each pinned frame's length claim is, and a claim that does not fit its bytes:
# a fifth PCM sample, and a first Opus packet one byte longer than the frame.
WRONG_LENGTH_EDITS = {
    frames.SOUND_PCM_CODEC: (20, (5).to_bytes(4, "little")),
    frames.SOUND_OPUS_CODEC: (26, (4).to_bytes(2, "little")),
}


def test_a_pcm_frame_round_trips_its_samples() -> None:
    payload = FRAMES[frames.SOUND_PCM_CODEC]

    frame = frames.read_pcm(payload)

    assert frame.phone_ns == 1
    assert frame.elapsed_ns == 0
    assert frame.sample_rate_hz == SAMPLE_RATE_HZ
    assert frame.sample_count == 4
    samples = struct.unpack(f"<{frame.sample_count}h", frame.pcm)
    assert samples == (0, 1000, -1000, 32767)
    assert frames.pcm_payload(1, 0, SAMPLE_RATE_HZ, samples) == payload


def test_an_opus_frame_round_trips_every_packet_with_its_length() -> None:
    payload = FRAMES[frames.SOUND_OPUS_CODEC]

    frame = frames.read_opus(payload)

    assert frame.phone_ns == 1
    assert frame.elapsed_ns == 0
    assert frame.sample_rate_hz == SAMPLE_RATE_HZ
    assert frame.sample_count == 640
    assert frame.packets == (b"\xfc\x01\x02", b"\xfc\x03")
    assert frames.opus_payload(1, 0, SAMPLE_RATE_HZ, frame.packets, 640) == payload


@pytest.mark.parametrize("codec", CODECS)
def test_a_truncated_sound_payload_is_refused(codec: str) -> None:
    with pytest.raises(ValueError):
        READERS[codec](FRAMES[codec][:-2])


@pytest.mark.parametrize("codec", CODECS)
def test_a_sound_payload_whose_length_disagrees_with_its_bytes_is_refused(codec: str) -> None:
    offset, edit = WRONG_LENGTH_EDITS[codec]
    frame = FRAMES[codec]
    payload = frame[:offset] + edit + frame[offset + len(edit) :]

    with pytest.raises(ValueError):
        READERS[codec](payload)


@pytest.mark.parametrize("codec", CODECS)
def test_a_sound_log_verifies_like_any_other_frame_log(tmp_path: Path, codec: str) -> None:
    path = tmp_path / "sound.log"
    path.write_bytes(fx.sound_log(duration_s=3, codec=codec))

    check = verify_recording_file(path, "sound.log")

    assert check.frames == 3
    assert check.valid_bytes == path.stat().st_size
    assert check.truncated is False
    assert check.gaps == ()


def _write_recording(root: Path, codec: str, *, duration_s: int = 10) -> None:
    meta = fx.meta(duration_s=duration_s, ecg=False, acc=False)
    meta["streams"] = [
        {
            "id": "hr",
            "type": "heart_rate",
            "codec": "polar-hr-v1",
            "file": "hr.log",
            "rate_hz": 1.0,
        },
        {
            "id": "sound",
            "type": "sound",
            "codec": codec,
            "file": "sound.log",
            "rate_hz": SAMPLE_RATE_HZ,
        },
    ]
    (root / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    (root / "hr.log").write_bytes(fx.hr_log(duration_s=duration_s))
    (root / "sound.log").write_bytes(fx.sound_log(duration_s=duration_s, codec=codec))


@pytest.mark.parametrize("codec", CODECS)
def test_a_recording_with_sound_next_to_heart_rate_loads_and_reports_the_stream(
    tmp_path: Path, codec: str
) -> None:
    _write_recording(tmp_path, codec)

    recording = load_recording(tmp_path)

    assert recording.hr is not None
    assert recording.hr.t_s.size == 10
    report = recording.quality.streams["sound"]
    assert report.present
    assert report.frames == 10
    assert report.samples == 10 * SAMPLE_RATE_HZ
    assert report.rate_hz == float(SAMPLE_RATE_HZ)
    assert report.coverage == pytest.approx(1.0)
    assert report.truncated_tail is False
    assert recording.extra_streams["sound"]["codec"] == codec
    assert recording.extra_streams["sound"]["samples"] == 10 * SAMPLE_RATE_HZ


def test_a_stager_that_does_not_read_sound_is_told_the_same_thing_about_it(
    tmp_path: Path,
) -> None:
    _write_recording(tmp_path, frames.SOUND_OPUS_CODEC)

    everything = load_recording(tmp_path)
    stager = load_recording(tmp_path, streams=("hr",))

    assert stager.quality.to_dict() == everything.quality.to_dict()


def test_a_damaged_sound_tail_keeps_the_frames_before_it_and_is_reported(tmp_path: Path) -> None:
    _write_recording(tmp_path, frames.SOUND_OPUS_CODEC, duration_s=3)
    with (tmp_path / "sound.log").open("ab") as handle:
        handle.write(b"\x10\x00\x00\x00broken")

    recording = load_recording(tmp_path)

    report = recording.quality.streams["sound"]
    assert report.frames == 3
    assert report.samples == 3 * SAMPLE_RATE_HZ
    assert report.truncated_tail is True
    assert "truncated" in (report.error or "")
