"""Codec fixtures shared with the app, and one recording per tracker kind."""

from __future__ import annotations

import json
import struct
from pathlib import Path

import numpy as np
import pytest
from taiga_worker import frames
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.pipeline import run_analysis

from tests import analysis_fixture as fx

FIXTURE = (
    Path(__file__).resolve().parents[3]
    / "shared"
    / "codec-fixtures"
    / "heart-rate-measurement.json"
)


def _meta(kind: str, streams: list[dict], duration_s: int = 8) -> dict:
    return {
        "format": "taiga-recording",
        "version": 1,
        "session_id": "20261023_223000",
        "state": "stopped",
        "mode": "stream",
        "start_utc_ms": fx.START_UTC_MS,
        "end_utc_ms": fx.START_UTC_MS + duration_s * 1000,
        "timezone": "UTC",
        "app": {"version": "test", "version_code": 1},
        "phone": {"manufacturer": "Test", "model": "Synthetic", "android": "16", "sdk": 36},
        "sensor": {"kind": kind, "id": "TEST0001", "name": kind},
        "config": {"hr": True, "ecg": False, "acc": False},
        "streams": streams,
    }


def _write(tmp_path: Path, meta: dict, logs: dict[str, bytes]) -> None:
    (tmp_path / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    for name, content in logs.items():
        (tmp_path / name).write_bytes(content)


def _polar_hr_log(duration_s: int = 8) -> bytes:
    data = bytearray(frames.frame_header(frames.STREAM_HR))
    for second in range(duration_s):
        payload = fx.hr_payload(
            fx.START_NS + second * 1_000_000_000,
            second * 1_000_000_000,
            60,
            rr_ms=(1000,),
        )
        data += frames.encode_frame(payload)
    return bytes(data)


def _ble_hrs_payload(phone_ns: int, elapsed_ns: int, value: bytes) -> bytes:
    return struct.pack("<qqH", phone_ns, elapsed_ns, len(value)) + value


def _ble_hr_log(duration_s: int = 8) -> bytes:
    data = bytearray(frames.frame_header(frames.STREAM_HR))
    for second in range(duration_s):
        # flags: contact supported, contact and RR present; 60 bpm, one 1 s interval
        value = bytes([0x16, 60]) + (1024).to_bytes(2, "little")
        data += frames.encode_frame(
            _ble_hrs_payload(fx.START_NS + second * 1_000_000_000, second * 1_000_000_000, value)
        )
    return bytes(data)


def _ppi_log(duration_s: int = 8) -> bytes:
    data = bytearray(frames.frame_header(frames.STREAM_PPI))
    for second in range(duration_s):
        payload = struct.pack(
            "<qqH", fx.START_NS + second * 1_000_000_000, second * 1_000_000_000, 1
        )
        payload += struct.pack("<qHHBB", second * 1_000_000_000, 1000, 10, 60, 3)
        data += frames.encode_frame(payload)
    return bytes(data)


def test_every_heart_rate_flag_combination_matches_the_app_fixture() -> None:
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    assert len(data["cases"]) == 32
    for case in data["cases"]:
        decoded = frames.decode_heart_rate_measurement(bytes.fromhex(case["value_hex"]))
        expected = case["expected"]
        assert decoded["hr_bpm"] == expected["hr_bpm"], case["name"]
        assert decoded["contact_supported"] == expected["contact_supported"], case["name"]
        assert decoded["contact"] == expected["contact"], case["name"]
        assert decoded["rr_present"] == expected["rr_present"], case["name"]
        assert decoded["rr_ms"] == expected["rr_ms"], case["name"]
        assert decoded["energy_expended"] == expected["energy_expended"], case["name"]


@pytest.mark.parametrize("kind", ["polar-h10", "polar-verity-sense", "polar-loop"])
def test_a_polar_tracker_kind_decodes_and_yields_heart_rate(tmp_path: Path, kind: str) -> None:
    streams = [
        {
            "id": "hr",
            "type": "heart_rate",
            "codec": "polar-hr-v1",
            "file": "hr.log",
            "rate_hz": 1.0,
        },
        {"id": "rr", "type": "rr_interval", "codec": "polar-hr-v1", "file": "hr.log"},
    ]
    _write(tmp_path, _meta(kind, streams), {"hr.log": _polar_hr_log()})

    recording = load_recording(tmp_path)

    assert recording.hr is not None
    means = recording.epochs(30).hr_mean_bpm
    assert np.any(np.isfinite(means))
    assert float(np.nanmean(means)) == pytest.approx(60.0, abs=2.0)


def test_a_heart_rate_service_recording_decodes_and_yields_heart_rate(tmp_path: Path) -> None:
    streams = [
        {"id": "hr", "type": "heart_rate", "codec": "ble-hrs-v1", "file": "hr.log"},
        {"id": "rr", "type": "rr_interval", "codec": "ble-hrs-v1", "file": "hr.log"},
    ]
    _write(tmp_path, _meta("ble-hrs", streams), {"hr.log": _ble_hr_log()})

    recording = load_recording(tmp_path)

    assert recording.hr is not None
    assert np.all(recording.hr.contact)
    assert int(recording.hr.rr_ms[0].size) == 1
    means = recording.epochs(30).hr_mean_bpm
    assert np.any(np.isfinite(means))
    assert float(np.nanmean(means)) == pytest.approx(60.0, abs=2.0)


def test_a_heart_rate_service_recording_runs_the_existing_analysis(tmp_path: Path) -> None:
    streams = [
        {"id": "hr", "type": "heart_rate", "codec": "ble-hrs-v1", "file": "hr.log"},
        {"id": "rr", "type": "rr_interval", "codec": "ble-hrs-v1", "file": "hr.log"},
    ]
    _write(tmp_path, _meta("ble-hrs", streams, duration_s=120), {"hr.log": _ble_hr_log(120)})

    output = run_analysis(str(tmp_path), "baseline", {})

    assert output.hr["count"] > 0
    assert any(item.get("id") == "hr_mean_bpm" for item in output.items)


def test_pulse_intervals_give_the_analysis_a_heart_rate(tmp_path: Path) -> None:
    streams = [
        {"id": "ppi", "type": "pulse_interval", "codec": "polar-ppi-v1", "file": "ppi.log"},
    ]
    _write(
        tmp_path,
        _meta("polar-verity-sense", streams, duration_s=120),
        {"ppi.log": _ppi_log(120)},
    )

    output = run_analysis(str(tmp_path), "baseline", {})

    assert output.hr["count"] > 0
    assert any(item.get("id") == "hr_mean_bpm" for item in output.items)


def test_pulse_intervals_are_decoded_and_kept(tmp_path: Path) -> None:
    streams = [
        {
            "id": "ppi",
            "type": "pulse_interval",
            "codec": "polar-ppi-v1",
            "file": "ppi.log",
        }
    ]
    _write(tmp_path, _meta("polar-verity-sense", streams), {"ppi.log": _ppi_log()})

    recording = load_recording(tmp_path)

    assert "pulse_interval" in recording.extra_streams
    decoded = recording.extra_streams["pulse_interval"]
    assert decoded["values"].tolist() == [1000.0] * 8
    assert decoded["flags"].tolist() == [3] * 8
