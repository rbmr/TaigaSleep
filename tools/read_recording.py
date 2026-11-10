#!/usr/bin/env python3
"""Read a Taiga recording directory (docs/recording-format.md) with the worker's frame format.

Usage: read_recording.py <session_dir>

The frame format lives in the worker, so run the tool from the worker environment:

  uv run --project backend/worker --frozen python tools/read_recording.py <session_dir>

See tools/README.md.

Validates the CRC of every frame, stops each log at the first truncated or corrupt frame (the
recovery rule), and prints per-stream counts, duration, sample rate and gaps.

Exit status: 0 all logs fully valid, 1 unreadable (bad header, no meta.json), 3 a log has a
damaged tail (everything before it was still read).
"""
import json
import os
import struct
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "backend", "worker"))
from taiga_worker import frames  # noqa: E402  (the worker owns the frame format)

STREAMS = {stream: (name, name.split(".")[0].upper())
           for name, stream in frames.STREAM_IDS.items()}
CLOCK_JUMP_NS = 3600 * 10**9  # a step this big in the sample timestamps is a clock reset, not a gap
GAP_SECONDS = {frames.STREAM_HR: 2.5, frames.STREAM_ECG: 1.0, frames.STREAM_ACC: 1.0}


def read_log(path):
  """Return (stream, payloads, valid_end, damaged) for one frame log.

  Frames are accepted until the first truncated or CRC-failing one; the rest is ignored.
  """
  with open(path, "rb") as f:
    data = f.read()
  if len(data) < frames.HEADER_SIZE or data[:4] != frames.MAGIC or data[4] != frames.VERSION:
    raise ValueError("bad header")
  stream = data[5]
  payloads = []
  try:
    for frame in frames.iter_frames(data, stream):
      payloads.append(frame.payload)
  except frames.FrameError as e:
    return stream, payloads, e.offset, True
  return stream, payloads, len(data), False


def fmt_time(ns):
  return datetime.fromtimestamp(ns / 1e9, timezone.utc).strftime("%H:%M:%S")


def summarize(stream, payloads):
  """Return a dict of counts, duration, rate and gaps for one stream."""
  info = {"frames": len(payloads), "samples": 0, "gaps": [], "jumps": [], "modes": {0: 0, 1: 0}}
  if not payloads:
    return info
  gap_ns = GAP_SECONDS[stream] * 1e9
  if stream == frames.STREAM_HR:
    prev = None
    first = last = None
    for p in payloads:
      s = frames.read_hr(p)
      info["samples"] += 1
      if first is None:
        first = s.phone_ns
      if prev is not None and s.phone_ns - prev > gap_ns:
        info["gaps"].append((prev, s.phone_ns - prev))
      prev = last = s.phone_ns
    span = last - first
  else:
    prev = None
    first = last = None
    seg_first = None
    span = 0
    for p in payloads:
      b = frames.read_ecg(p) if stream == frames.STREAM_ECG else frames.read_acc(p)
      info["modes"][b.mode] += 1
      info["samples"] += len(b.timestamps_ns)
      if first is None:
        first = seg_first = b.timestamps_ns[0]
      step = None if prev is None else b.timestamps_ns[0] - prev
      if step is not None and (step < -CLOCK_JUMP_NS or step > CLOCK_JUMP_NS):
        # The strap's clock was reset (battery pulled): count the segments separately.
        info["jumps"].append((b.phone_ns, step))
        span += prev - seg_first
        seg_first = b.timestamps_ns[0]
      elif step is not None and step > gap_ns:
        info["gaps"].append((prev + frames.POLAR_EPOCH_OFFSET_NS, step))
      prev = last = b.timestamps_ns[-1]
    span += last - seg_first
    first += frames.POLAR_EPOCH_OFFSET_NS
  info["start_ns"] = first
  info["duration_s"] = span / 1e9
  # Rate over the time the sensor was actually delivering, so gaps do not read as a slow sensor.
  active = span / 1e9 - sum(g for _, g in info["gaps"]) / 1e9
  info["rate_hz"] = info["samples"] / active if active > 0 else 0.0
  return info


def main(argv):
  if len(argv) != 2:
    print(__doc__)
    return 1
  d = argv[1]
  try:
    with open(os.path.join(d, "meta.json")) as f:
      meta = json.load(f)
  except (OSError, ValueError) as e:
    print("cannot read meta.json: %s" % e)
    return 1
  sensor = meta.get("sensor", {})
  print("session %s  state=%s  sensor=%s (%s)" % (
      meta.get("session_id"), meta.get("state"), sensor.get("kind"), sensor.get("name")))
  start = meta.get("start_utc_ms")
  end = meta.get("end_utc_ms")
  if start:
    print("  start %s UTC" % datetime.fromtimestamp(start / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S"))
  if start and end:
    print("  meta duration %.1f s" % ((end - start) / 1000))
  status = 0
  for stream, (name, label) in STREAMS.items():
    path = os.path.join(d, name)
    if not os.path.exists(path):
      continue
    try:
      s, payloads, valid_end, damaged = read_log(path)
    except (ValueError, OSError, struct.error) as e:
      print("%-8s UNREADABLE: %s" % (name, e))
      status = max(status, 1)
      continue
    if s != stream:
      print("%-8s wrong stream id %d" % (name, s))
      status = max(status, 1)
      continue
    try:
      info = summarize(stream, payloads)
    except (ValueError, struct.error) as e:
      print("%-8s BAD PAYLOAD: %s" % (name, e))
      status = max(status, 1)
      continue
    tail = os.path.getsize(path) - valid_end
    line = "%-8s %s frames=%d samples=%d" % (name, label, info["frames"], info["samples"])
    if info["samples"] > 1:
      line += " duration=%.1fs rate=%.2fHz (excl. gaps)" % (info["duration_s"], info["rate_hz"])
    if stream != frames.STREAM_HR:
      line += " ts_modes(linear/explicit)=%d/%d" % (info["modes"][0], info["modes"][1])
    line += " gaps=%d" % len(info["gaps"])
    line += "  [DAMAGED TAIL: %d bytes ignored]" % tail if damaged else "  [ok]"
    print(line)
    for at, length in info["gaps"]:
      print("    gap at %s UTC, %.1f s" % (fmt_time(at), length / 1e9))
    for at, step in info.get("jumps", []):
      print("    CLOCK JUMP at %s UTC (phone time): sample timestamps moved %.1f s" % (fmt_time(at), step / 1e9))
    if damaged:
      status = max(status, 3)
  events = os.path.join(d, "events.jsonl")
  if os.path.exists(events):
    counts = {}
    with open(events) as f:
      for line in f:
        try:
          t = json.loads(line)["type"]
        except (ValueError, KeyError):
          continue  # a torn last line is ignored like a truncated frame
        counts[t] = counts.get(t, 0) + 1
    print("events   " + ", ".join("%s=%d" % kv for kv in sorted(counts.items())))
  return status


if __name__ == "__main__":
  sys.exit(main(sys.argv))
