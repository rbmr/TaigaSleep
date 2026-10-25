from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import pytest

from taiga_worker.analysis.algorithms import wav2sleep
from taiga_worker.analysis.contract import (
    AnalysisError,
    RegisteredAlgorithm,
)
from taiga_worker.analysis.decode import load_recording
from taiga_worker.analysis.pipeline import run_analysis
from taiga_worker.analysis.registry import Registry
from tests import analysis_fixture as fx


def _prepare(tmp_path: Path, **params):
    fx.write_recording(tmp_path, duration_s=120)
    recording = load_recording(tmp_path)
    config = wav2sleep._params({"min_usable_epochs": 1, "pad_to_max_length": False, **params})
    return recording, config


def test_ecg_adapter_uses_1024_samples_per_epoch(tmp_path: Path) -> None:
    recording, config = _prepare(tmp_path)
    prepared = wav2sleep.prepare_ecg(recording, config)

    assert prepared.canonical_epochs == 4
    assert prepared.scored_epochs == 4
    assert prepared.input_epochs == 4
    assert prepared.signal.shape == (4 * wav2sleep.SAMPLES_PER_EPOCH,)
    assert prepared.signal.dtype == np.float32
    assert np.all(np.isfinite(prepared.signal))
    # Unit z-score over the model input.
    assert abs(float(np.mean(prepared.signal))) < 1e-4
    assert abs(float(np.std(prepared.signal)) - 1.0) < 1e-3
    assert prepared.usable_epochs == 4


def test_partial_epoch_is_padded_to_1024_samples(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=100)
    recording = load_recording(tmp_path)
    config = wav2sleep._params(
        {"min_usable_epochs": 1, "pad_to_max_length": False}
    )

    prepared = wav2sleep.prepare_ecg(recording, config)
    assert prepared.canonical_epochs == 4
    assert prepared.signal.shape == (4 * wav2sleep.SAMPLES_PER_EPOCH,)


def test_model_input_can_be_padded_to_the_configured_limit(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    recording = load_recording(tmp_path)
    config = wav2sleep._params(
        {
            "min_usable_epochs": 1,
            "pad_to_max_length": True,
            "max_length_hours": 1,
        }
    )

    prepared = wav2sleep.prepare_ecg(recording, config)
    assert prepared.scored_epochs == 4
    assert prepared.input_epochs == 120
    assert prepared.padded_epochs == 116
    assert prepared.signal.shape == (120 * wav2sleep.SAMPLES_PER_EPOCH,)


def test_class_mapping_confidence_and_artifacts() -> None:
    prepared = wav2sleep.PreparedEcg(
        signal=np.zeros(4 * wav2sleep.SAMPLES_PER_EPOCH, dtype=np.float32),
        canonical_epochs=4,
        scored_epochs=4,
        input_epochs=4,
        usable=np.asarray([True, False, True, True]),
        coverage=np.asarray([1.0, 0.1, 0.9, 0.8]),
        saturated=np.zeros(4, dtype=np.float64),
    )
    probabilities = np.asarray(
        [
            [0.10, 0.70, 0.10, 0.10],
            [0.40, 0.30, 0.20, 0.10],
            [0.05, 0.05, 0.85, 0.05],
            [0.10, 0.10, 0.10, 0.70],
        ],
        dtype=np.float64,
    )

    result = wav2sleep.to_result(
        prepared,
        probabilities,
        extras={"checkpoint_path": "/models/wav2sleep", "max_length_hours": 10},
    )

    assert result.stages == ["light", "unknown", "deep", "rem"]
    assert result.artifacts == [False, True, False, False]
    assert result.confidence is not None
    assert result.confidence[0] == pytest.approx(0.7)
    assert result.confidence[1] is None
    assert result.confidence[3] == pytest.approx(0.70)
    assert result.probabilities is not None
    assert result.probabilities["light"] == pytest.approx([0.7, 0.3, 0.05, 0.10])
    assert result.provenance["checkpoint_revision"] == wav2sleep.CHECKPOINT_REVISION
    assert result.provenance["checkpoint"] == "/models/wav2sleep"


def test_epochs_beyond_the_model_limit_are_unknown() -> None:
    prepared = wav2sleep.PreparedEcg(
        signal=np.zeros(2 * wav2sleep.SAMPLES_PER_EPOCH, dtype=np.float32),
        canonical_epochs=4,
        scored_epochs=2,
        input_epochs=2,
        usable=np.asarray([True, True]),
        coverage=np.asarray([1.0, 1.0]),
        saturated=np.zeros(2, dtype=np.float64),
    )
    probabilities = np.asarray(
        [[0.3, 0.4, 0.2, 0.1], [0.3, 0.4, 0.2, 0.1]], dtype=np.float64
    )

    result = wav2sleep.to_result(
        prepared,
        probabilities,
        extras={"checkpoint_path": "/models/wav2sleep", "max_length_hours": 1},
    )
    assert result.stages == ["light", "light", "unknown", "unknown"]
    assert result.artifacts == [False, False, True, True]
    assert any("1 h limit" in warning for warning in result.warnings)
    assert result.probabilities is not None
    assert result.probabilities["wake"] == pytest.approx([0.3, 0.3, 0.0, 0.0])


def test_missing_ecg_is_a_clear_failure(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120, include_ecg=False)
    recording = load_recording(tmp_path)
    config = wav2sleep._params({"min_usable_epochs": 1})

    with pytest.raises(AnalysisError) as context:
        wav2sleep.prepare_ecg(recording, config)
    assert context.value.code == "missing_stream"


def test_short_ecg_is_a_clear_failure(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    recording = load_recording(tmp_path)
    config = wav2sleep._params({"min_usable_epochs": 60})

    with pytest.raises(AnalysisError) as context:
        wav2sleep.prepare_ecg(recording, config)
    assert context.value.code == "insufficient_ecg"
    assert "30 min" in str(context.value)


def test_flat_ecg_is_a_clear_failure(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    n = 120 * 130
    payload = fx.ecg_payload(
        fx.START_NS + 120 * 1_000_000_000,
        120 * 1_000_000_000,
        fx.POLAR_START_NS,
        fx.POLAR_START_NS + (n - 1) * (1_000_000_000 // 130),
        [0] * n,
    )
    (tmp_path / "ecg.log").write_bytes(fx.frame_header(fx.STREAM_ECG) + fx.frame(payload))
    recording = load_recording(tmp_path)
    config = wav2sleep._params({"min_usable_epochs": 1, "pad_to_max_length": False})

    with pytest.raises(AnalysisError) as context:
        wav2sleep.prepare_ecg(recording, config)
    assert context.value.code == "bad_ecg"


def test_contact_loss_and_high_noise_become_unknown(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=240, include_acc=False)
    data = bytearray(fx.frame_header(fx.STREAM_HR))
    for second in range(240):
        flags = 3 if second < 120 else 1
        data += fx.frame(
            fx.hr_payload(
                fx.START_NS + second * 1_000_000_000,
                second * 1_000_000_000,
                60,
                flags=flags,
            )
        )
    (tmp_path / "hr.log").write_bytes(bytes(data))
    recording = load_recording(tmp_path)
    config = wav2sleep._params(
        {
            "min_usable_epochs": 1,
            "pad_to_max_length": False,
            "min_unknown_epochs": 2,
            "smooth_window_epochs": 1,
        }
    )

    prepared = wav2sleep.prepare_ecg(recording, config)
    assert prepared.usable.tolist() == [True, True, True, True, False, False, False, False]
    assert prepared.artifacts is not None
    assert prepared.artifacts.tolist() == [False, False, False, False, True, True, True, True]
    probabilities = np.full((8, 4), 0.25, dtype=np.float64)
    probabilities[:, 1] = 0.8
    probabilities[:, 0] = 0.2
    result = wav2sleep.to_result(
        prepared,
        probabilities,
        extras={"checkpoint_path": "/models/wav2sleep", "max_length_hours": 10},
    )
    assert result.stages == ["light"] * 4 + ["unknown"] * 4
    assert result.artifacts == [False] * 4 + [True] * 4
    assert result.confidence is not None
    assert all(value is None for value in result.confidence[4:])


def test_high_amplitude_noise_is_an_artifact(tmp_path: Path) -> None:
    duration = 480
    fx.write_recording(tmp_path, duration_s=duration, include_acc=False)
    n = duration * 130
    values = [int(1000 * ((i % 20) - 10)) for i in range(n)]
    for index in range(n - 120 * 130, n):
        values[index] = 100_000 if index % 2 else -100_000
    payload = fx.ecg_payload(
        fx.START_NS + duration * 1_000_000_000,
        duration * 1_000_000_000,
        fx.POLAR_START_NS,
        fx.POLAR_START_NS + (n - 1) * (1_000_000_000 // 130),
        values,
    )
    (tmp_path / "ecg.log").write_bytes(fx.frame_header(fx.STREAM_ECG) + fx.frame(payload))
    recording = load_recording(tmp_path)
    config = wav2sleep._params(
        {
            "min_usable_epochs": 1,
            "pad_to_max_length": False,
            "min_unknown_epochs": 2,
            "smooth_window_epochs": 1,
            "max_ecg_rms_ratio": 4.0,
        }
    )

    prepared = wav2sleep.prepare_ecg(recording, config)
    assert prepared.usable[:12].all()
    assert not prepared.usable[12:].any()
    assert prepared.unknown_epochs == 4


def test_probability_smoothing_removes_a_single_epoch_outlier() -> None:
    epochs = 7
    prepared = wav2sleep.PreparedEcg(
        signal=np.zeros(epochs * wav2sleep.SAMPLES_PER_EPOCH, dtype=np.float32),
        canonical_epochs=epochs,
        scored_epochs=epochs,
        input_epochs=epochs,
        usable=np.ones(epochs, dtype=bool),
        coverage=np.ones(epochs),
        saturated=np.zeros(epochs),
    )
    probabilities = np.tile([0.2, 0.8, 0.0, 0.0], (epochs, 1))
    probabilities[3] = [0.8, 0.2, 0.0, 0.0]

    raw = wav2sleep.to_result(
        prepared,
        probabilities,
        extras={"checkpoint_path": "/models/wav2sleep", "max_length_hours": 10},
    )
    smoothed = wav2sleep.to_result(
        prepared,
        probabilities,
        extras={"checkpoint_path": "/models/wav2sleep", "max_length_hours": 10},
        smooth_window_epochs=5,
    )

    assert raw.stages[3] == "wake"
    assert smoothed.stages == ["light"] * epochs


def test_invalid_params_are_rejected() -> None:
    with pytest.raises(AnalysisError) as context:
        wav2sleep._params({"not_a_parameter": 1})
    assert context.value.code == "invalid_params"

    for params in (
        {"max_length_hours": 0},
        {"max_length_hours": 25},
        {"min_ecg_coverage": 1.5},
        {"min_usable_epochs": 0},
        {"min_hr_contact_fraction": 1.5},
        {"max_ecg_rms_ratio": -1},
        {"min_ecg_rms_ratio": -1},
        {"min_unknown_epochs": 0},
        {"smooth_window_epochs": 0},
        {"device": "cuda"},
    ):
        with pytest.raises(AnalysisError) as context:
            wav2sleep._params(params)
        assert context.value.code == "invalid_params"


def test_remote_checkpoints_are_rejected() -> None:
    assert wav2sleep._remote_checkpoint("hf://joncarter/wav2sleep")
    assert wav2sleep._remote_checkpoint("https://example.com/model")
    assert not wav2sleep._remote_checkpoint("/srv/taiga/models/wav2sleep")


def test_checkpoint_is_pinned_and_verified(tmp_path: Path) -> None:
    checkpoint = tmp_path / "checkpoint"
    checkpoint.mkdir()
    (checkpoint / "config.yaml").write_text("num_classes: 4\n", encoding="utf-8")
    (checkpoint / "state_dict.pth").write_bytes(b"not the pinned checkpoint")

    with pytest.raises(AnalysisError) as context:
        wav2sleep._load_model(checkpoint, "cpu")
    assert context.value.code == "invalid_checkpoint"
    assert wav2sleep.CHECKPOINT_SHA256 in str(context.value)


def test_pipeline_merges_algorithm_provenance(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The pipeline contract is exercised without importing torch."""

    fx.write_recording(tmp_path, duration_s=120)
    registry = Registry(
        algorithms={
            (wav2sleep.INFO.id, wav2sleep.INFO.version): RegisteredAlgorithm(
                info=wav2sleep.INFO,
                stager=wav2sleep.STAGER,
                source="builtin",
            )
        }
    )

    def fake_load_model(checkpoint: Path, device: str):
        return object()

    def fake_classify(model, signal):
        epochs = len(signal) // wav2sleep.SAMPLES_PER_EPOCH
        probabilities = np.zeros((epochs, 4), dtype=np.float64)
        probabilities[:, 1] = 0.8
        probabilities[:, 0] = 0.2
        return probabilities

    monkeypatch.setattr(wav2sleep, "_load_model", fake_load_model)
    monkeypatch.setattr(wav2sleep, "classify", fake_classify)
    monkeypatch.setenv(wav2sleep.CHECKPOINT_ENV, "/models/wav2sleep")

    output = run_analysis(
        tmp_path,
        "wav2sleep",
        {"min_usable_epochs": 1, "pad_to_max_length": False},
        registry=registry,
    )
    payload = output.to_dict()

    assert payload["model"] == "wav2sleep"
    assert payload["model_version"] == "1.1.0"
    assert payload["capabilities"]["probabilities"] is True
    assert payload["provenance"]["algorithm"]["checkpoint_revision"] == wav2sleep.CHECKPOINT_REVISION
    assert payload["provenance"]["algorithm"]["checkpoint_sha256"] == wav2sleep.CHECKPOINT_SHA256
    assert payload["provenance"]["license"] == "MIT"
    assert payload["provenance"]["citation"]
    assert payload["stages"]["count"] == 4
    assert payload["stages"]["probabilities"]["light"] == [0.8, 0.8, 0.8, 0.8]
    assert payload["extras"]["scored_epochs"] == 4


def _checkpoint_or_skip() -> Path:
    pytest.importorskip("torch")
    pytest.importorskip("wav2sleep")
    configured = os.environ.get(wav2sleep.CHECKPOINT_ENV, "").strip()
    if not configured:
        pytest.skip(f"{wav2sleep.CHECKPOINT_ENV} is not set")
    checkpoint = Path(configured).expanduser()
    if not (checkpoint / "state_dict.pth").is_file():
        pytest.skip(f"no checkpoint at {checkpoint}")
    return checkpoint


def test_pinned_checkpoint_smoke(tmp_path: Path) -> None:
    """Run the real checkpoint once when it is installed locally.

    CI installs the base worker without torch, so this test skips there.
    """

    _checkpoint_or_skip()
    fx.write_recording(tmp_path, duration_s=120)

    output = run_analysis(
        tmp_path,
        "wav2sleep",
        {"min_usable_epochs": 1, "pad_to_max_length": False},
    )
    payload = output.to_dict()

    assert payload["model"] == "wav2sleep"
    assert payload["stages"]["count"] == 4
    assert payload["capabilities"]["probabilities"] is True
    assert payload["provenance"]["algorithm"]["checkpoint_revision"] == wav2sleep.CHECKPOINT_REVISION
    assert payload["provenance"]["algorithm"]["classes"] == list(wav2sleep.CLASSES)
    assert payload["provenance"]["runtime_s"] >= 0
    assert payload["provenance"]["peak_rss_mb"] > 0
