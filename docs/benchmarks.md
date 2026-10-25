# Analysis benchmarks

Sleep staging is the only heavy part of Taiga Sleep. This page is for measuring
one stager on one recording locally, without the API or the database.

**Never commit a recording or a result containing health data.** Run the script
on your machine and keep the output local. The repository only contains
synthetic fixtures.

## Run one analysis

```bash
cd backend

uv sync --project worker --frozen
uv run --project worker --frozen python scripts/benchmark_analysis.py \
  /path/to/recording \
  --model baseline \
  --memory-mb 1024
```

The recording directory must contain `meta.json` and the frame logs from
[`recording-format.md`](recording-format.md). The script runs the real job
subprocess with the same reader and stager the worker uses.

Useful options:

| Option | Meaning |
| --- | --- |
| `--model` | Stager id. `baseline` is built in; `wav2sleep` and `sleep-ecg` are optional extras (#23, #22). |
| `--version` | Exact algorithm version. Omit for the latest installed version. |
| `--params` | JSON object of algorithm parameters, e.g. `'{"deep_hr_ratio": 0.85}'`. |
| `--memory-mb` | Address-space limit for the subprocess. `0` disables it. |
| `--timeout` | Wall-clock limit in seconds. |
| `--json` | Print the full model-agnostic result instead of the summary. |
| `--out` | Write the full result to a local file. Do not commit it. |

The summary includes the input bytes, recording duration, stream coverage,
analysis runtime, wall time, peak RSS, stage seconds, quality breakdown, warnings
and metric support.

## Reference run (synthetic, not a real night)

The checked-in 120-second synthetic fixture (`backend/worker/tests/analysis_fixture.py`)
on an x86_64 Linux laptop, CPU-only, baseline `1.0.0`:

| Measure | Value |
| --- | --- |
| Input size | 103,457 bytes |
| Analysis runtime | 0.022 s |
| Wall time including subprocess start | 0.187 s |
| Peak RSS | 38.2 MiB |
| Quality score | 87.5 |
| Stage seconds | wake 0, rem 0, light 120, deep 0 |

This proves the measurement path and gives a lower bound for the baseline. It is
**not** a real night, not an accuracy claim, and not representative of the ML
models.

## Real H10 nights

Record a night with the app, copy the session directory to the laptop (or use
the backend's stored copy), and run the command above for each model/version.
Keep a table like this outside the repository:

| Date | Device | Duration | Input MB | Model/version | Runtime s | Peak RSS MB | Quality | Stage distribution | Machine | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-10-23 | Polar H10 | 8 h |  |  |  |  |  |  |  |  |

Report feasibility, stage distribution, runtime, peak memory and quality. Do
not report accuracy without ground-truth labels.

### What to look for

- **Coverage**: HR contact, ECG saturation, gaps and truncated tails from the
  summary. Exclude no-contact periods from staging.
- **Runtime**: compare the analysis runtime with the wall time; the difference
  is subprocess/import overhead.
- **Peak RSS**: set `TAIGA_JOB_MEMORY_MB` above the measured peak with a margin.
  The limit is an address-space cap, not a reservation.
- **Quality**: inspect the breakdown. A high score with low stage confidence or
  low HR coverage is not a good result.
- **Stage distribution**: compare algorithms on the same recording, not across
  different nights.

## Comparing models

Run the same recording through each installed model and version:

```bash
uv run --project worker --frozen python scripts/benchmark_analysis.py /path/to/recording --model baseline
uv run --project worker --frozen python scripts/benchmark_analysis.py /path/to/recording --model wav2sleep
uv run --project worker --frozen python scripts/benchmark_analysis.py /path/to/recording --model sleep-ecg
```

The model/version, licence, citation, runtime, peak RSS and input-quality
statistics are stored in each result's `provenance`, so comparisons remain
meaningful when the user switches algorithms.

## Memory and small machines

The worker runs one job at a time. The analysis subprocess is the only heavy
part; numeric libraries are forced to one thread. On a 1 GB machine add swap
before enabling an ML model, and set `TAIGA_JOB_MEMORY_MB` to a realistic value.
The built-in baseline is small; the wav2sleep and SleepECG extra benchmarks are
tracked in #23 and #22.

## Data handling

- Recordings and analysis results are health data. They live only in the local
  recording directory, the backend's data directory, or a local benchmark file.
- The benchmark script does not upload anything.
- Deleting a session removes its recording directory and cascades its analyses
  and jobs.
- Back up the whole PocketBase data directory, not just `data.db`.
