from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

from taiga_worker.analysis.algorithms import sleepecg
from taiga_worker.analysis.contract import AnalysisError, HrStream
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.pipeline import run_analysis
from tests import analysis_fixture as fx


def _recording(tmp_path: Path, duration_s: int = 120, contact_until: int | None = None):
    fx.write_recording(tmp_path, duration_s=duration_s, include_acc=False)
    recording = load_recording(tmp_path)
    seconds = np.arange(duration_s, dtype=np.float64)
    contact = np.ones(duration_s, dtype=bool)
    if contact_until is not None:
        contact[contact_until:] = False
    hr = HrStream(
        t_s=seconds,
        hr_bpm=np.full(duration_s, 60.0),
        contact_supported=np.ones(duration_s, dtype=bool),
        contact=contact,
        valid=contact.copy(),
        rr_ms=tuple(np.asarray([1000.0]) for _ in range(duration_s)),
        phone_ns=(recording.start_unix_ns + seconds * 1_000_000_000).astype(np.int64),
    )
    return replace(recording, hr=hr)


def _config(**overrides):
    return sleepecg._params(
        {
            "min_usable_epochs": 1,
            "min_unknown_epochs": 1,
            "smooth_window_epochs": 1,
            **overrides,
        }
    )


def test_rr_heartbeats_are_reconstructed_from_rr_intervals(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=120)
    beats = sleepecg.rr_heartbeats(recording)
    assert beats.size >= 118
    assert beats[0] == pytest.approx(0.0, abs=0.1)
    assert beats[-1] == pytest.approx(118.0, abs=0.1)
    assert np.all(np.diff(beats) > 0.9)


def test_rr_heartbeats_stop_when_contact_is_lost(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=120, contact_until=60)
    beats = sleepecg.rr_heartbeats(recording)
    assert beats.size >= 58
    assert beats[-1] < 60.5


def test_missing_streams_fail_clearly(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=120, contact_until=0)
    with pytest.raises(AnalysisError) as context:
        sleepecg._beats(
            replace(recording, ecg=None), _config(prefer_ecg=False)
        )
    assert context.value.code == "insufficient_beats"

    empty_hr = replace(
        recording,
        hr=HrStream(
            t_s=np.empty(0),
            hr_bpm=np.empty(0),
            contact_supported=np.empty(0, dtype=bool),
            contact=np.empty(0, dtype=bool),
            valid=np.empty(0, dtype=bool),
            rr_ms=(),
            phone_ns=np.empty(0, dtype=np.int64),
        ),
    )
    with pytest.raises(AnalysisError) as context:
        sleepecg._beats(replace(empty_hr, ecg=None), _config(prefer_ecg=True))
    assert context.value.code == "insufficient_beats"

    with pytest.raises(AnalysisError) as context:
        sleepecg._beats(
            replace(empty_hr, hr=None, ecg=None), _config(prefer_ecg=True)
        )
    assert context.value.code == "missing_stream"


def test_three_class_result_mapping_and_supported_stages(tmp_path: Path) -> None:
    recording = _recording(tmp_path)
    raw = np.asarray(
        [
            [0.0, 0.7, 0.2, 0.1],  # NREM
            [0.0, 0.1, 0.8, 0.1],  # REM
            [0.0, 0.1, 0.1, 0.8],  # WAKE
            [0.0, 0.6, 0.3, 0.1],  # NREM
        ],
        dtype=np.float64,
    )
    beats = np.arange(1, 120, 1.0)
    prepared = sleepecg.build_result(
        recording,
        _config(prefer_ecg=False),
        ("nrem", "rem", "wake"),
        raw,
        beats,
        "rr",
    )

    assert prepared.stages == ["nrem", "rem", "wake", "nrem"]
    assert prepared.confidence is not None
    assert prepared.probabilities["nrem"] == pytest.approx([0.7, 0.1, 0.1, 0.6])
    assert set(prepared.probabilities) == {"nrem", "rem", "wake"}
    assert prepared.beat_source == "rr"


def test_two_class_result_mapping(tmp_path: Path) -> None:
    recording = _recording(tmp_path)
    raw = np.asarray(
        [
            [0.0, 0.8, 0.2],  # SLEEP
            [0.0, 0.2, 0.8],  # WAKE
            [0.0, 0.7, 0.3],  # SLEEP
            [0.0, 0.4, 0.6],  # WAKE
        ],
        dtype=np.float64,
    )
    prepared = sleepecg.build_result(
        recording,
        _config(prefer_ecg=False),
        ("sleep", "wake"),
        raw,
        np.arange(1, 120, 1.0),
        "rr",
    )
    assert prepared.stages == ["sleep", "wake", "sleep", "wake"]
    assert set(prepared.probabilities) == {"sleep", "wake"}


def test_low_contact_becomes_unknown(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=240, contact_until=120)
    raw = np.zeros((8, 4), dtype=np.float64)
    raw[:, 1] = 0.8
    raw[:, 3] = 0.2
    prepared = sleepecg.build_result(
        recording,
        _config(prefer_ecg=False, min_unknown_epochs=2),
        ("nrem", "rem", "wake"),
        raw,
        np.arange(1, 120, 1.0),  # beats only in the first half
        "rr",
    )
    assert prepared.stages[:4] == ["nrem"] * 4
    assert prepared.stages[4:] == ["unknown"] * 4
    assert prepared.artifacts == [False] * 4 + [True] * 4
    assert prepared.unknown_epochs == 4


def test_probability_smoothing_removes_a_single_epoch_outlier(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=240)
    epochs = 8
    raw = np.zeros((epochs, 4), dtype=np.float64)
    raw[:, 1] = 0.2
    raw[:, 2] = 0.8
    raw[3, 1] = 0.8
    raw[3, 2] = 0.2
    prepared = sleepecg.build_result(
        recording,
        _config(prefer_ecg=False, smooth_window_epochs=5),
        ("nrem", "rem", "wake"),
        raw,
        np.arange(1, 239, 1.0),
        "rr",
    )
    assert prepared.stages[3] == "rem"


def test_invalid_params_are_rejected() -> None:
    with pytest.raises(AnalysisError) as context:
        sleepecg._params({"not_a_parameter": 1})
    assert context.value.code == "invalid_params"

    for params in (
        {"classifier": "nope"},
        {"prefer_ecg": "yes"},
        {"age": -1},
        {"gender": "other"},
        {"max_nans": 2},
        {"min_beats_per_epoch": 0},
        {"min_usable_epochs": 0},
        {"min_unknown_epochs": 0},
        {"smooth_window_epochs": 0},
        {"min_contact_fraction": 2},
        {"max_undefined_probability": 2},
    ):
        with pytest.raises(AnalysisError) as context:
            sleepecg._params(params)
        assert context.value.code == "invalid_params"


def test_nanpsd_workaround_handles_all_nan_rows() -> None:
    pytest.importorskip("sleepecg")
    sleepecg._install_nanpsd_fix()
    from sleepecg import feature_extraction as fe

    values = np.full((2, 8), np.nan)
    frequencies, psd = fe._nanpsd(values, fs=4.0, max_nans=0.5)
    assert frequencies.shape == (5,)
    assert psd.shape == (2, 5)
    assert np.all(np.isnan(psd))


def test_classifier_smoke(tmp_path: Path) -> None:
    pytest.importorskip("sleepecg")
    pytest.importorskip("keras")
    pytest.importorskip("torch")
    if not sleepecg.availability()[0]:
        pytest.skip("SleepECG is not available")

    duration = 300
    fx.write_recording(tmp_path, duration_s=duration, include_acc=False)
    seconds = np.arange(duration, dtype=np.float64)
    rr = 1.0 + 0.03 * np.sin(seconds / 17.0)
    data = bytearray(fx.frame_header(fx.STREAM_HR))
    for second, value in enumerate(rr):
        data += fx.frame(
            fx.hr_payload(
                fx.START_NS + second * 1_000_000_000,
                second * 1_000_000_000,
                60,
                flags=3,
                rr_ms=(int(value * 1000.0),),
            )
        )
    (tmp_path / "hr.log").write_bytes(bytes(data))

    output = run_analysis(
        str(tmp_path),
        "sleep-ecg",
        {
            "prefer_ecg": False,
            "min_usable_epochs": 1,
            "min_unknown_epochs": 1,
        },
    )
    payload = output.to_dict()
    assert payload["model"] == "sleep-ecg"
    assert payload["model_version"] == "1.0.0"
    assert payload["stages"]["count"] == 10
    assert payload["capabilities"]["probabilities"] is True
    assert payload["capabilities"]["no_deep"] is True
    assert "deep" not in payload["capabilities"]["supported_stages"]
    assert payload["provenance"]["algorithm"]["classifier"] == "wrn-gru-mesa"
    assert payload["provenance"]["algorithm"]["package"] == "sleepecg"
    assert payload["provenance"]["algorithm"]["package_version"]


def test_rr_only_recording_uses_the_rr_path(tmp_path: Path) -> None:
    pytest.importorskip("sleepecg")
    pytest.importorskip("keras")
    pytest.importorskip("torch")
    if not sleepecg.availability()[0]:
        pytest.skip("SleepECG is not available")

    duration = 300
    fx.write_recording(tmp_path, duration_s=duration, include_ecg=False, include_acc=False)
    data = bytearray(fx.frame_header(fx.STREAM_HR))
    for second in range(duration):
        data += fx.frame(
            fx.hr_payload(
                fx.START_NS + second * 1_000_000_000,
                second * 1_000_000_000,
                60,
                flags=3,
                rr_ms=(1000,),
            )
        )
    (tmp_path / "hr.log").write_bytes(bytes(data))

    output = run_analysis(
        str(tmp_path),
        "sleep-ecg",
        {
            "prefer_ecg": True,
            "min_usable_epochs": 1,
            "min_unknown_epochs": 1,
        },
    )
    payload = output.to_dict()
    assert payload["provenance"]["algorithm"]["beat_source"] == "rr"
    assert payload["stages"]["count"] == 10
