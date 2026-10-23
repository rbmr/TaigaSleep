#!/usr/bin/env python3
"""Read a Taiga recording directory (docs/recording-format.md) using only the stdlib.

Usage: read_recording.py <session_dir>

Validates the CRC of every frame, stops each log at the first truncated or corrupt frame (the
recovery rule), and prints per-stream counts, duration, sample rate and gaps.

Exit status: 0 all logs fully valid, 1 unreadable (bad header, no meta.json), 3 a log has a
damaged tail (everything before it was still read).
"""
import json
import os
import struct
import sys
import zlib
from datetime import datetime, timezone

POLAR_EPOCH_OFFSET_NS = 946684800 * 10**9
STREAMS = {1: ("hr.log", "HR"), 2: ("ecg.log", "ECG"), 3: ("acc.log", "ACC")}
MAX_PAYLOAD = 1 << 20
CLOCK_JUMP_NS = 3600 * 10**9  # a step this big in the sample timestamps is a clock reset, not a gap
GAP_SECONDS = {1: 2.5, 2: 1.0, 3: 1.0}


def trunc_div(a, b):
  """Integer division truncating toward zero (Python's // floors)."""
  q = abs(a) // abs(b)
  return q if (a >= 0) == (b >= 0) else -q


def read_log(path):
  """Return (stream, payloads, valid_end, damaged) for one frame log.

  Frames are accepted until the first truncated or CRC-failing one; the rest is ignored.
  """
  size = os.path.getsize(path)
  payloads = []
  with open(path, "rb") as f:
    head = f.read(8)
    if len(head) < 8 or head[:4] != b"TSLG" or head[4] != 1:
      raise ValueError("bad header")
    stream = head[5]
    pos = 8
    while pos < size:
      raw = f.read(4)
      if len(raw) < 4:
        return stream, payloads, pos, True
      (n,) = struct.unpack("<I", raw)
      if n > MAX_PAYLOAD or pos + 8 + n > size:
        return stream, payloads, pos, True
      payload = f.read(n)
      (crc,) = struct.unpack("<I", f.read(4))
      if zlib.crc32(payload) & 0xFFFFFFFF != crc:
        return stream, payloads, pos, True
      payloads.append(payload)
      pos += 8 + n
  return stream, payloads, pos, False


def parse_hr(p):
  phone, elapsed, hr, flags, n_rr = struct.unpack_from("<qqHBB", p, 0)
  rr = list(struct.unpack_from("<%dH" % n_rr, p, 20))
  if len(p) != 20 + 2 * n_rr:
    raise ValueError("hr payload length mismatch")
  return {"phone_ns": phone, "elapsed_ns": elapsed, "hr": hr, "flags": flags, "rr": rr}


def parse_batch(p, per_sample, fmt):
  phone, elapsed, mode, n = struct.unpack_from("<qqBH", p, 0)
  off = 19
  if mode == 0:
    first, last = struct.unpack_from("<qq", p, off)
    off += 16
    t = [first + (trunc_div((last - first) * i, n - 1) if n > 1 else 0) for i in range(n)]
  elif mode == 1:
    t = list(struct.unpack_from("<%dq" % n, p, off))
    off += 8 * n
  else:
    raise ValueError("unknown ts_mode %d" % mode)
  values = struct.unpack_from("<%d%s" % (n * per_sample, fmt), p, off)
  off += n * per_sample * struct.calcsize(fmt)
  if off != len(p):
    raise ValueError("batch payload length mismatch")
  return {"phone_ns": phone, "elapsed_ns": elapsed, "mode": mode, "t": t, "values": values}


def fmt_time(ns):
  return datetime.fromtimestamp(ns / 1e9, timezone.utc).strftime("%H:%M:%S")


def summarize(stream, payloads):
  """Return a dict of counts, duration, rate and gaps for one stream."""
  info = {"frames": len(payloads), "samples": 0, "gaps": [], "jumps": [], "modes": {0: 0, 1: 0}}
  if not payloads:
    return info
  gap_ns = GAP_SECONDS[stream] * 1e9
  if stream == 1:
    prev = None
    first = last = None
    for p in payloads:
      s = parse_hr(p)
      info["samples"] += 1
      if first is None:
        first = s["phone_ns"]
      if prev is not None and s["phone_ns"] - prev > gap_ns:
        info["gaps"].append((prev, s["phone_ns"] - prev))
      prev = last = s["phone_ns"]
    span = last - first
  else:
    per, fmt = (1, "i") if stream == 2 else (3, "h")
    prev = None
    first = last = None
    seg_first = None
    span = 0
    for p in payloads:
      b = parse_batch(p, per, fmt)
      info["modes"][b["mode"]] += 1
      info["samples"] += len(b["t"])
      if first is None:
        first = seg_first = b["t"][0]
      step = None if prev is None else b["t"][0] - prev
      if step is not None and (step < -CLOCK_JUMP_NS or step > CLOCK_JUMP_NS):
        # The strap's clock was reset (battery pulled): count the segments separately.
        info["jumps"].append((b["phone_ns"], step))
        span += prev - seg_first
        seg_first = b["t"][0]
      elif step is not None and step > gap_ns:
        info["gaps"].append((prev + POLAR_EPOCH_OFFSET_NS, step))
      prev = last = b["t"][-1]
    span += last - seg_first
    first += POLAR_EPOCH_OFFSET_NS
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
    if stream != 1:
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
