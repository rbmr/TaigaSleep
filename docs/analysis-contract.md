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
                                                   -> shared derive -> figures,
                                                                      scores
                                                   -> serialization -> stages,
                                                                      typed items
```

- **Decode/normalize** is shared and not pluggable. It handles CRC, µV→mV,
  mG→g, Polar/phone/strap clocks, contact, ECG saturation, gaps and coverage.
- **Stager** is the plugin. It is a pure function over `Recording`.
- **Derive** is shared. It computes the figures, the data-quality score, the
  sleep score and their explicit support. A stager cannot override them.
- **Orchestration** is shared. It loads the recording, validates required
  streams, calls the stager, validates the result, serializes the epoch-aligned
  result blocks and stores them.

## Typed items

Contract version 3 has exactly one place for figures: `items`. An item describes itself, so a
client renders it without knowing the algorithm or a local catalog:

```json
{
  "items": [
    {"kind": "number", "id": "hr_mean_bpm", "label": "Average heart rate", "group": "heart",
     "unit": "bpm", "value": 58.2, "explanation": "Mean of all valid heart-rate samples.",
     "sources": ["hr"]},
    {"kind": "series", "id": "hr", "label": "Heart rate", "group": "heart", "unit": "bpm",
     "explanation": "Mean heart rate for each epoch.",
     "sources": ["hr"], "series": {"epoch_seconds": 30, "count": 2, "mean_bpm": [61.0, null]}},
    {"kind": "score", "id": "sleep_quality", "label": "Sleep score", "group": "score",
     "unit": "%", "value": 82.4,
     "explanation": "Weighted average of the sleep figures.",
     "sources": ["stages"],
     "components": [{"id": "sleep_efficiency", "label": "Sleep efficiency",
                     "weight": 30.0, "value": 91.0}]}
  ]
}
```

- `kind` is `number`, `series` or `score`. A number has `value`; a series has `series`;
  a score has `value` in 0..100 and weighted `components`, also in 0..100.
- Every item carries `label`, `unit`, `explanation` and `sources`, so the app hides what is
  absent instead of printing a missing-data shell.
- `group` keeps the presentation sections: `sleep`, `heart`, `movement`, `quality` or `score`.
- The stage timeline stays the separate top-level `stages` block; it is not an item.
- Items are only emitted for values the run actually supports. There are no parallel result
  keys: a client that wants a figure reads its item, and a figure the run cannot produce is
  absent instead of present with a null value.
- Contract version 3 requires at least one item, unique ids, a source on every item, and
  score values between 0 and 100. The API rejects a result that breaks those rules. The
  stage timeline stays the separate top-level `stages` block.
- The data-quality score and the sleep score are `score` items. Their components carry the
  same id, label, weight and value the app shows in the breakdown.

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
| `stage_scheme` | `aasm4`, `wake-rem-nrem`, `wake-sleep`, or a documented custom scheme such as `sleep-ecg`. |
| `supported_stages` | Stages the algorithm can actually emit. |
| `capabilities` | At least `stages`; optionally `confidence`, `probabilities`, `artifacts`, `beats`, `no_deep`. |
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
    supported_stages=("wake", "rem", "nrem", "unknown"),  # optional narrow set
)
```

- `stages` is required and must use only `info.supported_stages`.
- `supported_stages` is optional. A stager whose manifest supports several
  modes (for example SleepECG's three-class and two-class classifiers) can
  narrow the stages for this run. The set must be a subset of the manifest and
  decides which stage figures are emitted; for example a wake/rem/nrem run has
  no sleep-stage item instead of reporting zero.
- `confidence` and `artifacts` are optional; the shared quality score accounts
  for missing confidence by renormalizing its weight. `unknown` means the epoch
  could not be scored; an epoch may still carry a stage while being flagged as
  an artifact (for example a short electrode pop), so artifacts are not
  necessarily unknown.
- `probabilities` is optional. If confidence is absent, it is derived from the
  maximum probability.
- `beats_s` is optional and lets an ECG algorithm improve HRV without changing
  the shared figures. If absent, HRV uses the H10 RR intervals.
- `provenance` is optional algorithm-specific provenance (checkpoint path,
  model revision, hashes). The worker merges it into the result under
  `provenance.algorithm`; it cannot replace the standard licence, citation or
  contract fields.
- `extras` is algorithm-specific, must be JSON-serializable and is never used by
  the standard app metrics.
- A stager must not read files, call the API, write results or depend on the
  host environment.

## Figures and scores are shared

The shared layer derives, and then emits as items:

- total duration, scored/unscored duration, total sleep, efficiency, sleep
  latency, WASO, awakenings (wake periods longer than two minutes), final
  awakening, time per stage;
- HR mean/min/max, HRV RMSSD/SDNN, movement index/mean/epochs;
- an epoch-aligned heart-rate series, as a `series` item;
- explicit support per figure: `supported`, `unsupported_device`,
  `insufficient_data`, or `unsupported_algorithm`, with a reason. Only
  supported figures become items.

### Data-quality score

The data-quality score is a `score` item with id `data_quality`. It uses the
tunable formula from issue #10:

```text
100 * (0.35 * usable epoch coverage
     + 0.25 * (1 - artifact epoch rate)
     + 0.25 * mean stage confidence
     + 0.15 * HR coverage)
```

Unavailable components are removed and the remaining weights are renormalized.
The item carries the surviving components, so the app can explain the score.

### Sleep score

The sleep score is a `score` item with id `sleep_quality`. It is a transparent,
versioned product policy over the sleep figures:

```text
100 * (0.30 * sleep efficiency
     + 0.20 * (1 - WASO / 2 hours)
     + 0.20 * (1 - awakenings per hour of sleep / 6)
     + 0.15 * (1 - sleep latency / 60 minutes)
     + 0.15 * min(1, (deep + REM share of total sleep) / 0.5))
```

Every term is clamped to 0..1, so a lower WASO, awakening rate or latency scores
higher. A component is unavailable when the data or algorithm cannot produce it,
for example when an algorithm cannot split deep and REM. Unavailable components
are removed and the remaining weights are renormalized. The score deliberately
has no target sleep duration: duration is reported as its own item.

```json
{
  "kind": "score",
  "id": "sleep_quality",
  "label": "Sleep score",
  "group": "score",
  "unit": "%",
  "value": 82.4,
  "explanation": "Weighted average of the sleep figures.",
  "sources": ["stages"],
  "components": [
    {"id": "sleep_efficiency", "label": "Sleep efficiency", "weight": 30.0, "value": 91.0},
    {"id": "waso", "label": "Wake after sleep onset", "weight": 20.0, "value": 88.0}
  ]
}
```

### Heart-rate series

The worker serializes the per-epoch heart rate it already computes in
`EpochGrid` as a `series` item with id `hr`. It uses the same epoch grid as
`stages`, so a night stays compact (about 960 numbers for 8 hours instead of
hundreds of thousands of raw samples):

```json
{
  "kind": "series",
  "id": "hr",
  "label": "Heart rate",
  "group": "heart",
  "unit": "bpm",
  "explanation": "Mean heart rate for each epoch. Gaps are epochs without a valid sample.",
  "sources": ["hr"],
  "series": {
    "epoch_seconds": 30,
    "start_unix_ms": 1790000000000,
    "count": 960,
    "mean_bpm": [61.2, 60.8, null, 59.9],
    "sample_count": [29, 30, 0, 28]
  }
}
```

`mean_bpm` is `null` for an epoch without valid samples, and `sample_count` is
the number of valid samples behind each mean. Gaps stay gaps: clients must not
interpolate across a null.

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
offer them and the claim filters by algorithm. Two algorithms may share an id
only if their versions differ; the latest version is the default, and an
analysis can request an exact version.

Third-party code runs in the worker's analysis subprocess with the existing
memory/time limits. It is trusted operator-installed code, not a sandbox.
`TAIGA_ALGORITHMS` can allowlist ids.

Built-ins may be optional. A built-in module can export
`availability() -> (bool, reason)`; when it returns false the algorithm is not
advertised. A missing optional dependency stays silent, while a
configured-but-broken extra is reported in the registry errors. The optional
wav2sleep and SleepECG stagers use this so a base worker reports only the
baseline.

The result's `capabilities` records what the run actually produced, including
`probabilities`, `confidence`, `artifacts`, `beats` and whether deep sleep is
unsupported (`no_deep`). Per-epoch probabilities are stored by stage name in
`stages.probabilities`.

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
