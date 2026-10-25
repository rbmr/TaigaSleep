# SleepECG HR/RR stager

SleepECG is the second optional stager behind the analysis contract. Its value
is the **heartbeat/RR path**: it can stage a recording that has HR/RR data but
no usable ECG. The package and its bundled classifier weights are
**BSD-3-Clause**.

- **Package:** [cbrnr/sleepecg](https://github.com/cbrnr/sleepecg), version
  `0.6.0` in the worker lock.
- **Paper:** Hofer F, Brunner C. *SleepECG: a Python package for sleep stage
  classification based on ECG.* Journal of Open Source Software, 2023.
  [doi:10.21105/joss.05411](https://doi.org/10.21105/joss.05411).
- **Classifiers:** bundled in the package (`wrn-gru-mesa`,
  `wrn-gru-mesa-weighted`, `ws-gru-mesa`). No runtime download.
- **Stages:** three-class Wake/REM/NREM, or two-class Wake/Sleep. There is no
  bundled light/deep classifier; **deep sleep is never invented**.

## Install

SleepECG needs Python **3.11+**, Keras with the PyTorch backend, and PyTorch
CPU. It is an optional extra, so a base worker does not install any of it:

```bash
cd backend/worker
uv sync --frozen --extra sleepecg
```

This adds roughly 1.5 GB of dependencies. There is no separate checkpoint:
the classifier `.zip` files are part of the installed `sleepecg` package.
Set `TAIGA_JOB_MEMORY_MB=2048`; 1024 MiB fails during the Keras/torch import.

## How the adapter works

1. **Beat source.** When ECG is present and `prefer_ecg=true` (default), the
   adapter runs `sleepecg.detect_heartbeats()` on the normalized ECG. This is
   the most reliable path. If ECG is absent, the adapter reconstructs beat
   times from the H10 RR intervals, anchored to the HR sample times and using
   the shared 300–2000 ms RR validity rules. This is the path for HR-only
   nights.
2. **Record.** The beats are passed to `SleepRecord(heartbeat_times=...,`
   `recording_start_time=...)`. The start time is converted from the recording
   UTC start into the recording timezone. Age/gender are passed only when the
   analysis params provide them; otherwise the classifier handles missing
   metadata as it does in training.
3. **Features and classifier.** SleepECG extracts HRV time/frequency features
   and runs the selected Keras classifier. Its output includes an `UNDEFINED`
   column plus the classifier's classes; the adapter drops `UNDEFINED`,
   renormalizes the remaining classes, and uses a high undefined probability
   as a quality flag.
4. **Smoothing.** The class probabilities are smoothed with the same centred
   **5-epoch (2.5 min)** moving average as wav2sleep before `argmax`.
5. **Quality mask.** Epochs with too few beats, low HR contact, poor ECG
   coverage/saturation (ECG path), or a high undefined probability are
   flagged. Long bad runs become `unknown`; single bad epochs are staged but
   flagged as artifacts. The night is refused below 30 min of usable beats.

## Stages and scheme

| Classifier | Mode | Canonical stages |
| --- | --- | --- |
| `wrn-gru-mesa` | `wake-rem-nrem` | `wake`, `rem`, `nrem`, `unknown` |
| `wrn-gru-mesa-weighted` | `wake-rem-nrem` | `wake`, `rem`, `nrem`, `unknown` |
| `ws-gru-mesa` | `wake-sleep` | `wake`, `sleep`, `unknown` |

`deep` is never in a result. The custom stage scheme id is `sleep-ecg`; each
result narrows `capabilities.supported_stages` to the stages of the selected
classifier, so `time_in_sleep_s` is `null` for a three-class run and
`time_in_nrem_s` is `null` for a two-class run.

### Compatibility note

SleepECG 0.6.0 has an edge-case bug in `_nanpsd`: when no HRV window is free of
NaNs it calls `scipy.signal.periodogram` on an empty selection and raises a
shape-mismatch error. The adapter installs a local shim that keeps the upstream
behaviour and only skips that empty call. It is applied at runtime and can be
removed when the dependency is fixed.

## Caveats: metadata and clock time

SleepECG's MESA-trained classifiers use age, gender and recording start time as
features. The app does not currently provide age/gender, so those features are
masked. This is not a cosmetic difference. On the 9.4 h reference night,
supplying plausible age/gender values changed SleepECG's wake total from
128.5 min to roughly 90–105 min and raised wake/sleep agreement with wav2sleep
from 78.6 % to about 86–87 %. On the 7.0 h night the same change moved wake the
other way (134.5 min to 168–191 min), so metadata is a strong prior, not a
simple correction.

The recording start time matters too. Shifting the 9.4 h night's start time
while keeping the signal unchanged produced wake totals from 126.5 min (21:00)
to 295 min (00:00), with 03:00 at 210.5 min. A different sleep schedule or
timezone can therefore move the result.

Conclusion: do not treat wav2sleep and SleepECG as two estimates of the same
quantity. They use different signals, training cohorts and priors, and neither
has seen H10 data during training. Use SleepECG for HR-only nights and as a
comparison; use wav2sleep as the primary 4-class ECG model. Without PSG labels,
a disagreement is not evidence that either model is right.

If age/gender become available, pass them through the analysis params. A result
without them includes a warning that the metadata features are masked.

## Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `classifier` | `wrn-gru-mesa` | Bundled classifier name. |
| `prefer_ecg` | `true` | Use the ECG heartbeat detector when ECG is present. |
| `age` | `null` | Optional subject age passed to the classifier. |
| `gender` | `null` | Optional `female`/`male` passed to the classifier. |
| `max_nans` | `null` | Override the classifier's feature NaN threshold. |
| `min_beats_per_epoch` | `5` | Minimum beats in a 30 s epoch. |
| `min_contact_fraction` | `0.5` | Minimum HR contact fraction when the strap reports it. |
| `min_ecg_coverage` | `0.5` | Minimum ECG coverage on the ECG path. |
| `min_usable_epochs` | `60` | Refuse the night below this many usable epochs (30 min). |
| `min_unknown_epochs` | `2` | Short bad runs are staged and flagged, not turned into gaps. |
| `smooth_window_epochs` | `5` | Centred probability smoothing; `1` disables. |
| `max_undefined_probability` | `0.5` | Reject epochs the classifier marks as undefined. |

Failure codes: `missing_stream`, `insufficient_beats`, `insufficient_data`,
`invalid_classifier`, `algorithm_unavailable` and `invalid_params`.

## Result provenance

The standard provenance includes the package licence, citation and homepage.
The `algorithm` block adds:

```json
"algorithm": {
  "id": "sleep-ecg",
  "version": "1.0.0",
  "package": "sleepecg",
  "package_version": "0.6.0",
  "classifier": "wrn-gru-mesa",
  "classifier_source": "SleepECG",
  "stages_mode": "wake-rem-nrem",
  "beat_source": "ecg"
}
```

`extras` records the classifier, beat source, beat/epoch counts, thresholds,
smoothing window, and whether age/gender were provided.

## Benchmark

Two local H10 nights, `TAIGA_JOB_MEMORY_MB=2048`, CPU-only, one torch thread.
No ground-truth labels, so these are feasibility and cost numbers, not
accuracy. The ECG path is preferred; the RR path is forced with
`{"prefer_ecg": false}` as a proxy for an HR-only night.

| Night | Path | Runtime | Peak RSS | Quality | Wake | REM | NREM | Unscored |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A (7.03 h) | ECG | 6.8 s | 597 MiB | 81.8 | 135.0 min | 0.0 min | 97.5 min | 189.5 min |
| A (7.03 h) | RR | 6.4 s | 568 MiB | 81.3 | 79.5 min | 0.0 min | 172.5 min | 170.0 min |
| B (9.41 h) | ECG | 7.7 s | 653 MiB | 90.8 | 128.5 min | 42.5 min | 377.0 min | 17.0 min |
| B (9.41 h) | RR | 7.4 s | 651 MiB | 93.0 | 378.0 min | 0.0 min | 170.5 min | 16.5 min |

The RR path is clearly less reliable than the ECG detector on these nights.
Prefer ECG whenever it is available; the RR path is the fallback for HR-only
recordings, where there is no ECG to compare against. Deep sleep is intentionally
absent, so `light`/`deep` are `null` and `nrem` is the NREM total.

Synthetic 120 s fixture (pass `{"min_usable_epochs":1}`): 3.4 s, 409 MiB,
quality 86.9, stages wake 90 s + unknown 30 s. This proves the path; it is not
an accuracy claim.

## Memory and small machines

Keras + PyTorch behaves like wav2sleep: 1024 MiB failed during import, 1536 MiB
passed, and **2048 MiB is the recommended minimum**. Peak RSS on real nights was
567–653 MiB. The worker logs a warning if SleepECG is installed with a smaller
limit. About 1.5 GB of extra disk is needed for the virtualenv.

## Comparing with wav2sleep

Run the same recording through both:

```bash
cd backend
uv run --project worker --frozen --extra wav2sleep --extra sleepecg \
  python scripts/benchmark_analysis.py /path/to/recording --model wav2sleep
uv run --project worker --frozen --extra wav2sleep --extra sleepecg \
  python scripts/benchmark_analysis.py /path/to/recording --model sleep-ecg
```

wav2sleep is the primary 4-class ECG model and is preferred by the app when ECG
is present. SleepECG is the fallback for HR-only recordings and a useful
comparison because it uses a very different feature set and classifier.

## Data handling

Recordings and results are health data. Keep them in the local recording
directory, the backend data directory, or a local benchmark file. The
classifier files contain no personal data. Never commit a recording, a result
or a benchmark output.
