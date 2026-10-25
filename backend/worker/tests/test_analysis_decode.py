from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np

from tests import analysis_fixture as fx
from taiga_worker.analysis.decode import load_recording


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


class DecodeTest(unittest.TestCase):
    def test_decodes_units_timestamps_and_quality(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=6)
            recording = load_recording(tmp)

        self.assertIsNotNone(recording.hr)
        assert recording.hr is not None
        self.assertEqual(recording.hr.t_s.size, 6)
        self.assertTrue(np.all(recording.hr.contact))
        self.assertTrue(np.all(recording.hr.valid))
        self.assertEqual(len(recording.hr.rr_ms), 6)

        self.assertIsNotNone(recording.ecg)
        assert recording.ecg is not None
        self.assertEqual(recording.ecg.t_s.size, 6 * 130)
        self.assertAlmostEqual(float(recording.ecg.rate_hz), 130.0, delta=1.0)
        self.assertAlmostEqual(float(recording.ecg.t_s[0]), 0.0, delta=0.1)
        self.assertAlmostEqual(float(recording.ecg.t_s[-1]), 6.0, delta=0.2)
        # Fixture values are 1000 * ((i % 20) - 10) µV -> -10 mV at i=0.
        self.assertAlmostEqual(float(recording.ecg.voltage_mv[0]), -10.0, places=3)
        self.assertFalse(np.any(recording.ecg.saturated))

        self.assertIsNotNone(recording.acc)
        assert recording.acc is not None
        self.assertEqual(recording.acc.xyz_g.shape, (6 * 50, 3))
        self.assertAlmostEqual(float(recording.acc.xyz_g[0, 2]), 1.0, places=4)
        self.assertAlmostEqual(float(recording.acc.xyz_g[0, 0]), 0.0, places=4)

        self.assertAlmostEqual(recording.duration_s, 6.0, delta=0.1)
        self.assertIsNotNone(recording.quality.hr_contact_fraction)
        self.assertAlmostEqual(recording.quality.hr_contact_fraction, 1.0)
        self.assertIsNotNone(recording.quality.ecg_saturated_fraction)
        self.assertAlmostEqual(recording.quality.ecg_saturated_fraction, 0.0)
        self.assertGreater(recording.quality.streams["ecg"].coverage, 0.9)
        self.assertGreater(recording.quality.streams["acc"].coverage, 0.9)

    def test_no_contact_marks_hr_invalid(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=6, contact=False)
            recording = load_recording(tmp)

        assert recording.hr is not None
        self.assertFalse(np.any(recording.hr.valid))
        self.assertIsNotNone(recording.quality.hr_contact_fraction)
        self.assertAlmostEqual(recording.quality.hr_contact_fraction, 0.0)

    def test_truncated_tail_keeps_the_valid_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=6, bad_hr_tail=b"\x10\x00\x00\x00abc")
            recording = load_recording(tmp)

        assert recording.hr is not None
        self.assertEqual(recording.hr.t_s.size, 6)
        report = recording.quality.streams["hr"]
        self.assertTrue(report.truncated_tail)
        self.assertIn("truncated", report.error or "")

    def test_bad_crc_keeps_the_valid_prefix_and_reports_it(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=6, bad_hr_tail=fx.frame(b"abcd", bad_crc=True))
            recording = load_recording(tmp)

        assert recording.hr is not None
        self.assertEqual(recording.hr.t_s.size, 6)
        report = recording.quality.streams["hr"]
        self.assertTrue(report.truncated_tail)
        self.assertIn("CRC", report.error or "")

    def test_missing_streams_are_reported_without_crashing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=6, include_ecg=False, include_acc=False)
            (Path(tmp) / "hr.log").unlink()
            recording = load_recording(tmp)

        self.assertIsNone(recording.hr)
        self.assertIsNone(recording.ecg)
        self.assertIsNone(recording.acc)
        self.assertFalse(recording.quality.streams["hr"].present)
        self.assertFalse(recording.quality.streams["ecg"].present)
        self.assertFalse(recording.quality.streams["acc"].present)

    def test_meta_is_parsed_and_raw_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=6)
            recording = load_recording(tmp)

        self.assertEqual(recording.meta.session_id, "20261023_223000")
        self.assertEqual(recording.meta.state, "stopped")
        self.assertEqual(recording.meta.ecg_rate_hz, 130.0)
        self.assertEqual(recording.meta.raw["version"], 1)

    def test_clock_sync_is_not_a_clock_jump(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=6)
            with (Path(tmp) / "events.jsonl").open("ab") as handle:
                handle.write(
                    fx.event_line(
                        fx.START_NS + 1_000_000_000,
                        1_000_000_000,
                        "clock_sync",
                        "strap clock set",
                    )
                )
            recording = load_recording(tmp)

        self.assertEqual(len(recording.quality.clock_syncs), 1)
        sync = recording.quality.clock_syncs[0]
        self.assertEqual(sync.source, "event")
        self.assertEqual(sync.message, "strap clock set")
        self.assertEqual(recording.quality.clock_jumps, ())

    def test_phone_wall_clock_jump_is_reported_but_elapsed_time_wins(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=120)
            (Path(tmp) / "ecg.log").write_bytes(_two_batch_ecg(phone_jump_s=10.0))
            recording = load_recording(tmp)

        jumps = recording.quality.clock_jumps
        self.assertEqual(len(jumps), 1)
        self.assertEqual(jumps[0].source, "phone_wall_clock")
        self.assertEqual(jumps[0].stream, "ecg")
        self.assertAlmostEqual(jumps[0].delta_s, 10.0, delta=0.1)
        assert recording.ecg is not None
        self.assertLess(float(recording.ecg.t_s[-1]), 125.0)
        self.assertTrue(any("phone wall-clock" in warning for warning in recording.quality.warnings))

    def test_strap_clock_jump_is_reported_and_does_not_move_the_timeline(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fx.write_recording(tmp, duration_s=120)
            (Path(tmp) / "ecg.log").write_bytes(_two_batch_ecg(strap_jump_s=10.0))
            recording = load_recording(tmp)

        jumps = recording.quality.clock_jumps
        self.assertEqual(len(jumps), 1)
        self.assertEqual(jumps[0].source, "strap")
        self.assertEqual(jumps[0].stream, "ecg")
        self.assertAlmostEqual(jumps[0].delta_s, 10.0, delta=0.1)
        assert recording.ecg is not None
        self.assertAlmostEqual(float(recording.ecg.t_s[0]), 0.0, delta=0.2)
        self.assertAlmostEqual(float(recording.ecg.t_s[-1]), 120.0, delta=0.2)
        self.assertTrue(any("strap" in warning for warning in recording.quality.warnings))


if __name__ == "__main__":
    unittest.main()
