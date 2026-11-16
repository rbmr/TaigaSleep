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
- Contract version 4 changed what several figures mean without changing their ids or shapes:
  sleep efficiency is a share of scored time and is absent when less than half of the recording
  could be scored, sleep latency starts at the first scored epoch, HRV uses sleep epochs only and
  never takes a difference across a gap in the RR data, and movement is measured from the night's
  median accelerometer magnitude. A result stored under an older contract version keeps the older
  definitions until it is analysed again.
- Contract version 5 removed stage confidence from the data-quality score and withholds the sleep
  score when the data quality is below 50 of 100. A result stored under an older contract version
  keeps the older score until it is analysed again.
- Contract version 6 removed the `total_duration_s` item. Recording length describes the
  recording rather than the sleep, and clients show it from the recording's own metadata. A result
  stored under an older contract version may still carry the item; clients ignore it.
- Contract version 7 put every stream on one time base: seconds since the recording's start. A
  stream that began after the recording started no longer shifts its stages, heart-rate series and
  figures earlier, and clock-sync and jump times use the same base as the samples. Figures that
  depend on sample times can change for recordings where a stream did not start at the first
  sample. A result stored under an older contract version keeps the older times until it is
  analysed again.
- Contract version 8 computes heart-rate variability from the raw R-R intervals instead of
  the stager's sleep epochs: the beats are chained into runs, artefacts are detected and
  removed, and the figures are the median over fixed five-minute windows. `hrv_sdnn_ms` is
  replaced by `hrv_sdnn_median_ms`, and `hrv_ln_rmssd`, `hrv_artifact_share`,
  `hrv_window_count` and an `hrv` window series are added. A result stored under an older
  contract version keeps the older figures until it is analysed again.

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

Every stream shares one time base: `t_s` is seconds since `start_unix_ns`, which
is `meta.start_utc_ms`. Absolute time is `start_unix_ns + t_s * 1e9`. `t_s` is
therefore zero at the recording's start, not at the first sample, so a stream
that connects later keeps its place on the clock.

Within a stream, the spacing between samples comes from its own clock:
`elapsed_ns` between callbacks, and the strap timestamps inside an ECG or
accelerometer batch. The phone wall clock anchors the stream to the recording
start through a robust offset, the median of phone time minus start minus
elapsed time across the stream, so jitter in when a callback ran does not enter
the spacing. A backward move of `elapsed_ns` starts a new segment with its own
anchor instead of switching that stream to another clock, and time without
samples stays a gap. A wrong or reset strap clock therefore does not move the
recording. Clock-sync and clock-jump times use the same base as the samples.

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
- `confidence` and `artifacts` are optional. Confidence is kept on the timeline and reported
  through `capabilities`, but the data-quality score does not use it: a stager's confidence is
  not calibrated against the sensor, so it describes the model rather than the data. `unknown`
  means the epoch could not be scored; an epoch may still carry a stage while being flagged as
  an artifact (for example a short electrode pop), so artifacts are not necessarily unknown.
- `probabilities` is optional. If confidence is absent, it is derived from the
  maximum probability.
- `beats_s` is optional and lets a stager report the beat times it detected. It is not
  used by the shared figures: heart-rate variability is computed from the raw R-R stream
  by the shared beat series, so every stager gives the same HRV for the same recording.
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

- scored/unscored duration, total sleep, efficiency, sleep latency, WASO,
  awakenings (wake periods longer than two minutes), final awakening, time per
  stage;
- HR mean/min/max, movement index/mean/epochs;
- HRV RMSSD, SDNN, ln RMSSD, artifact share and window count over five-minute windows,
  plus an `hrv` series with one entry per window (see below);
- an epoch-aligned heart-rate series, as a `series` item;
- explicit support per figure: `supported`, `unsupported_device`,
  `insufficient_data`, or `unsupported_algorithm`, with a reason. Only
  supported figures become items.

Efficiency is total sleep divided by scored time, and it is reported as
`insufficient_data` with a reason when less than half of the recording could be
scored. Latency runs from the first scored epoch, so leading `unknown` epochs do
not count. WASO, awakenings and the final awakening stay on the recording
timeline. Movement per epoch is the mean deviation from the night's median
accelerometer magnitude rather than from a nominal 1 g, so a constant sensor
offset does not read as movement.

### Heart-rate variability

HRV is computed from the raw R-R intervals and from nothing the stager produced.
Two analyses of one recording therefore report the same HRV whatever stages they carry.

**Beat series.** The strap reports R-R intervals in bursts, and the burst's arrival time
jitters by tens to hundreds of milliseconds. The shared beat series
(`analysis/beats.py`) chains every interval exactly and watches the offset between the
notification clock and the chained beats. In a healthy stream that offset wanders by less
than one interval: it is the phase of the last beat inside its notification plus the
arrival jitter. A backlog (a notification carrying more time than elapsed), a late
timestamp or a stretch of sparse notifications moves the offset down or up for a while and
it comes back. A real loss raises it by the missing time and it stays raised. A run ends
only at such a persistent step: the medians of the bursts after it and after a second
window are both more than one recent interval plus half a second above the median before
it. The chain being ahead of the clock, a negative offset, never ends a run. Each run is
then placed on the clock at the median offset of its own bursts, so the beats keep their
place across the whole recording. The series keeps a `nan` entry at every break, so no
successive difference is ever taken across one.

**Artifacts.** Each run is classified with the adaptive decision rules of Lipponen &
Tarvainen (2019), *A robust algorithm for heart rate variability time series artefact
correction using novel beat classification*, Journal of Medical Engineering & Technology
43(3), 173-181. The threshold is 5.2 times the quartile deviation of the surrounding 91
successive differences, and the decision boundaries use the published constants. Ectopic,
missed, extra, long and short intervals are flagged and excluded, not corrected: the
figures never rest on an invented beat. A successive difference that touches a flagged
interval is dropped. A perfectly regular stretch has a quartile deviation of zero, so the
threshold has a floor of 20 ms; a normal sinus rhythm is far less regular than that.

**Windows.** The recording is cut into fixed five-minute windows. Each window reports
RMSSD, SDNN, ln RMSSD, mean heart rate and its artifact share. A window enters the
summary when

- it has at least 30 clean intervals covering at least half of it,
- its artifact share is at most 5%, and
- when the recording has an accelerometer stream, movement is above the movement
  threshold in at most a fifth of its 30-second intervals.

That rule reads raw signals only; stages never decide it. The summary figure of each
measure is the median over the included windows, so a single disturbed window does not
move the night.

The items are:

| Item | Meaning |
| --- | --- |
| `hrv_rmssd_ms` | Median RMSSD of the valid windows. |
| `hrv_sdnn_median_ms` | Median SDNN of the valid windows. It describes short-term variability; it is not a whole-night SDNN. |
| `hrv_ln_rmssd` | Median of ln RMSSD, in `ln(ms)`. |
| `hrv_artifact_share` | Share of all R-R intervals removed as artifacts. |
| `hrv_window_count` | Number of windows that entered the summary. |
| `hrv_rr_count` | Number of clean intervals in those windows. |

The `hrv` series carries every window, included or not, so a client can show the windows
that were left out and why:

```json
{
  "kind": "series",
  "id": "hrv",
  "label": "HRV by 5-minute window",
  "group": "heart",
  "unit": "ms",
  "sources": ["hr"],
  "series": {
    "window_seconds": 300,
    "start_unix_ms": 1790000000000,
    "count": 2,
    "start_s": [0.0, 300.0],
    "rmssd_ms": [38.4, 41.2],
    "sdnn_ms": [27.6, 28.1],
    "ln_rmssd": [3.648, 3.718],
    "mean_hr_bpm": [60.2, 58.9],
    "artifact_share": [0.0, 0.012],
    "intervals": [296, 291],
    "included": [true, true],
    "reason": ["", ""]
  }
}
```

A window that cannot be summarised still carries the figures it could compute, and its
`reason` is empty when it was included and otherwise `too_little_data`, `artifacts` or
`movement`. A recording shorter than five minutes has no `hrv` series and reports
`insufficient_data` with `recording_shorter_than_one_hrv_window`.

### Data-quality score

The data-quality score is a `score` item with id `data_quality`. It uses the
tunable formula from issue #10, without stage confidence since contract version 5:

```text
100 * (0.4667 * usable epoch coverage
     + 0.3333 * (1 - artifact epoch rate)
     + 0.2 * HR coverage)
```

Stage confidence is not a component: it says how sure a stager is, not how good
the data is, and it is uncalibrated. The weights are the former 0.35/0.25/0.15
renormalised over their sum of 0.75. Unavailable components are removed and the
remaining weights are renormalized. The item carries the surviving components, so
the app can explain the score.

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

Below a data-quality score of 50 out of 100 the sleep score is withheld. A score
computed from too little good data would read as a statement about the night
rather than about the recording. The result then carries no `sleep_quality` item,
and the derivation records `insufficient_data` with the reason
`not_enough_good_data` for it. The stages and the other figures are unaffected.

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
