"""Frame and CRC checks for the files of a downloaded recording.

The API stores the bytes of a recording as the phone sent them, compressed or
not, and never looks inside. The worker owns the checking: after it has
downloaded a file (the API inflates compressed files on the way out) it checks
frame headers and CRCs and keeps only the valid prefix of each stream, as the
recovery rule of docs/recording-format.md says. Every file goes through the same
check, whichever way it was uploaded, before the analysis subprocess sees it.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .frames import HEADER_SIZE, STREAM_IDS, FrameError, check_header, iter_frames

MAX_EVENT_LINE = 8 * 1024

RECORDING_FILES = {"events.jsonl", *STREAM_IDS}


@dataclass(frozen=True)
class FrameCheck:
    name: str
    size: int
    valid_bytes: int
    frames: int
    lines: int
    truncated: bool
    error: str | None


def verify_recording_file(path: Path, name: str) -> FrameCheck:
    """Check a downloaded file and cut back anything after its valid prefix."""

    size = path.stat().st_size if path.exists() else 0
    if size == 0:
        return FrameCheck(name, 0, 0, 0, 0, False, None)
    data = path.read_bytes()
    if name == "events.jsonl":
        return _verify_events(path, data)
    stream = STREAM_IDS.get(name)
    if stream is None:
        return FrameCheck(name, size, size, 0, 0, False, None)
    return _verify_log(path, data, name, stream)


def _verify_log(path: Path, data: bytes, name: str, stream: int) -> FrameCheck:
    size = len(data)
    try:
        check_header(data, stream)
    except FrameError:
        _truncate(path, 0)
        return FrameCheck(name, size, 0, 0, 0, True, "invalid frame-log header")

    frames = 0
    valid = HEADER_SIZE
    try:
        for frame in iter_frames(data, stream):
            valid = frame.end
            frames += 1
    except FrameError as exc:
        _truncate(path, valid)
        return FrameCheck(name, size, valid, frames, 0, True, f"{exc} at byte {exc.offset}")
    return FrameCheck(name, size, valid, frames, 0, False, None)


def _verify_events(path: Path, data: bytes) -> FrameCheck:
    size = len(data)
    lines = 0
    valid = 0
    start = 0
    for index, byte in enumerate(data):
        if index - start > MAX_EVENT_LINE:
            error = f"event line is too long at byte {start}"
            _truncate(path, valid)
            return FrameCheck("events.jsonl", size, valid, 0, lines, True, error)
        if byte != 0x0A:
            continue
        line = data[start:index]
        if line:
            try:
                event = json.loads(line)
            except ValueError:
                event = None
            if not isinstance(event, dict) or not event.get("type"):
                error = f"invalid event line at byte {start}"
                _truncate(path, valid)
                return FrameCheck("events.jsonl", size, valid, 0, lines, True, error)
            lines += 1
        valid = index + 1
        start = index + 1
    if valid < size:
        _truncate(path, valid)
        return FrameCheck(
            "events.jsonl",
            size,
            valid,
            0,
            lines,
            True,
            f"truncated event line at byte {valid}",
        )
    return FrameCheck("events.jsonl", size, valid, 0, lines, False, None)


def _truncate(path: Path, size: int) -> None:
    with path.open("r+b") as handle:
        handle.truncate(size)


__all__ = [
    "FrameCheck",
    "RECORDING_FILES",
    "STREAM_IDS",
    "verify_recording_file",
]
