# wav2sleep ECG stager

`wav2sleep` is the first real ECG model behind the analysis contract in
[`analysis-contract.md`](analysis-contract.md). It is an optional worker extra:
a base worker does not install PyTorch, the upstream package or a checkpoint.

- **Model:** [joncarter1/wav2sleep](https://github.com/joncarter1/wav2sleep),
  ECG-only path of the cardio-respiratory model.
- **Paper:** Carter JF, Tarassenko L. *wav2sleep: A Unified Multi-Modal Approach
  to Sleep Stage Classification from Physiological Signals.* ML4H 2024,
  [arXiv:2411.04644](https://arxiv.org/abs/2411.04644).
- **Licence:** MIT. The checkpoint is pinned by revision and SHA-256; no model
  is downloaded while a job runs.
- **Output:** 4 classes — Wake, Light (N1+N2), Deep (N3), REM — with per-epoch
  probabilities, confidence and ECG-coverage artifacts.

## Install

The extra needs Python **3.11+** (the worker itself still supports 3.10 for the
baseline). It installs a CPU-only PyTorch wheel, `wav2sleep` at a pinned commit
and about 1.5 GB of dependencies:

```bash
cd backend/worker
uv sync --frozen --extra wav2sleep
```

The extra is intentionally not part of the Docker image or CI. To build an image
with it, copy the usual Dockerfile and add `--extra wav2sleep` to the two
`uv sync` commands. Keep the checkpoint outside the image and mount it read-only.

## Checkpoint

Download the pinned checkpoint once on a machine with network access:

```bash
cd backend
uv run --project worker --frozen --extra wav2sleep \
  python scripts/download_wav2sleep_checkpoint.py --dest /srv/taiga/models/wav2sleep
export TAIGA_WAV2SLEEP_CHECKPOINT=/srv/taiga/models/wav2sleep
```

| Property | Value |
| --- | --- |
| Hub repo | `joncarter/wav2sleep` |
| Revision | `8b6e46ab3ef6e3945282b81f631933c59db0b205` |
| `state_dict.pth` | 9,666,562 bytes |
| SHA-256 | `ea6fb4410315cf6cce406fe1ffd44cba83e8dc69be6a69101aff62cc2cbee0bc` |

Without `--dest`, the script uses `$TAIGA_WAV2SLEEP_CHECKPOINT`, or
`${XDG_CACHE_HOME:-~/.cache}/taiga-sleep/models/wav2sleep`. The worker checks
the same default. A checkpoint with a different digest is rejected with
`invalid_checkpoint`; there is no silent upgrade and no runtime Hub download.

## How the adapter works

1. The shared decoder has already removed CRC-damaged frames, converted µV to
   mV, applied the phone/strap clock handling and built per-epoch coverage.
2. ECG is resampled to exactly **1024 samples per 30 s epoch**
   (34.133… Hz) on an epoch-aligned grid. A partial last epoch is zero-filled.
3. Samples outside real coverage are linearly interpolated and then zero-filled,
   matching upstream preprocessing.
4. Each epoch is checked for real signal: ECG coverage, saturation, the Polar
   HR contact flag (when the strap reports one), and the ECG RMS relative to the
   night median. A detached strap or a high-amplitude electrode artifact is not
   treated as a sleep stage.
5. The model input is z-scored over the **stageable** samples only; artifact
   epochs are replaced with a flat signal. This stops a noisy half-night from
   rescaling the clean half. By default the input is **not** padded to 10 hours:
   the network is fully convolutional and handles a variable number of complete
   epochs. Set `pad_to_max_length=true` to reproduce upstream's fixed 10 h
   input (slower, and zero padding changes the z-score of short nights).
6. Nights longer than `max_length_hours` are truncated for the model; the
   remaining canonical epochs are returned as `unknown` with an artifact flag.
7. `argmax` gives the canonical stage and `softmax` gives per-class
   probabilities. `confidence` is the maximum probability. Probabilities are
   smoothed with a centered 5-epoch (2.5 min) moving average before `argmax`, so
   a one-epoch flip does not become a visible stage change.
8. Long no-contact/noise runs become `unknown` (`confidence = null`,
   `artifact = true`). A single bad epoch is still staged but flagged as an
   artifact, so the night does not become a field of tiny gaps. This is what
   turns an unbuckled strap into "unknown" instead of "awake".

Class order is part of the checkpoint contract and must not change:

| Index | Stage | Canonical |
| --- | --- | --- |
| 0 | Wake | `wake` |
| 1 | N1 + N2 | `light` |
| 2 | N3 | `deep` |
| 3 | REM | `rem` |

## Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `checkpoint` | `""` | Local checkpoint directory. Empty reads `TAIGA_WAV2SLEEP_CHECKPOINT`. |
| `max_length_hours` | `10` | Truncate the model input; longer recordings keep unscored epochs. |
| `pad_to_max_length` | `false` | Zero-pad to `max_length_hours` for upstream parity. |
| `batch_size` | `1` | Accepted for API parity; one job analyses one night. |
| `min_ecg_coverage` | `0.5` | Minimum fraction of an epoch with real ECG samples. |
| `min_usable_epochs` | `60` | Refuse the night below this many usable epochs (30 min). |
| `min_hr_contact_fraction` | `0.5` | Reject epochs without enough Polar HR contact when the strap reports it. |
| `max_ecg_rms_ratio` | `12.0` | Reject epochs this many times louder than the night median; `0` disables. |
| `min_ecg_rms_ratio` | `0.1` | Reject epochs this much quieter than the night median; `0` disables. |
| `min_unknown_epochs` | `2` | Runs of bad epochs shorter than this are staged and flagged, not turned into gaps. |
| `smooth_window_epochs` | `5` | Centered probability smoothing window; `1` disables. |
| `device` | `cpu` | `cpu` or `auto`. The extra ships CPU PyTorch. |

Failure codes are stable: `missing_stream`, `insufficient_ecg`, `bad_ecg`,
`missing_checkpoint`, `invalid_checkpoint`, `invalid_params` and
`algorithm_unavailable` (for example when the extra is installed but torch
cannot be imported under a tight memory limit).

## Default selection and fallback

When the extra and checkpoint are present the worker reports the algorithm in
its capability report, so it appears in `GET /api/taiga/algorithms`. The app
already prefers `wav2sleep`, then `sleep-ecg`, then `baseline`, and only chooses a
stager whose required streams and rates match the recording. Re-analysis of a
local recording uses the same selection instead of hard-coding `baseline`.

A recording without ECG falls back to the built-in baseline. A recording with
ECG but too little usable data still fails with `insufficient_ecg`; automatic
fallback to another stager is tracked in #32, together with user-configurable
preferences and comparison UI. There are no accuracy claims without labels.

## Why the stage timeline looked rough

Both built-in stagers classify one 30 s epoch at a time. A single uncertain
epoch in the middle of a stable stage becomes a visible one-epoch segment, and
a short wake/REM/MOVEMENT burst becomes several transitions. The first wav2sleep
runs also used the no-contact tail as if it were ECG, which pulled the global
z-score and produced a long run of spurious wake.

The current defaults smooth the probabilities with a centered **5-epoch
(2.5 min)** window before taking the maximum. On the two real nights this took
the stage transitions from **6.4 / 12.1 per hour** down to **2.0 / 3.6 per
hour** and raised the median stage segment from **1.5 min** to **9.5 / 5.5 min**.
The baseline uses a 7-epoch majority filter, because its per-epoch threshold
rules are noisier. Stage totals barely change.

Smoothing is deliberately a *display/decoding* improvement, not an accuracy
claim. It cannot recover a stage the model never predicted; it only removes
flicker. Set `smooth_window_epochs=1` to see the raw per-epoch output.

## Detached strap and no skin contact

The Polar H10 HR stream carries a contact flag. When the strap is not against
the chest, the ECG log can still contain samples (electrode noise), often with
*high* amplitude and low saturation, so coverage alone is not enough. The
adapter now uses three independent checks:

1. **HR contact**: epochs with less than `min_hr_contact_fraction` (default 50 %)
   of contact samples are unusable. This is the primary H10 signal.
2. **ECG RMS**: epochs whose RMS is more than `max_ecg_rms_ratio` (default 12×)
   or less than `min_ecg_rms_ratio` (default 0.1×) of the night median are
   unusable. This catches a detached strap even on devices without a contact
   flag, and electrode pops.
3. **Coverage/saturation**: existing gaps and pinned ECG samples.

A long bad run is reported as `unknown`, not `wake`, and does not contribute to
sleep metrics. A single bad epoch is staged (the neighbours usually carry it),
but is still flagged as an artifact and lowers the quality score. On the 7 h
test night with the strap unbuckled for the last ~2.5 h, the previous result
showed 249 min of wake; the current result reports that period as unknown and
keeps the remaining 4.2 h of scorable sleep.

If the H10 contact flag itself is unreliable, set `min_hr_contact_fraction=0`
and rely on the ECG RMS rule. To inspect the raw contact and RMS values, use
the result's `provenance.input_quality.hr_contact_fraction` and the per-epoch
extras (`hr_contact_available`, `artifact_epochs`, `unknown_epochs`).

## Result provenance

The standard provenance includes `algorithm.id`, `algorithm.version`, licence,
citation and homepage. wav2sleep adds:

```json
"algorithm": {
  "id": "wav2sleep",
  "version": "1.1.0",
  "checkpoint": "/srv/taiga/models/wav2sleep",
  "checkpoint_revision": "8b6e46ab3ef6e3945282b81f631933c59db0b205",
  "checkpoint_sha256": "ea6fb4410315cf6cce406fe1ffd44cba83e8dc69be6a69101aff62cc2cbee0bc",
  "checkpoint_bytes": 9666562,
  "classes": ["wake", "light", "deep", "rem"]
}
```

`extras` also records the scored/padded/artifact/unknown epoch counts, contact
and RMS thresholds, smoothing window, sample rate, device and torch version.
`stages.probabilities` carries the smoothed four-class probabilities and
`capabilities.probabilities` is `true`.

## Benchmark: synthetic fixture

The checked-in 120-second synthetic recording (103,457 bytes), x86_64 Linux
laptop, CPU-only, one torch thread, `TAIGA_JOB_MEMORY_MB=2048`. The fixture is
shorter than the default 30 min minimum, so these runs pass
`{"min_usable_epochs": 1}`. These numbers prove the path; they are not an
accuracy claim.

| Model | Runtime | Peak RSS | Quality | Stages |
| --- | --- | --- | --- | --- |
| baseline 1.1.0 | 0.04 s | 38.8 MiB | 87.5 | light 120 s |
| wav2sleep 1.1.0, variable length | 1.9 s | 399 MiB | 90.8 | light 120 s |
| wav2sleep 1.1.0, padded to 10 h | 4.0 s | 624 MiB | 100.0 | wake 120 s |

The padded run shows the zero-padding effect on a very short recording: the
z-score is dominated by padding and the stage distribution is meaningless. That
is why padding is off by default.

## Benchmark: two real Polar H10 nights

Two local H10 recordings, 130 Hz ECG, full-night coverage, `max_length_hours=10`,
default variable-length input, `TAIGA_JOB_MEMORY_MB=2048`. Session ids are
omitted on purpose. There are no ground-truth labels, so these are feasibility
and cost numbers, not accuracy.

| Night | Duration | Model | Runtime | Peak RSS | Quality | Wake | Light | Deep | REM | Unscored |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 7.03 h | baseline 1.1.0 | 1.7 s | 374 MiB | 59.3 | 51.5 min | 197.0 min | 3.5 min | 2.5 min | 167.5 min |
| A | 7.03 h | wav2sleep 1.1.0 | 4.7 s | 726 MiB | 84.5 | 75.0 min | 116.0 min | 32.5 min | 27.5 min | 171.0 min |
| B | 9.41 h | baseline 1.1.0 | 2.3 s | 489 MiB | 86.4 | 66.0 min | 468.5 min | 8.0 min | 8.0 min | 14.5 min |
| B | 9.41 h | wav2sleep 1.1.0 | 6.0 s | 859 MiB | 94.3 | 33.5 min | 349.5 min | 55.5 min | 109.5 min | 17.0 min |

Both models now report the unbuckled part of night A as `unknown` instead of
wake. The quality score is lower than the first wav2sleep run because those
artifacts count against it; that is correct.

Roughness after smoothing (same runs):

| Night | Model | Transitions/h | Median stage segment |
| --- | --- | --- | --- |
| A | baseline 1.1.0 | 2.7 | 3.5 min |
| A | wav2sleep 1.1.0 | 2.0 | 9.5 min |
| B | baseline 1.1.0 | 5.3 | 2.0 min |
| B | wav2sleep 1.1.0 | 3.6 | 5.5 min |

Before smoothing, wav2sleep had 6.4 / 12.1 transitions per hour and a median
segment of 1.5 min on both nights.

Tested locally with `backend/scripts/benchmark_analysis.py`; the full results
contain health data and were not committed.

Padding night A to the full 10 h changed the distribution (Wake 74 min vs 75,
Light 136.5 vs 116, Deep 12 vs 32.5, REM 28.5 vs 27.5) and cost about 0.7 s
more, but there is no label set to say which is closer. Keep the default unless
you need byte-for-byte parity with the upstream inference script.

## Memory and small machines

The address-space limit matters more than RSS because PyTorch maps a lot of
virtual memory at import:

| `TAIGA_JOB_MEMORY_MB` | Result on the synthetic night |
| --- | --- |
| `1024` | Import failed under the address-space cap. |
| `1536` | Passed. |
| `2048` | Passed and is the recommended minimum. |
| `3072` | Passed; same peak RSS, shorter runtime noise. |

A practical minimum is a 2 vCPU, 4 GB RAM CPU-only machine with about 1.5 GB
free disk for the extra. Set `TAIGA_JOB_MEMORY_MB=2048` (or `0` to disable the
cap when the host has headroom) and keep `TAIGA_JOB_TIMEOUT_S` at its default.
The worker logs a warning when wav2sleep is installed with a smaller limit.
Numeric libraries are already limited to one thread; the adapter also calls
`torch.set_num_threads(1)`.

## Comparing with SleepECG

Run the same recording through both optional stagers once #22 lands:

```bash
cd backend
uv run --project worker --frozen --extra wav2sleep --extra sleepecg \
  python scripts/benchmark_analysis.py /path/to/recording --model wav2sleep
uv run --project worker --frozen --extra wav2sleep --extra sleepecg \
  python scripts/benchmark_analysis.py /path/to/recording --model sleep-ecg
```

Compare `provenance.runtime_s`, `provenance.peak_rss_mb`, input-quality
warnings, stage distribution and quality on the same night. Do not compare
across different nights or report accuracy without labels.

## Data handling

Recordings and analysis results are health data. They live only in the local
recording directory, the backend data directory, or a local benchmark file. The
checkpoint contains no personal data. Never commit a recording, an analysis
result or a benchmark output.
