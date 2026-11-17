from __future__ import annotations

import json
from pathlib import Path

import pytest
from taiga_worker.analysis.contract import (
    AASM4_STAGES,
    CONTRACT_VERSION,
    AlgorithmInfo,
    AnalysisError,
    RegisteredAlgorithm,
    Stager,
    StagingResult,
    StreamNeed,
)
from taiga_worker.analysis.pipeline import run_analysis
from taiga_worker.analysis.registry import Registry

from tests import analysis_fixture as fx


def test_full_result_is_json_serializable_and_model_agnostic(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=120)
    output = run_analysis(tmp_path, "baseline", {})
    payload = output.to_dict()
    encoded = json.dumps(payload)

    assert '"baseline"' in encoded
    assert payload["contract_version"] == CONTRACT_VERSION
    assert payload["model"] == "baseline"
    assert payload["model_version"] == "1.2.0"
    assert len(payload["params_hash"]) == 16
    assert payload["stages"]["epoch_seconds"] == 30
    assert payload["stages"]["count"] == 4
    assert sum(item["count"] for item in payload["stages"]["segments"]) == 4
    assert payload["stages"]["confidence"] is None
    assert len(payload["stages"]["artifacts"]) == 4
    assert "items" in payload and payload["items"]
    assert "hr" not in payload
    assert "metrics" not in payload
    assert "quality" not in payload
    assert "capabilities" in payload
    assert "provenance" in payload
    assert payload["capabilities"]["stages"]
    assert payload["provenance"]["algorithm"]["id"] == "baseline"
    assert payload["provenance"]["algorithm"]["version"] == "1.2.0"
    assert payload["provenance"]["recording"]["session_id"] == "20261023_223000"
    assert payload["provenance"]["runtime_s"] >= 0
    assert payload["provenance"]["peak_rss_mb"] > 0


def test_params_hash_changes_with_params(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    first = run_analysis(tmp_path, "baseline", {})
    second = run_analysis(tmp_path, "baseline", {"wake_movement_g": 0.2})
    third = run_analysis(tmp_path, "baseline", {"wake_movement_g": 0.2})

    assert first.params_hash != second.params_hash
    assert second.params_hash == third.params_hash


def test_params_hash_override_and_latest_version(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    output = run_analysis(
        tmp_path,
        "baseline",
        {},
        version="latest",
        params_hash_override="api-hash",
    )

    assert output.params_hash == "api-hash"
    assert output.model_version == "1.2.0"


def test_invalid_params_fail_with_a_clear_code(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "baseline", {"unknown": 1})
    assert context.value.code == "invalid_params"


def test_missing_required_stream_fails_clearly(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    (tmp_path / "hr.log").unlink()
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "baseline", {})
    assert context.value.code == "missing_stream"
    assert "HR" in str(context.value)


def test_short_recording_fails_clearly(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=10)
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "baseline", {})
    assert context.value.code == "recording_too_short"


def test_unknown_algorithm_fails_clearly(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=60)
    with pytest.raises(AnalysisError) as context:
        run_analysis(tmp_path, "no-such-algorithm", {})
    assert context.value.code == "unknown_algorithm"


def test_poor_data_withholds_the_sleep_score(tmp_path: Path) -> None:
    from taiga_worker.analysis.derive import MIN_QUALITY_FOR_SLEEP_SCORE

    fx.write_recording(tmp_path, duration_s=240, contact=False)
    output = run_analysis(tmp_path, "baseline", {})

    quality = next(item for item in output.items if item["id"] == "data_quality")
    assert quality["value"] < MIN_QUALITY_FOR_SLEEP_SCORE
    assert all(item["id"] != "sleep_quality" for item in output.items)


def test_typed_items_carry_their_own_labels_units_and_sources(tmp_path: Path) -> None:
    fx.write_recording(tmp_path, duration_s=240)
    payload = run_analysis(tmp_path, "baseline", {}).to_dict()
    items = payload["items"]

    numbers = [item for item in items if item["kind"] == "number"]
    assert numbers
    for item in numbers:
        assert item["id"] and item["label"] and item["unit"] and item["explanation"]
        assert item["sources"]
        assert isinstance(item["value"], (int, float))
    assert {item["group"] for item in numbers} >= {"sleep", "heart", "quality"}

    scores = {item["id"]: item for item in items if item["kind"] == "score"}
    assert scores["sleep_quality"]["value"] is not None
    assert scores["sleep_quality"]["components"]
    assert all(0 <= item["value"] <= 100 for item in scores.values() if item["value"] is not None)

    series = [item for item in items if item["kind"] == "series"]
    assert len(series) == 1
    assert series[0]["id"] == "hr"
    assert series[0]["series"]["count"] == payload["stages"]["count"]


def test_items_omit_metrics_the_algorithm_does_not_support() -> None:
    from taiga_worker.analysis.items import number_items

    items = number_items(
        {"hr_mean_bpm": 58.0, "time_in_deep_s": None},
        {
            "hr_mean_bpm": {"status": "supported"},
            "time_in_deep_s": {"status": "unsupported_algorithm"},
        },
    )

    assert [item["id"] for item in items] == ["hr_mean_bpm"]


PROBE_INFO = AlgorithmInfo(
    id="confidence-probe",
    version="1.0.0",
    name="Confidence probe",
    description="Synthetic stager that reports a fixed per-epoch confidence.",
    license="MIT",
    needs=(StreamNeed("hr", required=True),),
    stage_scheme="aasm4",
    supported_stages=AASM4_STAGES,
    capabilities=frozenset({"stages", "confidence"}),
)


class _ProbeStager(Stager):
    info = PROBE_INFO
    confidence = 0.5

    def stage(self, recording, params):
        grid = recording.epochs(30)
        return StagingResult(
            stages=["light"] * grid.count,
            confidence=[self.confidence] * grid.count,
        )


def _data_quality_with_confidence(tmp_path: Path, confidence: float) -> dict:
    fx.write_recording(tmp_path, duration_s=240)
    stager = _ProbeStager()
    stager.confidence = confidence
    registry = Registry(
        algorithms={
            (PROBE_INFO.id, PROBE_INFO.version): RegisteredAlgorithm(
                info=PROBE_INFO,
                stager=stager,
                source="test",
            )
        }
    )
    payload = run_analysis(tmp_path, PROBE_INFO.id, {}, registry=registry).to_dict()
    return next(item for item in payload["items"] if item["id"] == "data_quality")


def test_the_data_quality_score_does_not_depend_on_stage_confidence(tmp_path: Path) -> None:
    unsure = _data_quality_with_confidence(tmp_path / "unsure", 0.01)
    certain = _data_quality_with_confidence(tmp_path / "certain", 1.0)

    assert unsure["value"] == certain["value"]
    assert unsure["components"] == certain["components"]
    assert all(component["id"] != "mean_stage_confidence" for component in unsure["components"])


def test_analysis_is_identical_for_the_same_samples_in_either_ecg_codec(
    tmp_path: Path,
) -> None:
    old = fx.write_recording(tmp_path / "old", duration_s=240, ecg_values_i16=False)
    new = fx.write_recording(tmp_path / "new", duration_s=240, ecg_values_i16=True)

    def result(directory: Path) -> dict:
        payload = run_analysis(directory, "baseline", {}).to_dict()
        # The run itself differs only in how long it took and how much memory it used;
        # valid_bytes is the stored byte count, which is the one thing the codec changes.
        payload["provenance"].pop("runtime_s")
        payload["provenance"].pop("peak_rss_mb")
        payload["provenance"]["input_quality"]["streams"]["ecg"].pop("valid_bytes")
        return payload

    assert result(old) == result(new)


class _StreamProbe(Stager):
    """A stager that records which streams it was given."""

    info = PROBE_INFO

    def __init__(self, seen: list, needs: tuple[StreamNeed, ...]) -> None:
        self.seen = seen
        self.info = AlgorithmInfo(
            id=PROBE_INFO.id,
            version=PROBE_INFO.version,
            name=PROBE_INFO.name,
            description=PROBE_INFO.description,
            license=PROBE_INFO.license,
            needs=needs,
            stage_scheme="aasm4",
            supported_stages=AASM4_STAGES,
        )

    def stage(self, recording, params):
        self.seen.append((recording.hr, recording.ecg, recording.acc))
        grid = recording.epochs(30)
        return StagingResult(stages=["light"] * grid.count)


def _run_stream_probe(tmp_path: Path, needs: tuple[StreamNeed, ...]) -> tuple[list, dict]:
    fx.write_recording(tmp_path, duration_s=60)
    seen: list = []
    stager = _StreamProbe(seen, needs)
    registry = Registry(
        algorithms={
            (PROBE_INFO.id, PROBE_INFO.version): RegisteredAlgorithm(
                info=stager.info,
                stager=stager,
                source="test",
            )
        }
    )
    payload = run_analysis(tmp_path, PROBE_INFO.id, {}, registry=registry).to_dict()
    return seen, payload


def test_a_stream_the_stager_and_the_derivations_do_not_need_is_not_decoded(
    tmp_path: Path,
) -> None:
    seen, payload = _run_stream_probe(tmp_path, (StreamNeed("hr", required=True),))
    (hr, ecg, acc) = seen[0]

    assert hr is not None
    assert acc is not None  # the movement figures and the HRV rule read it
    assert ecg is None
    # The stream it did not decode is still reported, so the result says the same
    # thing about the input either way.
    quality = payload["provenance"]["input_quality"]["streams"]
    assert quality["ecg"]["present"]
    assert quality["ecg"]["samples"] == 60 * 130
    assert quality["ecg"]["rate_hz"] == pytest.approx(130.0, abs=1.0)


def test_a_stream_the_stager_needs_is_decoded(tmp_path: Path) -> None:
    seen, _ = _run_stream_probe(
        tmp_path,
        (StreamNeed("hr", required=True), StreamNeed("ecg", required=True)),
    )
    (_hr, ecg, _acc) = seen[0]

    assert ecg is not None
    assert ecg.t_s.size == 60 * 130
