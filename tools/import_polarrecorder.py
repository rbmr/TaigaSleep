#!/usr/bin/env python3
"""Import PolarRecorder (com.wboelens.polarrecorder) nights as Taiga recordings.

Usage: import_polarrecorder.py <source> --out DIR [--min-hours H]
                               [--timezone TZ] [--sensor-kind KIND]

<source> is a `PolarRecording_YYYYMMDD_HHMMSS` directory, or a `PolarRecorder`
folder containing several of them. Each night is written as a v1 Taiga
recording directory (docs/recording-format.md):

    <out>/<session_id>/meta.json, hr.log, ecg.log, acc.log, events.jsonl

PolarRecorder stores batches as JSONL (`HR.jsonl`, `ECG.jsonl` and
`ACC.jsonl`, optionally split into `_partN` files). Its strap timestamps are
not exactly linear, so a batch is written with explicit per-sample timestamps
(mode 1) unless the linear reconstruction reproduces every timestamp exactly
(mode 0). The session clock is derived from the phone timestamps relative to
the first sample of the session and is shared by all streams, so processing
order never moves it.

The sensor id and name come from the source's sensor directory. The phone and
app fields are generic: PolarRecorder does not identify the phone reliably,
and no personal values belong in the tool. The timezone is inferred from the
session id and start time unless --timezone is given.

Every written session is re-read and validated with the rules in
tools/read_recording.py (header, CRC, payload shape, sample counts, clock
monotonicity, events and meta). A session that fails validation is removed.

Options:
  --out DIR         output directory (required; keep it out of the repository)
  --min-hours H     skip recordings shorter than H hours (default: 0)
  --timezone TZ     meta.json timezone; default: inferred UTC offset
  --sensor-kind K   override the sensor kind derived from the directory name

Stdlib only. Existing session directories are never overwritten.

Exit status: 0 all sessions converted or skipped, 1 any session failed.
"""

import argparse
import json
import os
import re
import shutil
import struct
import sys
import zlib
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import read_recording  # noqa: E402  (sibling tool, same repository)

MAGIC = b"TSLG"
VERSION = 1
STREAM_HR, STREAM_ECG, STREAM_ACC = 1, 2, 3
MAX_PAYLOAD = 1 << 20
MAX_BATCH_SAMPLES = 0xFFFF
SESSION_PREFIX = "PolarRecording_"
STREAM_FILES = ((STREAM_HR, "HR", "hr.log"),
                (STREAM_ECG, "ECG", "ecg.log"),
                (STREAM_ACC, "ACC", "acc.log"))
# Defaults for an H10 recording; PolarRecorder's LOG line overrides them when
# it is present. The worker derives the real rates from the samples anyway.
ECG_RATE_HZ = 130
ACC_RATE_HZ = 50
ACC_RANGE_G = 2
ECG_SETTINGS = re.compile(r"ECG:\s*\[(\d+(?:\.\d+)?)\]\s*Hz")
ACC_SETTINGS = re.compile(r"ACC:\s*\[(\d+(?:\.\d+)?)\]\s*Hz")
RANGE_SETTINGS = re.compile(r"[±]\[(\d+(?:\.\d+)?)\]\s*g")


class ConvertError(Exception):
  """A session could not be converted or did not validate."""


class Skip(Exception):
  """The session has nothing to write (no samples, too short or already there)."""

  def __init__(self, message, quiet=False):
    super().__init__(message)
    self.quiet = quiet


def parts(directory, stem):
  """Return the stream's files in numeric part order (`X.jsonl`, `X_part2.jsonl`, ...)."""
  found = []
  for name in os.listdir(directory):
    match = re.fullmatch(rf"{stem}(?:_part(\d+))?\.jsonl", name)
    path = os.path.join(directory, name)
    if match and os.path.isfile(path):
      found.append((int(match.group(1) or 1), path))
  return [path for _, path in sorted(found)]


def batches(directory, stem):
  """Yield source batches, tolerating a torn last line like the app's recovery rule."""
  files = parts(directory, stem)
  for index, path in enumerate(files):
    with open(path, encoding="utf-8") as handle:
      number = 0
      while True:
        line = handle.readline()
        if not line:
          break
        number += 1
        line = line.strip()
        if not line:
          continue
        try:
          batch = json.loads(line)
        except ValueError as exc:
          remaining = handle.read()
          if index == len(files) - 1 and not remaining.strip():
            print(f"warning: {path}:{number}: ignored a torn last line", file=sys.stderr)
            return
          raise ConvertError(f"{path}:{number}: invalid JSON: {exc}") from exc
        if not isinstance(batch, dict) or "phoneTimestamp" not in batch or "data" not in batch:
          raise ConvertError(f"{path}:{number}: missing phoneTimestamp or data")
        if not isinstance(batch["data"], list):
          raise ConvertError(f"{path}:{number}: data is not a list")
        try:
          int(batch["phoneTimestamp"])
        except (TypeError, ValueError):
          raise ConvertError(f"{path}:{number}: phoneTimestamp is not an integer") from None
        yield batch


def first_phone_ns(directory, stem):
  """Phone timestamp of the stream's first batch that actually contains samples."""
  for batch in batches(directory, stem):
    if batch["data"]:
      return int(batch["phoneTimestamp"]) * 1_000_000
  return None


def last_phone_ns(directory, stem):
  """Phone timestamp of the stream's last batch that actually contains samples."""
  for path in reversed(parts(directory, stem)):
    last = None
    with open(path, encoding="utf-8") as handle:
      for line in handle:
        line = line.strip()
        if not line:
          continue
        try:
          batch = json.loads(line)
        except ValueError:
          continue
        if isinstance(batch, dict) and batch.get("data") and "phoneTimestamp" in batch:
          try:
            last = int(batch["phoneTimestamp"]) * 1_000_000
          except (TypeError, ValueError):
            continue
    if last is not None:
      return last
  return None


def trunc_div(a, b):
  """Integer division truncating toward zero, matching read_recording.py."""
  quotient = abs(a) // abs(b)
  return quotient if (a >= 0) == (b >= 0) else -quotient


def timestamps(values):
  """Return (mode, ts_bytes) for `u8 ts_mode | u16 n | ts | payload`."""
  count = len(values)
  if count <= 1:
    value = values[0]
    return 0, struct.pack("<qq", value, value)
  first, last = values[0], values[-1]
  if all(first + trunc_div((last - first) * index, count - 1) == value
         for index, value in enumerate(values)):
    return 0, struct.pack("<qq", first, last)
  return 1, struct.pack(f"<{count}q", *values)


def clamp(value, low, high):
  return max(low, min(high, int(value)))


def note_clamp(clamps, label, phone_ns):
  entry = clamps.setdefault(label, [0, phone_ns])
  entry[0] += 1


class Writer:
  def __init__(self, path, stream):
    self.handle = open(path, "wb")
    self.handle.write(MAGIC + bytes([VERSION, stream]) + b"\x00\x00")
    self.count = 0

  def write(self, payload):
    if len(payload) > MAX_PAYLOAD:
      raise ConvertError(f"frame payload is {len(payload)} bytes (max {MAX_PAYLOAD})")
    self.handle.write(struct.pack("<I", len(payload)))
    self.handle.write(payload)
    self.handle.write(struct.pack("<I", zlib.crc32(payload) & 0xFFFFFFFF))
    self.count += 1

  def __enter__(self):
    return self

  def __exit__(self, exc_type, exc, traceback):
    self.handle.close()
    return False


def sensor_info(directory, override_kind):
  """Derive the sensor identity from the source's sensor directory name."""
  name = os.path.basename(directory.rstrip(os.sep))
  tokens = name.split()
  sensor_id = tokens[-1] if tokens else name
  if override_kind:
    kind = override_kind
  elif name.lower().startswith("polar h10"):
    kind = "polar-h10"
  else:
    kind = "polar"
  return {"kind": kind, "id": sensor_id or name, "name": name, "firmware": None,
          "battery_percent_at_start": None}


def number(text):
  value = float(text)
  return int(value) if value.is_integer() else value


def source_settings(directory):
  """Read the H10 stream settings from PolarRecorder's LOG.jsonl, when present."""
  settings = {"ecg_rate_hz": ECG_RATE_HZ, "acc_rate_hz": ACC_RATE_HZ,
              "acc_range_g": ACC_RANGE_G}
  try:
    for batch in batches(directory, "LOG"):
      for item in batch["data"]:
        if not isinstance(item, dict):
          continue
        message = str(item.get("message", ""))
        if not message.startswith("Device: "):
          continue
        ecg = ECG_SETTINGS.search(message)
        acc = ACC_SETTINGS.search(message)
        acc_range = RANGE_SETTINGS.search(message)
        if ecg:
          settings["ecg_rate_hz"] = number(ecg.group(1))
        if acc:
          settings["acc_rate_hz"] = number(acc.group(1))
        if acc_range:
          settings["acc_range_g"] = number(acc_range.group(1))
        return settings
  except (ConvertError, OSError) as exc:
    print(f"warning: {exc}; using default stream settings", file=sys.stderr)
  return settings


def session_id_for(name):
  """Make a session id the API accepts (`[A-Za-z0-9_-]`, at most 40 chars)."""
  return re.sub(r"[^A-Za-z0-9_-]+", "_", name).strip("_-")[:40]


def infer_timezone(session_id, start_ms):
  """Infer the recording's UTC offset from its local session id and UTC start."""
  try:
    local_ms = int(datetime.strptime(session_id, "%Y%m%d_%H%M%S")
                   .replace(tzinfo=timezone.utc).timestamp() * 1000)
  except ValueError:
    return ""
  offset_min = round((local_ms - start_ms) / 60_000)
  if abs(offset_min) > 14 * 60:
    return ""
  if offset_min == 0:
    return "UTC"
  sign = "+" if offset_min > 0 else "-"
  hours, minutes = divmod(abs(offset_min), 60)
  return f"UTC{sign}{hours:02d}:{minutes:02d}"


def find_sensor_dir(source):
  """The single sensor directory inside a PolarRecording_* directory."""
  candidates = []
  for entry in sorted(os.listdir(source)):
    path = os.path.join(source, entry)
    if os.path.isdir(path) and any(parts(path, stem) for _, stem, _ in STREAM_FILES):
      candidates.append(path)
  if not candidates:
    raise Skip("no sensor directory with HR, ECG or ACC files", quiet=True)
  if len(candidates) > 1:
    names = ", ".join(os.path.basename(path) for path in candidates)
    raise ConvertError(f"multiple sensor directories: {names}")
  return candidates[0]


def has_samples(recording):
  """True when a recording directory contains at least one non-empty stream file."""
  for root, _, files in os.walk(recording):
    for name in files:
      if re.fullmatch(r"(?:HR|ECG|ACC)(?:_part\d+)?\.jsonl", name):
        try:
          if os.path.getsize(os.path.join(root, name)) > 0:
            return True
        except OSError:
          continue
  return False


def convert(source, out_dir, min_hours, timezone_override, sensor_kind):
  """Convert one PolarRecording_* directory and return its summary."""
  raw_session_id = os.path.basename(source.rstrip(os.sep))
  if raw_session_id.startswith(SESSION_PREFIX):
    raw_session_id = raw_session_id[len(SESSION_PREFIX):]
  session_id = session_id_for(raw_session_id)
  if not session_id:
    raise ConvertError(f"cannot derive a session id from {raw_session_id!r}")
  target = os.path.join(out_dir, session_id)
  if os.path.exists(target):
    raise Skip(f"{target} already exists")

  sensor_dir = find_sensor_dir(source)
  starts = {stream: first_phone_ns(sensor_dir, stem) for stream, stem, _ in STREAM_FILES}
  present_starts = [value for value in starts.values() if value is not None]
  if not present_starts:
    raise Skip("no HR, ECG or ACC samples", quiet=True)
  start_ns = min(present_starts)

  if min_hours > 0:
    present_ends = [value for value in (last_phone_ns(sensor_dir, stem)
                                        for _, stem, _ in STREAM_FILES)
                    if value is not None]
    hours = (max(present_ends) - start_ns) / 3.6e12
    if hours < min_hours:
      raise Skip(f"{hours:.2f} h is below --min-hours {min_hours:g}")

  try:
    os.makedirs(target)
  except FileExistsError:
    raise Skip(f"{target} already exists") from None

  try:
    counts = {}
    last_ns = {}
    clamps = {}
    for stream, stem, filename in STREAM_FILES:
      if not parts(sensor_dir, stem):
        continue
      path = os.path.join(target, filename)
      samples = 0
      with Writer(path, stream) as writer:
        for batch in batches(sensor_dir, stem):
          phone_ns = int(batch["phoneTimestamp"]) * 1_000_000
          items = batch["data"]
          if not items:
            continue
          elapsed_ns = phone_ns - start_ns
          if elapsed_ns < 0:
            raise ConvertError(f"{stem}: batch at {phone_ns} is before the session start")
          last_ns[filename] = phone_ns
          try:
            if stream == STREAM_HR:
              for sample in items:
                hr = int(sample.get("hr") or 0)
                if not 0 <= hr <= 0xFFFF:
                  note_clamp(clamps, "HR", phone_ns)
                  hr = clamp(hr, 0, 0xFFFF)
                rrs = [int(rr) for rr in (sample.get("rrsMs") or [])]
                if len(rrs) > 255:
                  note_clamp(clamps, "HR", phone_ns)
                  rrs = rrs[:255]
                for index, rr in enumerate(rrs):
                  if not 0 <= rr <= 0xFFFF:
                    note_clamp(clamps, "HR", phone_ns)
                    rrs[index] = clamp(rr, 0, 0xFFFF)
                flags = ((1 if sample.get("contactStatusSupported") else 0) |
                         (2 if sample.get("contactStatus") else 0) |
                         (4 if sample.get("rrAvailable") else 0))
                payload = (struct.pack("<qqHBB", phone_ns, elapsed_ns, hr, flags, len(rrs)) +
                           struct.pack(f"<{len(rrs)}H", *rrs))
                writer.write(payload)
                samples += 1
              continue
            if len(items) > MAX_BATCH_SAMPLES:
              raise ConvertError(f"{stem}: batch has {len(items)} samples "
                                 f"(max {MAX_BATCH_SAMPLES})")
            ts = [int(sample["timeStamp"]) for sample in items]
            mode, ts_bytes = timestamps(ts)
            if stream == STREAM_ECG:
              values = []
              for sample in items:
                voltage = int(sample["voltage"])
                if not -(2**31) <= voltage <= 2**31 - 1:
                  note_clamp(clamps, "ECG", phone_ns)
                  voltage = clamp(voltage, -(2**31), 2**31 - 1)
                values.append(voltage)
              payload = (struct.pack("<qq", phone_ns, elapsed_ns) +
                         struct.pack("<BH", mode, len(ts)) + ts_bytes +
                         struct.pack(f"<{len(values)}i", *values))
            else:
              values = []
              for sample in items:
                for axis in ("x", "y", "z"):
                  value = int(sample[axis])
                  if not -32768 <= value <= 32767:
                    note_clamp(clamps, "ACC", phone_ns)
                    value = clamp(value, -32768, 32767)
                  values.append(value)
              payload = (struct.pack("<qq", phone_ns, elapsed_ns) +
                         struct.pack("<BH", mode, len(ts)) + ts_bytes +
                         struct.pack(f"<{len(values)}h", *values))
            writer.write(payload)
            samples += len(items)
          except (KeyError, TypeError, ValueError, struct.error) as exc:
            raise ConvertError(f"{stem}: invalid batch at {phone_ns}: {exc}") from exc
      if samples == 0:
        os.remove(path)
        continue
      counts[filename] = samples

    if not counts:
      raise Skip("no HR, ECG or ACC samples", quiet=True)
    end_ns = max(last_ns.values())
    if end_ns < start_ns:
      raise ConvertError("the last sample is before the session start")

    start_ms = start_ns // 1_000_000
    end_ms = end_ns // 1_000_000
    timezone_value = timezone_override
    if timezone_value is None:
      timezone_value = infer_timezone(session_id, start_ms)
    settings = source_settings(sensor_dir)
    meta = {
        "format": "taiga-recording",
        "version": 1,
        "session_id": session_id,
        "state": "stopped",
        "start_utc_ms": start_ms,
        "end_utc_ms": end_ms,
        "timezone": timezone_value,
        "app": {"version": "polarrecorder-import", "version_code": 0},
        "phone": {"manufacturer": "", "model": "", "android": "", "sdk": 0},
        "sensor": sensor_info(sensor_dir, sensor_kind),
        "config": {"hr": "hr.log" in counts, "ecg": "ecg.log" in counts,
                   "acc": "acc.log" in counts,
                   "ecg_rate_hz": settings["ecg_rate_hz"],
                   "acc_rate_hz": settings["acc_rate_hz"],
                   "acc_range_g": settings["acc_range_g"]},
    }
    meta_path = os.path.join(target, "meta.json")
    with open(meta_path + ".tmp", "w", encoding="utf-8") as handle:
      json.dump(meta, handle, indent=2)
      handle.write("\n")
    os.replace(meta_path + ".tmp", meta_path)

    events = [{"phone_ns": start_ns, "elapsed_ns": 0, "type": "start",
               "message": "imported from PolarRecorder"}]
    for stream, stem, filename in STREAM_FILES:
      if filename in counts and starts[stream] is not None:
        first = starts[stream]
        events.append({"phone_ns": first, "elapsed_ns": first - start_ns,
                       "type": "stream", "message": f"{stem.lower()} streaming"})
    for label, (count, phone_ns) in clamps.items():
      events.append({"phone_ns": phone_ns, "elapsed_ns": phone_ns - start_ns,
                     "type": "error",
                     "message": f"{label} values out of range clamped ({count})"})
    events.append({"phone_ns": end_ns, "elapsed_ns": end_ns - start_ns,
                   "type": "stop", "message": "imported from PolarRecorder"})
    events.sort(key=lambda event: event["phone_ns"])
    with open(os.path.join(target, "events.jsonl"), "w", encoding="utf-8") as handle:
      for event in events:
        handle.write(json.dumps(event, separators=(",", ":")) + "\n")

    report = validate(target, counts, start_ns, end_ns)
  except Exception:
    shutil.rmtree(target, ignore_errors=True)
    raise
  return session_id, target, start_ns, end_ns, counts, report


def validate(directory, expected, start_ns, end_ns):
  """Re-read every written file using the same rules as tools/read_recording.py."""
  report = {}
  for stream, stem, filename in STREAM_FILES:
    path = os.path.join(directory, filename)
    if filename not in expected:
      if os.path.exists(path):
        raise ConvertError(f"{filename}: unexpected file")
      continue
    try:
      actual_stream, payloads, valid_end, damaged = read_recording.read_log(path)
    except (OSError, ValueError, struct.error) as exc:
      raise ConvertError(f"{filename}: {exc}") from exc
    if actual_stream != stream:
      raise ConvertError(f"{filename}: stream id {actual_stream} (expected {stream})")
    if damaged:
      raise ConvertError(f"{filename}: damaged tail at byte {valid_end}")
    size = os.path.getsize(path)
    if valid_end != size:
      raise ConvertError(f"{filename}: read {valid_end} of {size} bytes")
    previous_elapsed = -1
    try:
      for payload in payloads:
        if stream == STREAM_HR:
          elapsed_ns = read_recording.parse_hr(payload)["elapsed_ns"]
        else:
          per_sample, fmt = (1, "i") if stream == STREAM_ECG else (3, "h")
          batch = read_recording.parse_batch(payload, per_sample, fmt)
          elapsed_ns = batch["elapsed_ns"]
          samples = batch["t"]
          if any(later < earlier for earlier, later in zip(samples, samples[1:])):
            raise ConvertError(f"{filename}: sample timestamps go backwards")
        if elapsed_ns < 0:
          raise ConvertError(f"{filename}: negative elapsed clock")
        if elapsed_ns < previous_elapsed:
          raise ConvertError(f"{filename}: elapsed clock went backwards")
        previous_elapsed = elapsed_ns
      info = read_recording.summarize(stream, payloads)
    except (ValueError, struct.error) as exc:
      raise ConvertError(f"{filename}: {exc}") from exc
    if info["samples"] != expected[filename]:
      raise ConvertError(f"{filename}: expected {expected[filename]} samples, "
                         f"read {info['samples']}")
    report[filename] = info

  events_path = os.path.join(directory, "events.jsonl")
  if not os.path.exists(events_path):
    raise ConvertError("events.jsonl is missing")
  previous_phone = None
  event_types = []
  with open(events_path, encoding="utf-8") as handle:
    for number, line in enumerate(handle, 1):
      line = line.strip()
      if not line:
        continue
      try:
        event = json.loads(line)
      except ValueError as exc:
        raise ConvertError(f"events.jsonl:{number}: invalid JSON: {exc}") from exc
      if not isinstance(event, dict):
        raise ConvertError(f"events.jsonl:{number}: not an object")
      for key in ("phone_ns", "elapsed_ns", "type", "message"):
        if key not in event:
          raise ConvertError(f"events.jsonl:{number}: missing {key}")
      if not isinstance(event["phone_ns"], int) or not isinstance(event["elapsed_ns"], int):
        raise ConvertError(f"events.jsonl:{number}: phone_ns and elapsed_ns must be integers")
      if not isinstance(event["type"], str) or not isinstance(event["message"], str):
        raise ConvertError(f"events.jsonl:{number}: type and message must be strings")
      if event["elapsed_ns"] < 0:
        raise ConvertError(f"events.jsonl:{number}: negative elapsed_ns")
      if previous_phone is not None and event["phone_ns"] < previous_phone:
        raise ConvertError(f"events.jsonl:{number}: clock went backwards")
      previous_phone = event["phone_ns"]
      event_types.append(event["type"])
  if not event_types or event_types[0] != "start" or event_types[-1] != "stop":
    raise ConvertError("events.jsonl: must begin with start and end with stop")

  meta_path = os.path.join(directory, "meta.json")
  try:
    with open(meta_path, encoding="utf-8") as handle:
      meta = json.load(handle)
  except (OSError, ValueError) as exc:
    raise ConvertError(f"meta.json: {exc}") from exc
  for key in ("format", "version", "session_id", "state", "start_utc_ms", "end_utc_ms",
              "timezone", "app", "phone", "sensor", "config"):
    if key not in meta:
      raise ConvertError(f"meta.json: missing {key}")
  if meta["format"] != "taiga-recording" or meta["version"] != 1:
    raise ConvertError("meta.json: unsupported format or version")
  if meta["session_id"] != os.path.basename(directory):
    raise ConvertError("meta.json: session_id does not match the directory name")
  if meta["state"] != "stopped":
    raise ConvertError("meta.json: state must be stopped")
  if meta["start_utc_ms"] != start_ns // 1_000_000:
    raise ConvertError("meta.json: start_utc_ms does not match the first sample")
  if meta["end_utc_ms"] != end_ns // 1_000_000:
    raise ConvertError("meta.json: end_utc_ms does not match the last sample")
  if meta["start_utc_ms"] <= 0:
    raise ConvertError("meta.json: start_utc_ms is not a valid Unix timestamp")
  if not isinstance(meta["timezone"], str):
    raise ConvertError("meta.json: timezone must be a string")
  if not isinstance(meta["app"], dict) or not isinstance(meta["phone"], dict):
    raise ConvertError("meta.json: app and phone must be objects")
  sensor = meta["sensor"]
  if not isinstance(sensor, dict) or not sensor.get("kind") or not sensor.get("id") \
      or not sensor.get("name"):
    raise ConvertError("meta.json: sensor.kind, sensor.id and sensor.name are required")
  config = meta["config"]
  if not isinstance(config, dict):
    raise ConvertError("meta.json: config must be an object")
  for key in ("hr", "ecg", "acc"):
    if not isinstance(config.get(key), bool):
      raise ConvertError(f"meta.json: config.{key} must be a boolean")
  if config["hr"] != ("hr.log" in expected) or config["ecg"] != ("ecg.log" in expected) \
      or config["acc"] != ("acc.log" in expected):
    raise ConvertError("meta.json: stream flags do not match the written files")
  for key in ("ecg_rate_hz", "acc_rate_hz"):
    if not isinstance(config.get(key), (int, float)) or config[key] <= 0:
      raise ConvertError(f"meta.json: config.{key} must be a positive number")
  return report


def format_duration(seconds):
  seconds = int(round(seconds))
  hours, remainder = divmod(seconds, 3600)
  minutes, secs = divmod(remainder, 60)
  if hours:
    return f"{hours}h{minutes:02d}m"
  if minutes:
    return f"{minutes}m{secs:02d}s"
  return f"{secs}s"


def print_summary(session_id, target, start_ns, end_ns, counts, report):
  streams = []
  for stream, stem, filename in STREAM_FILES:
    if filename not in counts:
      continue
    text = f"{stem} {counts[filename]}"
    info = report.get(filename)
    if info and info["samples"] > 1:
      text += f" ({info['rate_hz']:.2f} Hz)"
    streams.append(text)
  duration = (end_ns - start_ns) / 1e9
  print(f"{session_id}  {format_duration(duration)}  " + "  ".join(streams) + f"  -> {target}")
  for filename, info in report.items():
    if info["gaps"]:
      print(f"  {filename}: {len(info['gaps'])} gap(s)")
    if info["jumps"]:
      print(f"  {filename}: {len(info['jumps'])} clock jump(s)")


def main(argv=None):
  parser = argparse.ArgumentParser(
      description="Import PolarRecorder nights as Taiga recording directories.",
      epilog="See docs/recording-format.md for the output format.")
  parser.add_argument("source", help="PolarRecording_* directory or a PolarRecorder folder")
  parser.add_argument("--out", required=True,
                      help="output directory (keep it out of the repository)")
  parser.add_argument("--min-hours", type=float, default=0,
                      help="skip recordings shorter than this many hours (default: 0)")
  parser.add_argument("--timezone", default=None,
                      help="meta.json timezone (default: inferred UTC offset)")
  parser.add_argument("--sensor-kind", default=None,
                      help="override the sensor kind derived from the directory name")
  args = parser.parse_args(argv)

  source = os.path.abspath(args.source)
  if not os.path.isdir(source):
    parser.error(f"not a directory: {source}")
  out_dir = os.path.abspath(args.out)
  try:
    os.makedirs(out_dir, exist_ok=True)
  except OSError as exc:
    parser.error(f"cannot create {out_dir}: {exc}")

  if os.path.basename(source.rstrip(os.sep)).startswith(SESSION_PREFIX):
    candidates = [source]
    single = True
  else:
    single = False
    candidates = sorted(os.path.join(source, entry) for entry in os.listdir(source)
                        if entry.startswith(SESSION_PREFIX) and
                        os.path.isdir(os.path.join(source, entry)))
    if not candidates:
      parser.error(f"no {SESSION_PREFIX}* directories in {source}")

  converted = skipped = failed = 0
  if not single:
    with_samples = [candidate for candidate in candidates if has_samples(candidate)]
    skipped += len(candidates) - len(with_samples)
    candidates = with_samples

  for candidate in candidates:
    try:
      result = convert(candidate, out_dir, args.min_hours, args.timezone, args.sensor_kind)
    except Skip as exc:
      skipped += 1
      if single or not exc.quiet:
        print(f"skip {os.path.basename(candidate)}: {exc}", file=sys.stderr)
      continue
    except (ConvertError, OSError) as exc:
      failed += 1
      print(f"error {os.path.basename(candidate)}: {exc}", file=sys.stderr)
      continue
    converted += 1
    print_summary(*result)

  if not single or converted + skipped + failed > 1:
    print(f"converted {converted}, skipped {skipped}, failed {failed}")
  return 1 if failed else 0


if __name__ == "__main__":
  raise SystemExit(main())
