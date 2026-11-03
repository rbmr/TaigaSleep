"""Decompression and frame checks for compressed recording uploads.

The API stores one complete gzip member per appended chunk and never inflates.
The worker owns the other half: it decompresses the concatenated members with
hard limits, then checks frame headers and CRCs and keeps only the valid prefix
of each stream. Both steps run before the analysis subprocess sees the files.
"""

from __future__ import annotations

import json
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path

# The frame-log constants mirror backend/api/internal/api/frames.go.
HEADER_SIZE = 8
MAX_PAYLOAD = 1 << 20
MAX_EVENT_LINE = 8 * 1024

STREAM_IDS = {"hr.log": 1, "ecg.log": 2, "acc.log": 3}
RECORDING_FILES = {"meta.json", "events.jsonl", "hr.log", "ecg.log", "acc.log"}

READ_BLOCK = 1024 * 1024
MAX_OUTPUT_CHUNK = 4 * 1024 * 1024


class UploadError(RuntimeError):
    """A compressed recording cannot be read within the allowed limits."""


@dataclass(frozen=True)
class DecompressionStats:
    members: int
    stored_bytes: int
    uncompressed_bytes: int


@dataclass(frozen=True)
class FrameCheck:
    name: str
    size: int
    valid_bytes: int
    frames: int
    lines: int
    truncated: bool
    error: str | None


def decompress_gzip(
    source: Path,
    destination: Path,
    *,
    max_bytes: int,
    max_members: int,
    max_ratio: float,
) -> DecompressionStats:
    """Inflate concatenated gzip members with explicit size and ratio limits.

    ``zlib`` verifies each member's CRC and ISIZE, so a corrupt or truncated
    member fails here instead of reaching the analysis. No member may expand
    past ``max_ratio`` times its compressed size, and the whole file may not
    exceed ``max_bytes``.
    """

    stored_bytes = source.stat().st_size
    if stored_bytes == 0:
        raise UploadError("compressed file is empty")

    members = 0
    written = 0
    pending = b""
    with source.open("rb") as src, destination.open("wb") as dst:
        while True:
            decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
            member_in = 0
            member_out = 0
            while True:
                if not pending:
                    pending = src.read(READ_BLOCK)
                    if not pending:
                        raise UploadError("truncated gzip member")
                before = len(pending)
                try:
                    out = decompressor.decompress(pending, MAX_OUTPUT_CHUNK)
                except zlib.error as exc:
                    raise UploadError(f"invalid gzip member: {exc}") from exc
                written += len(out)
                if written > max_bytes:
                    raise UploadError(
                        f"decompressed file exceeds the {max_bytes} byte limit"
                    )
                if out:
                    dst.write(out)
                member_out += len(out)
                if decompressor.eof:
                    pending = decompressor.unused_data
                    member_in += before - len(pending)
                    break
                pending = decompressor.unconsumed_tail
                member_in += before - len(pending)

            members += 1
            if members > max_members:
                raise UploadError(
                    f"compressed file has more than {max_members} members"
                )
            if member_out == 0:
                raise UploadError("gzip member is empty")
            if member_out > max_ratio * max(member_in, 1):
                raise UploadError("gzip member expansion ratio is too high")

            if not pending:
                pending = src.read(READ_BLOCK)
                if not pending:
                    break

    return DecompressionStats(
        members=members, stored_bytes=stored_bytes, uncompressed_bytes=written
    )


def verify_recording_file(path: Path, name: str) -> FrameCheck:
    """Check a decompressed stream and cut back anything after its valid prefix."""

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
    if size < HEADER_SIZE or data[:4] != b"TSLG" or data[4] != 1 or data[5] != stream:
        _truncate(path, 0)
        return FrameCheck(name, size, 0, 0, 0, True, "invalid frame-log header")
    if data[6] != 0 or data[7] != 0:
        _truncate(path, 0)
        return FrameCheck(name, size, 0, 0, 0, True, "invalid frame-log header")

    frames = 0
    valid = HEADER_SIZE
    at = HEADER_SIZE
    while at < size:
        if size - at < 4:
            error = f"truncated frame at byte {at}"
            _truncate(path, valid)
            return FrameCheck(name, size, valid, frames, 0, True, error)
        length = struct.unpack_from("<I", data, at)[0]
        if length > MAX_PAYLOAD:
            error = f"frame payload too large at byte {at}"
            _truncate(path, valid)
            return FrameCheck(name, size, valid, frames, 0, True, error)
        end = at + 8 + length
        if end > size:
            error = f"truncated frame at byte {at}"
            _truncate(path, valid)
            return FrameCheck(name, size, valid, frames, 0, True, error)
        payload = data[at + 4 : at + 4 + length]
        expected = struct.unpack_from("<I", data, at + 4 + length)[0]
        if expected != zlib.crc32(payload) & 0xFFFFFFFF:
            error = f"frame CRC mismatch at byte {at}"
            _truncate(path, valid)
            return FrameCheck(name, size, valid, frames, 0, True, error)
        at = end
        frames += 1
        valid = at
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
    "DecompressionStats",
    "FrameCheck",
    "RECORDING_FILES",
    "UploadError",
    "decompress_gzip",
    "verify_recording_file",
]
