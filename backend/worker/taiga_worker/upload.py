"""Frame and CRC checks for the files of a downloaded recording.

The API stores the bytes of a recording as the phone sent them, compressed or
not, and never looks inside. The worker owns the checking: after it has
downloaded a file (the API inflates compressed files on the way out) it checks
frame headers, CRCs and payloads, keeps every frame that checks out and reports
each damaged byte range, as the recovery rule of docs/recording-format.md says.
Damage in the middle of a log is left in place for the decoder to step over; a
damaged tail is cut back. Every file goes through the same check, whichever way
it was uploaded, before the analysis subprocess sees it.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .frames import (
    HEADER_SIZE,
    STREAM_IDS,
    FrameError,
    FrameGap,
    iter_frames,
    mapped_log,
    stream_id_of,
)

MAX_EVENT_LINE = 8 * 1024

RECORDING_FILES = {"events.jsonl", *STREAM_IDS}


def is_recording_file(name: str) -> bool:
    """A recording file is the event log or any stream log a tracker declared."""

    return name == "events.jsonl" or name.endswith(".log") or name.endswith(".log.gz")


@dataclass(frozen=True)
class FrameCheck:
    name: str
    size: int
    valid_bytes: int
    frames: int
    lines: int
    truncated: bool
    error: str | None
    gaps: tuple[FrameGap, ...] = ()

    def warnings(self) -> list[str]:
        """What was skipped or cut off, in the wording of a result warning."""

        if self.gaps:
            return [f"{self.name}: {gap.describe()}" for gap in self.gaps]
        if self.truncated and self.error:
            return [f"{self.name}: {self.error}"]
        return []


def verify_recording_file(path: Path, name: str) -> FrameCheck:
    """Check a downloaded file and cut back a damaged tail."""

    size = path.stat().st_size if path.exists() else 0
    if size == 0:
        return FrameCheck(name, 0, 0, 0, 0, False, None)
    if name == "events.jsonl":
        return _verify_events(path, path.read_bytes())
    stream = STREAM_IDS.get(name)
    if stream is None:
        # A stream a newer tracker added is named by its own meta.json; the
        # header says which layout its payloads use.
        with mapped_log(path) as data:
            stream = stream_id_of(data) if data is not None else None
        if stream is None:
            return FrameCheck(name, size, size, 0, 0, False, None)
    return _verify_log(path, size, name, stream)


def _verify_log(path: Path, size: int, name: str, stream: int) -> FrameCheck:
    gaps: list[FrameGap] = []
    frames = 0
    valid = HEADER_SIZE
    unreadable = False
    with mapped_log(path) as data:
        if data is None:
            return FrameCheck(name, 0, 0, 0, 0, False, None)
        try:
            for frame in iter_frames(data, stream, gaps):
                valid = frame.end
                frames += 1
        except FrameError:
            unreadable = True
            valid = 0
    # The file is no longer mapped, so a damaged tail can be cut back now.
    if unreadable:
        _truncate(path, 0)
        return FrameCheck(name, size, 0, 0, 0, True, "invalid frame-log header")
    # Damage in the middle is left where it is: the reader steps over it and the
    # frames after it are valid. A damaged tail is cut back, as before.
    truncated = bool(gaps) and gaps[-1].end == size
    if truncated:
        _truncate(path, valid)
    error = None
    if gaps:
        error = (gaps[-1] if truncated else gaps[0]).describe()
    return FrameCheck(name, size, valid, frames, 0, truncated, error, tuple(gaps))


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
    "is_recording_file",
    "verify_recording_file",
]
