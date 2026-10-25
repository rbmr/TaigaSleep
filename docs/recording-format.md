# Taiga recording format v1

How the Android app stores a night on the phone. Goals, in priority order:

1. **Raw.** Store what the Polar SDK hands us — same fields, same units, same
   timestamps — with no normalisation. Unit conversion and resampling are the
   backend's job.
2. **Appendable.** Every file is append-only. There is no "finalize" step, so a
   process kill leaves a valid recording. Uploading is "send bytes from offset N".
3. **Compact.** Binary frames; no per-sample timestamps when they are
   reconstructible. About 25 MB for 8 h of HR + ECG + ACC.
4. **Streamable.** A reader needs only one frame in memory at a time.

Parquet was considered and rejected for the on-device format: it writes its
footer last, so a killed recorder leaves an unreadable file, and the Android
writers pull in Hadoop-sized dependencies. The backend can convert finished
recordings to Parquet.

## Layout

```text
<getExternalFilesDir(null)>/recordings/<session_id>/
  meta.json      small; replaced atomically (write temp, rename)
  hr.log         append-only frame log, stream 1
  ecg.log        append-only frame log, stream 2
  acc.log        append-only frame log, stream 3
  events.jsonl   append-only, one JSON object per line
```

`session_id` is the local start time, `yyyyMMdd_HHmmss` (bumped by whole
seconds if that directory already exists). A stream file exists only if that
stream was enabled for the session.

## meta.json

Written at start with `state: "recording"`, rewritten on stop / force stop.
No counts, sizes or checksums (they are derivable and would need a finalize
step).

```json
{
  "format": "taiga-recording",
  "version": 1,
  "session_id": "20261023_223000",
  "state": "recording",
  "start_utc_ms": 1792798200000,
  "end_utc_ms": null,
  "timezone": "Europe/Amsterdam",
  "app": { "version": "0.1.0", "version_code": 1 },
  "phone": { "manufacturer": "OnePlus", "model": "CPH2769", "android": "16", "sdk": 36 },
  "sensor": {
    "kind": "polar-h10",
    "id": "069A4737",
    "name": "Polar H10 069A4737",
    "firmware": "3.0.35",
    "battery_percent_at_start": 87
  },
  "config": { "hr": true, "ecg": true, "acc": true, "ecg_rate_hz": 130, "acc_rate_hz": 50, "acc_range_g": 8 }
}
```

`state` is one of `recording`, `stopped` (clean stop), `forced_stop` (one-sided
stop without sensor connection), `interrupted` (found as `recording` on app
start with no live service). `end_utc_ms` is null while recording; for an
`interrupted` session it is the time of the last valid HR frame. When the OS
restarts the service and it resumes a session, the state stays (or returns to)
`recording` and a `service_restart` event is appended.

## Frame log (`*.log`)

```text
header (8 bytes):  'T' 'S' 'L' 'G'  | u8 version = 1 | u8 stream | u16 reserved = 0
frame:             u32 payload_len | payload | u32 crc32(payload)
```

All integers little-endian. `stream`: 1 = HR, 2 = ECG, 3 = ACC (further ids
reserved for future sensors, e.g. PPG/PPI).

**Recovery rule.** A reader (and the app, when it resumes appending) accepts
frames until the first one that is truncated or fails its CRC and ignores
everything after it. When the app resumes it truncates the file to the last
valid frame before appending.

Every payload starts with two clocks:

| Field | Type | Meaning |
|---|---|---|
| `phone_ns` | i64 | `System.currentTimeMillis() * 1_000_000` when the SDK callback fired (Unix epoch) |
| `elapsed_ns` | i64 | `SystemClock.elapsedRealtimeNanos()` at the same moment (monotonic, survives wall-clock changes) |

### HR payload (stream 1) — one per `PolarHrData` sample

```text
i64 phone_ns | i64 elapsed_ns | u16 hr_bpm | u8 flags | u8 n_rr | u16 rr_ms[n_rr]
flags: bit0 = contact_supported, bit1 = contact, bit2 = rr_available
```

`rr_ms` is the SDK's `rrsMs` verbatim. Values outside u16 (and RR lists longer
than 255) are clamped with an `error` event, like ACC below.

### ECG payload (stream 2) — one per SDK batch

```text
i64 phone_ns | i64 elapsed_ns | u8 ts_mode | u16 n | ts | i32 voltage_uV[n]
ts_mode 0 (linear):   i64 t_first | i64 t_last
ts_mode 1 (explicit): i64 t[n]
```

`voltage_uV` is the SDK's microvolt value verbatim. `t_*` are the SDK sample
timestamps verbatim: nanoseconds since **2000-01-01 00:00:00 UTC** (the Polar
epoch — do not convert). Unix ns = Polar ns + 946684800000000000.

The strap keeps its own clock, which is only right if the app set it: a recording with
no `clock_sync` event can be days off (an unset H10 was 2.85 days behind the phone). The
app sets the strap to UTC before its streams start and records a `clock_sync` event, and
sets it again after every reconnect (a strap that lost its battery comes back with its clock
reset, so sample timestamps can jump at a reconnect; the reader reports these as clock jumps). Readers
should still anchor absolute time on `phone_ns` (the phone's clock when the batch arrived) and
use the strap timestamps for the spacing between samples; compare the two to detect a bad clock.

Mode 0 reconstruction, for `i` in `0..n-1`:
`t[i] = t_first + ((t_last - t_first) * i) / (n - 1)` (64-bit integer division,
truncating; `n == 1` gives `t_first`). The writer must use mode 0 **only if**
this reproduces every SDK timestamp exactly; otherwise it writes mode 1. Never
lossy.

### ACC payload (stream 3) — one per SDK batch

```text
i64 phone_ns | i64 elapsed_ns | u8 ts_mode | u16 n | ts | i16 xyz_mG[n * 3]
```

Same `ts_mode` rules as ECG. `xyz_mG` is milli-g, interleaved x, y, z, verbatim
from the SDK. The writer must not clamp silently: if a value does not fit in
i16, log an `error` event and clamp.

## events.jsonl

```json
{"phone_ns": 1792798200000000000, "elapsed_ns": 123456789, "type": "start", "message": "recording started"}
```

Types in use: `start`, `stop`, `force_stop`, `connect`, `disconnect`,
`reconnect`, `service_restart`, `watchdog`, `error`, `clock_sync` (the strap's clock was
set), `stream` (a stream delivered its first sample), `contact` (the raw HR contact flag changed; the live
state applies 60 s/5 s hysteresis before it changes the notification), `ecg_signal` (the ECG was pinned at the sensor's limits, or recovered), `stream_stalled` (a
stream stopped delivering without a disconnect), `stream_recovered` (a stalled stream came
back), `battery` (the sensor battery crossed the low threshold), `storage_error` (a raw frame
or fsync failed), `readiness` (a snapshot of the battery/standby settings at start and on
every state change). Keep messages short and free of personal data.

The service owns the lifecycle lines (`start`, `stop`, `force_stop`, `readiness`,
`service_restart`); the live monitor owns `connect`, `disconnect`, `reconnect`, `contact`,
`ecg_signal`, `stream_stalled`, `stream_recovered`, `battery` and `storage_error`. Contact,
ECG quality, rates and staleness are derived only by the live monitor, never by the writer.

## Durability

- Each frame is `write()`n to the file as soon as it arrives (a process kill
  cannot lose it); `fsync`/`FileDescriptor.sync()` every 30–60 s and on stop.
- The writer never holds more than one batch in memory.
- `meta.json` is the only file that is rewritten; use write-temp + rename.

## Export / upload

Because files only grow, the uploader sends `(file, byte_offset, bytes)` and
resumes from the server's current length. The backend route is
`PUT /api/taiga/sessions/{id}/upload/file/{file}?offset=N`; see
[`backend/README.md`](../backend/README.md#recording-uploads) for the full
create/resume/complete flow.

For manual export the app can zip the session directory on demand (never stored
permanently). The backend stores the same files under
`<pb_data>/recordings/<session id>/`.
