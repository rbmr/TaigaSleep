# Sleep-staging algorithm contract

Taiga Sleep treats sleep staging as the pluggable part of analysis. The worker
owns recording decoding, timestamps, metrics, quality and result storage; a
stager only turns a normalized recording into a canonical stage timeline.

This document is the contract for built-in and third-party algorithms. It is
versioned by `CONTRACT_VERSION` in
`backend/worker/taiga_worker/analysis/contract.py`.

## Layers

```text
recording bytes -> decode/normalize -> Recording -> [stager] -> StagingResult
                                                   -> shared derive -> metrics,
                                                                      quality,
                                                                      support map
```

- **Decode/normalize** is shared and not pluggable. It handles CRC, µV→mV,
  mG→g, Polar/phone/strap clocks, contact, ECG saturation, gaps and coverage.
- **Stager** is the plugin. It is a pure function over `Recording`.
- **Derive** is shared. It computes metrics, quality and explicit unsupported
  states. A stager cannot override the standard metrics.
- **Orchestration** is shared. It loads the recording, validates required
  streams, calls the stager, validates the result and stores it.

## Canonical stages and epochs

```python
Stage = Literal["wake", "rem", "nrem", "light", "deep", "sleep", "unknown"]
```

- `light` is AASM N1+N2, `deep` is N3.
- `nrem` exists for algorithms that can distinguish REM but not light/deep.
- `sleep` exists for wake/sleep classifiers that cannot split sleep at all.
- Never silently map `nrem` or `sleep` to `light` or `deep`.
- `unknown` means the epoch could not be scored. It is not wake.
- Epochs are 30 seconds by default and aligned to the recording start. A stager
  may use any internal window size, but must return exactly one canonical stage
  per epoch.
- A stager declares its `stage_scheme` and `supported_stages`. A
  WAKE/REM/NREM algorithm declares `nrem` and leaves `time_in_light_s` and
  `time_in_deep_s` as `null` with `unsupported_algorithm` support. A wake/sleep
  algorithm declares `sleep` and leaves every more specific stage `null`.

## What a stager declares

`AlgorithmInfo` is the manifest:

| Field | Meaning |
| --- | --- |
| `id` | Stable lowercase id, e.g. `wav2sleep`. |
| `version` | Bump whenever behaviour or output changes. |
| `name`, `description` | Human-readable labels. |
| `license`, `citation`, `homepage` | Provenance shown in results. |
| `needs` | Required/optional streams and rate ranges. |
| `epoch_seconds` | Output epoch length; 30 is the norm. |
| `stage_scheme` | `aasm4`, `wake-rem-nrem`, `wake-sleep`, or a documented custom scheme. |
| `supported_stages` | Stages the algorithm can actually emit. |
| `capabilities` | At least `stages`; optionally `confidence`, `probabilities`, `artifacts`, `beats`. |
| `default_params`, `params_schema` | Tunable parameters and their shape. |

## Input: `Recording`

The shared decoder provides:

```python
recording.meta             # RecordingMeta (device, rates, session id, state)
recording.start_unix_ns    # absolute recording start
recording.end_unix_ns
recording.duration_s
recording.hr               # HrStream | None
recording.ecg              # EcgStream | None
recording.acc              # AccStream | None
recording.events           # start/stop/contact/clock_sync/... events
recording.quality          # coverage, contact, saturation, gaps,
                           # clock_syncs (normal strap clock sets) and
                           # clock_jumps (actual phone/strap discontinuities)
```

Stream units and shapes:

| Stream | Values | Shape |
| --- | --- | --- |
| HR | `hr_bpm` (float32), `contact`, `valid`, `rr_ms` per sample | 1D, ~1 Hz |
| ECG | `voltage_mv` (float32), `saturated` | 1D, native rate (usually 130 Hz) |
| ACC | `xyz_g` (float32) | `(n, 3)`, native rate (usually 50 Hz) |

Time is `t_s`, seconds relative to `start_unix_ns`. Absolute time is
`start_unix_ns + t_s * 1e9`. Strap timestamps are used only for sample spacing
inside a batch; phone `elapsed_ns` anchors batches, so a wrong or reset strap
clock does not move the recording.

Helpers:

```python
grid = recording.epochs(30)              # coverage, HR mean/std, movement, ...
ecg = recording.resample("ecg", 34.13)   # uniform t_s, values, valid mask
```

`resample` respects gaps and marks invalid samples; algorithms should not treat
interpolated gap samples as real data.

## Output: `StagingResult`

```python
StagingResult(
    stages=["light", "deep", ...],        # exactly grid.count entries
    confidence=[0.82, None, ...],         # optional, 0..1 per epoch
    probabilities={"light": [...], ...},   # optional per-stage probabilities
    artifacts=[False, True, ...],          # optional per-epoch artifact flag
    beats_s=[0.81, 1.62, ...],             # optional beat times in seconds
    warnings=["..."],
    extras={"anything": "namespaced"},
)
```

- `stages` is required and must use only `info.supported_stages`.
- `confidence` and `artifacts` are optional; the shared quality score accounts
  for missing confidence by renormalizing its weight. `unknown` means the epoch
  could not be scored; an epoch may still carry a stage while being flagged as
  an artifact (for example a short electrode pop), so artifacts are not
  necessarily unknown.
- `probabilities` is optional. If confidence is absent, it is derived from the
  maximum probability.
- `beats_s` is optional and lets an ECG algorithm improve HRV without changing
  the metrics layer. If absent, HRV uses the H10 RR intervals.
- `provenance` is optional algorithm-specific provenance (checkpoint path,
  model revision, hashes). The worker merges it into the result under
  `provenance.algorithm`; it cannot replace the standard licence, citation or
  contract fields.
- `extras` is algorithm-specific, must be JSON-serializable and is never used by
  the standard app metrics.
- A stager must not read files, call the API, write results or depend on the
  host environment.

## Metrics and quality are shared

The shared layer derives:

- total duration, scored/unscored duration, total sleep, efficiency, sleep
  latency, WASO, awakenings, final awakening, time per stage;
- HR mean/min/max, HRV RMSSD/SDNN, movement index/mean/epochs;
- explicit support per metric: `supported`, `unsupported_device`,
  `insufficient_data`, or `unsupported_algorithm`, with a reason.

Quality uses the tunable formula from issue #10:

```text
100 * (0.35 * usable epoch coverage
     + 0.25 * (1 - artifact epoch rate)
     + 0.25 * mean stage confidence
     + 0.15 * HR coverage)
```

Unavailable components are removed and the remaining weights are renormalized.
The breakdown is returned so the app can explain the score.

## Discovery and versioning

Built-ins live in `taiga_worker/analysis/algorithms/`. Third-party stagers
register an entry point:

```toml
[project.entry-points."taiga_sleep.stagers"]
my_algo = "my_package.module:STAGER"
```

`STAGER` may be an instance, a class, or a module exporting `STAGER`. It must be
a subclass of `Stager`; the ABC enforces the class-level `info: AlgorithmInfo`
and the abstract `stage()` method, and rejects duck-typed objects at
registration. The worker reports installed algorithms to the API; the app can
offer them and the job claim filters by model. Two algorithms may share an id
only if their versions differ; the latest version is the default, and an
analysis can request an exact version.

Third-party code runs in the worker's analysis subprocess with the existing
memory/time limits. It is trusted operator-installed code, not a sandbox.
`TAIGA_ALGORITHMS` can allowlist ids.

Built-ins may be optional. A built-in module can export
`availability() -> (bool, reason)`; when it returns false the algorithm is not
advertised. A missing optional dependency stays silent, while a
configured-but-broken extra is reported in the registry errors. The optional
wav2sleep stager uses this so a base worker reports only the baseline.

The result's `capabilities` records what the run actually produced, including
`probabilities`, `confidence`, `artifacts` and `beats`. Per-epoch probabilities
are stored by stage name in `stages.probabilities`.

## Minimal example

```python
from taiga_worker.analysis.contract import (
    AASM4_STAGES, AlgorithmInfo, Recording, Stager, StagingResult, StreamNeed,
)

class MyStager(Stager):
    info = AlgorithmInfo(
        id="my-algo",
        version="0.1.0",
        name="My ECG stager",
        description="Example third-party stager.",
        license="MIT",
        citation="...",
        needs=(StreamNeed("ecg", required=True, min_rate_hz=100),),
        stage_scheme="aasm4",
        supported_stages=AASM4_STAGES,
        capabilities=frozenset({"stages", "confidence"}),
    )

    def stage(self, recording: Recording, params):
        grid = recording.epochs(30)
        ecg = recording.resample("ecg", 100.0)
        stages = ["light"] * grid.count      # replace with real inference
        return StagingResult(stages=stages, confidence=[0.5] * grid.count)

STAGER = MyStager()
```

## Testing a stager

- Use the synthetic recording builder in
  `backend/worker/tests/analysis_fixture.py`; never commit real recordings.
- Test determinism, every supported stage, missing/empty streams, short
  recordings and artifact/confidence lengths.
- Run the full worker suite:

```bash
cd backend/worker
uv sync --frozen
uv run --frozen pytest
```

The integration suite for the upload API and result storage lives in
`backend/tests/test_integration.py`.
