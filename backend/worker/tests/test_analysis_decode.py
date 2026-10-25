from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from taiga_worker.analysis.decode import load_recording
from tests import analysis_fixture as fx


def _two_batch_ecg(*, phone_jump_s: float = 0.0, strap_jump_s: float = 0.0) -> bytes:
    rate = 130
    n = 60 * rate
    spacing = 1_000_000_000 // rate
    first = fx.POLAR_START_NS
    last = first + (n - 1) * spacing
    batch_one = fx.frame(
        fx.ecg_payload(
            fx.START_NS + 60_000_000_000,
            60_000_000_000,
            first,
            last,
            [0] * n,
        )
    )
    second = first + 60_000_000_000 + int(strap_jump_s * 1_000_000_000)
    second_last = second + (n - 1) * spacing
    batch_two = fx.frame(
        fx.ecg_payload(
            fx.START_NS + 120_000_000_000 + int(phone_jump_s * 1_000_000_000),
            120_000_000_000,
            second,
            second_last,
            [0] * n,
        )
    )
    return fx.frame_header(fx.STREAM_ECG) + batch_one + batch_two


def test_decodes_units_timestamps_and_quality(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=6)
    recording = load_recording(tmp_path)

    assert recording.hr is not None
    assert recording.hr.t_s.size == 6
    assert np.all(recording.hr.contact)
    assert np.all(recording.hr.valid)
    assert len(recording.hr.rr_ms) == 6

    assert recording.ecg is not None
    assert recording.ecg.t_s.size == 6 * 130
    assert float(recording.ecg.rate_hz) == pytest.approx(130.0, abs=1.0)
    assert float(recording.ecg.t_s[0]) == pytest.approx(0.0, abs=0.1)
    assert float(recording.ecg.t_s[-1]) == pytest.approx(6.0, abs=0.2)
    # Fixture values are 1000 * ((i % 20) - 10) µV -> -10 mV at i=0.
    assert float(recording.ecg.voltage_mv[0]) == pytest.approx(-10.0, abs=1e-3)
    assert not np.any(recording.ecg.saturated)

    assert recording.acc is not None
    assert recording.acc.xyz_g.shape == (6 * 50, 3)
    assert float(recording.acc.xyz_g[0, 2]) == pytest.approx(1.0, abs=1e-4)
    assert float(recording.acc.xyz_g[0, 0]) == pytest.approx(0.0, abs=1e-4)

    assert recording.duration_s == pytest.approx(6.0, abs=0.1)
    assert recording.quality.hr_contact_fraction is not None
    assert recording.quality.hr_contact_fraction == pytest.approx(1.0)
    assert recording.quality.ecg_saturated_fraction is not None
    assert recording.quality.ecg_saturated_fraction == pytest.approx(0.0)
    assert recording.quality.streams["ecg"].coverage > 0.9
    assert recording.quality.streams["acc"].coverage > 0.9


def test_no_contact_marks_hr_invalid(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=6, contact=False)
    recording = load_recording(tmp_path)

    assert recording.hr is not None
    assert not np.any(recording.hr.valid)
    assert recording.quality.hr_contact_fraction is not None
    assert recording.quality.hr_contact_fraction == pytest.approx(0.0)


def test_truncated_tail_keeps_the_valid_prefix(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=6, bad_hr_tail=b"\x10\x00\x00\x00abc")
    recording = load_recording(tmp_path)

    assert recording.hr is not None
    assert recording.hr.t_s.size == 6
    report = recording.quality.streams["hr"]
    assert report.truncated_tail
    assert "truncated" in (report.error or "")


def test_bad_crc_keeps_the_valid_prefix_and_reports_it(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=6, bad_hr_tail=fx.frame(b"abcd", bad_crc=True))
    recording = load_recording(tmp_path)

    assert recording.hr is not None
    assert recording.hr.t_s.size == 6
    report = recording.quality.streams["hr"]
    assert report.truncated_tail
    assert "CRC" in (report.error or "")


def test_missing_streams_are_reported_without_crashing(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=6, include_ecg=False, include_acc=False)
    (tmp_path / "hr.log").unlink()
    recording = load_recording(tmp_path)

    assert recording.hr is None
    assert recording.ecg is None
    assert recording.acc is None
    assert not recording.quality.streams["hr"].present
    assert not recording.quality.streams["ecg"].present
    assert not recording.quality.streams["acc"].present


def test_meta_is_parsed_and_raw_is_preserved(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=6)
    recording = load_recording(tmp_path)

    assert recording.meta.session_id == "20261023_223000"
    assert recording.meta.state == "stopped"
    assert recording.meta.ecg_rate_hz == 130.0
    assert recording.meta.raw["version"] == 1


def test_clock_sync_is_not_a_clock_jump(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=6)
    with (tmp_path / "events.jsonl").open("ab") as handle:
        handle.write(
            fx.event_line(
                fx.START_NS + 1_000_000_000,
                1_000_000_000,
                "clock_sync",
                "strap clock set",
            )
        )
    recording = load_recording(tmp_path)

    assert len(recording.quality.clock_syncs) == 1
    sync = recording.quality.clock_syncs[0]
    assert sync.source == "event"
    assert sync.message == "strap clock set"
    assert recording.quality.clock_jumps == ()


def test_phone_wall_clock_jump_is_reported_but_elapsed_time_wins(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    (tmp_path / "ecg.log").write_bytes(_two_batch_ecg(phone_jump_s=10.0))
    recording = load_recording(tmp_path)

    jumps = recording.quality.clock_jumps
    assert len(jumps) == 1
    assert jumps[0].source == "phone_wall_clock"
    assert jumps[0].stream == "ecg"
    assert jumps[0].delta_s == pytest.approx(10.0, abs=0.1)
    assert recording.ecg is not None
    assert float(recording.ecg.t_s[-1]) < 125.0
    assert any("phone wall-clock" in warning for warning in recording.quality.warnings)


def test_strap_clock_jump_is_reported_and_does_not_move_the_timeline(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    (tmp_path / "ecg.log").write_bytes(_two_batch_ecg(strap_jump_s=10.0))
    recording = load_recording(tmp_path)

    jumps = recording.quality.clock_jumps
    assert len(jumps) == 1
    assert jumps[0].source == "strap"
    assert jumps[0].stream == "ecg"
    assert jumps[0].delta_s == pytest.approx(10.0, abs=0.1)
    assert recording.ecg is not None
    assert float(recording.ecg.t_s[0]) == pytest.approx(0.0, abs=0.2)
    assert float(recording.ecg.t_s[-1]) == pytest.approx(120.0, abs=0.2)
    assert any("strap" in warning for warning in recording.quality.warnings)
