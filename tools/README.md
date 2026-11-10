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
```

`read_diagnostics.py` is the reader for the diagnostics directory documented in
[`docs/diagnostics.md`](../docs/diagnostics.md). The other two are the release checks
described in [`docs/versioning.md`](../docs/versioning.md).
