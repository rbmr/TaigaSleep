# Tools

Small scripts that sit outside the app and the backend. Run them from the repository
root; nothing here is installed or imported by either component.

## Recording tools

[`read_recording.py`](read_recording.py) reads a recording directory and
[`import_polarrecorder.py`](import_polarrecorder.py) converts PolarRecorder nights into
one. Both work on the recording frame format described in
[`docs/recording-format.md`](../docs/recording-format.md), which is implemented once,
in `backend/worker/taiga_worker/frames.py`. They therefore run in the worker's
environment:

```bash
# summarise a recording: frames, samples, rates and gaps per stream
uv run --project backend/worker --frozen python tools/read_recording.py /path/to/<session id>

# convert PolarRecorder nights (the output directory stays out of the repository)
uv run --project backend/worker --frozen python tools/import_polarrecorder.py \
  /sdcard/polar/PolarRecorder --out "$HOME/taiga-imported" --min-hours 3
```

`uv` uses the worker's `uv.lock` and creates `backend/worker/.venv` if it is missing,
so the version of the format module that runs is the one the worker uses.

## Standard-library tools

These use only the standard library and run with the system interpreter:

```bash
python3 tools/read_diagnostics.py /tmp/diag
python3 tools/check_version_bump.py
python3 tools/release_plan.py --self-test
python3 tools/ci_areas.py --self-test
```

`read_diagnostics.py` is the reader for the diagnostics directory documented in
[`docs/diagnostics.md`](../docs/diagnostics.md). The other three are the checks
described in [`docs/versioning.md`](../docs/versioning.md) and
[`docs/ci.md`](../docs/ci.md).

## CI shards

The parallel jobs in [`docs/ci.md`](../docs/ci.md#how-the-parallelism-is-configured)
get their share of a test suite from two small scripts:

```bash
# from backend/api: the names of one shard, and the check that every test is in one
python3 ../../tools/go_test_shard.py --index 0 --shards 4
python3 ../../tools/go_test_shard.py --self-test

# from backend: the ids of one shard of the integration suite
uv run --project worker --frozen python ../tools/pytest_shard.py \
  --index 0 --shards 4 tests/test_integration.py
```

`go_test_shard.py` uses only the standard library and the Go toolchain.
`pytest_shard.py` collects the suite with the same environment that runs it, so
run it through the worker's project, as the workflow does.

## Check scripts

[`check_kotlin.sh`](check_kotlin.sh) runs detekt over the app's Kotlin sources
with the config and baseline in [`detekt/`](detekt). CI calls the same script, so
the local and the CI run agree. It needs a JDK; CI uses 21:

```bash
./tools/check_kotlin.sh
```

The Go and Python checks are plain commands rather than scripts, because their
tool versions come from the Go toolchain and the worker's lock file. They are
listed in [`docs/ci.md`](../docs/ci.md#running-the-checks-locally).
