from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest
from taiga_worker.analysis.algorithms import sleepecg
from taiga_worker.analysis.beats import beat_series
from taiga_worker.analysis.contract import AnalysisError, EcgStream, Gap, HrStream
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.pipeline import run_analysis

from tests import analysis_fixture as fx

ECG_RATE_HZ = 130.0


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


def _hr_stream(recording, times_s, batches) -> HrStream:
    times = np.asarray(times_s, dtype=np.float64)
    return HrStream(
        t_s=times,
        hr_bpm=np.full(times.size, 60.0),
        contact_supported=np.ones(times.size, dtype=bool),
        contact=np.ones(times.size, dtype=bool),
        valid=np.ones(times.size, dtype=bool),
        rr_ms=tuple(np.asarray(batch, dtype=np.float64) for batch in batches),
        phone_ns=(recording.start_unix_ns + times * 1_000_000_000).astype(np.int64),
    )


def _impulse_ecg(times_s: np.ndarray, beat_times_s: np.ndarray):
    """An ECG that is zero except for one sample of 1 mV at every requested beat."""

    indices = np.searchsorted(times_s, np.asarray(beat_times_s, dtype=np.float64))
    voltage = np.zeros(times_s.size, dtype=np.float32)
    voltage[indices] = 1.0
    ecg = EcgStream(
        t_s=times_s,
        voltage_mv=voltage,
        saturated=np.zeros(times_s.size, dtype=bool),
        rate_hz=ECG_RATE_HZ,
    )
    return ecg, times_s[indices]


def _impulse_detector(calls: list[int]):
    """A known detector: every sample above the noise floor is a beat."""

    def detect(voltage_mv: np.ndarray, rate_hz: float) -> np.ndarray:
        calls.append(int(voltage_mv.size))
        return np.flatnonzero(voltage_mv >= 0.5)

    return detect


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
    assert beats.size == 120
    assert beats[0] == pytest.approx(0.0, abs=0.1)
    # Every one-second interval completes at its notification, so the last beat is the
    # notification at 119 s and not one interval before it.
    assert beats[-1] == pytest.approx(119.0, abs=0.1)
    assert np.all(np.diff(beats) > 0.9)


def test_rr_heartbeats_chain_the_intervals_through_jittered_arrival_times(
    tmp_path: Path,
) -> None:
    recording = _recording(tmp_path, duration_s=400)
    intervals = np.asarray(fx.default_rr_series(300), dtype=np.float64)
    ends_s = np.cumsum(intervals) / 1000.0
    rng = np.random.default_rng(11)
    # A notification arrives at the beat it carries, jittered by the Bluetooth link.
    times = ends_s + rng.uniform(-0.2, 0.2, intervals.size)
    recording = replace(
        recording,
        hr=_hr_stream(recording, times, [[value] for value in intervals]),
    )

    beats = sleepecg.rr_heartbeats(recording)

    # The reconstructed intervals are the reported ones, not the distance between the
    # times the notifications arrived.
    intervals_in_beats = np.diff(beats) * 1000.0
    assert intervals_in_beats == pytest.approx(intervals[-intervals_in_beats.size :], abs=1.0)
    assert intervals_in_beats.size >= intervals.size - 1


def test_rr_path_survives_empty_notifications_and_a_backlog(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=200)
    # Below about 60 bpm a one-second notification carries no interval, and a backlog
    # delivers several at once; neither loses a beat or breaks the run.
    rr = fx.rr_notifications(
        duration_s=200,
        mean_rr_ms=1300.0,
        spread=0.03,
        jitter_s=0.2,
        catchups=((60.0, 6.0),),
        seed=5,
    )
    recording = replace(recording, hr=_hr_stream(recording, rr.times_s, rr.batches))

    series = beat_series(recording)
    assert series is not None
    assert np.count_nonzero(series.run_start) == 0
    expected = rr.intervals_ms[rr.kept]
    assert series.rr_ms == pytest.approx(expected, abs=1.0)

    beats, source = sleepecg._beats(recording, _config(prefer_ecg=False))
    assert source == "rr"
    assert beats.size >= expected.size
    assert np.all(np.diff(beats) < 2.0)


def test_rr_heartbeats_stop_when_contact_is_lost(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=120, contact_until=60)
    beats = sleepecg.rr_heartbeats(recording)
    assert beats.size >= 58
    assert beats[-1] < 60.5


def test_ecg_beats_after_a_gap_keep_their_true_time(tmp_path: Path) -> None:
    rate = ECG_RATE_HZ
    times = np.concatenate(
        [
            np.arange(0, int(20 * rate)) / rate,
            # Ten seconds of samples never reached the phone.
            np.arange(int(28 * rate), int(48 * rate)) / rate,
        ]
    )
    beats = np.concatenate([np.arange(1.0, 20.0, 0.9), np.arange(29.0, 47.0, 0.9)])
    ecg, true_times = _impulse_ecg(times, beats)
    recording = _recording(tmp_path, duration_s=48)
    recording = replace(
        recording,
        ecg=ecg,
        quality=replace(recording.quality, gaps=(Gap("ecg", 20.0, 28.0),)),
    )

    calls: list[int] = []
    detected = sleepecg.ecg_heartbeats(recording, detect=_impulse_detector(calls))

    assert detected.size == true_times.size
    assert np.all(np.abs(detected - true_times) <= 1.0 / rate)
    # Each side of the dropout is detected as its own stretch, so no beat is placed by
    # counting samples across the missing ten seconds.
    assert len(calls) == 2
    assert sum(calls) == times.size


def test_a_stretch_without_contact_produces_no_beats(tmp_path: Path) -> None:
    rate = ECG_RATE_HZ
    times = np.arange(0, int(40 * rate)) / rate
    ecg, true_times = _impulse_ecg(times, np.arange(0.5, 39.5, 0.9))
    recording = replace(
        _recording(tmp_path, duration_s=40, contact_until=20),
        ecg=ecg,
    )

    detected = sleepecg.ecg_heartbeats(recording, detect=_impulse_detector([]))

    assert 0 < detected.size < true_times.size
    assert np.all(detected < 20.0)


def test_both_paths_agree_on_a_recording_with_ecg_and_rr(tmp_path: Path) -> None:
    duration_s = 60
    times = np.arange(0, int(duration_s * ECG_RATE_HZ)) / ECG_RATE_HZ
    true_beats = np.arange(0.0, duration_s, 1.0)
    ecg, _ = _impulse_ecg(times, true_beats)

    # One notification per beat, arriving just after the beat it carries, as the strap
    # sends them. The arrival jitter is far larger than a beat's real position error.
    intervals = np.diff(true_beats) * 1000.0
    rng = np.random.default_rng(2)
    arrival_s = true_beats[1:] + 0.02 + rng.uniform(0.0, 0.03, intervals.size)

    recording = _recording(tmp_path, duration_s=duration_s)
    recording = replace(
        recording,
        ecg=ecg,
        hr=_hr_stream(recording, arrival_s, [[value] for value in intervals]),
    )

    ecg_beats = sleepecg.ecg_heartbeats(recording, detect=_impulse_detector([]))
    rr_beats = sleepecg.rr_heartbeats(recording)

    assert ecg_beats.size == true_beats.size
    assert rr_beats.size == true_beats.size
    # The two paths see the same beats within the notification delay, stated as 50 ms.
    assert np.all(np.abs(ecg_beats - rr_beats) <= 0.05)


def test_missing_streams_fail_clearly(tmp_path: Path) -> None:
    recording = _recording(tmp_path, duration_s=120, contact_until=0)
    with pytest.raises(AnalysisError) as context:
        sleepecg._beats(replace(recording, ecg=None), _config(prefer_ecg=False))
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
        sleepecg._beats(replace(empty_hr, hr=None, ecg=None), _config(prefer_ecg=True))
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
        {"weight": 0},
        {"weight": 501},
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


def test_personal_metadata_is_optional() -> None:
    config = _config(age=36, gender="female", weight=72.5)
    assert config["age"] == 36
    assert config["gender"] == "female"
    assert config["weight"] == pytest.approx(72.5)

    empty = _config()
    assert empty["age"] is None
    assert empty["gender"] is None
    assert empty["weight"] is None


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
    assert payload["model_version"] == "1.2.0"
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
