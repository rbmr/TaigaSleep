# Taiga recording format v1

How the Android app stores a night on the phone. Goals, in priority order:

1. **Raw.** Store what the Polar SDK hands us — same fields, same units, same
   timestamps — with no normalisation. Unit conversion and resampling are the
   backend's job.
2. **Appendable.** Every file is append-only. There is no "finalize" step, so a
   process kill leaves a valid recording. Uploading is "send bytes from offset N".
3. **Compact.** Binary frames; no per-sample timestamps when they are
   reconstructible. About 20 MB for 8 h of HR + ECG + ACC.
4. **Streamable.** A reader needs only one frame in memory at a time.

Parquet was considered and rejected for the on-device format: it writes its
footer last, so a killed recorder leaves an unreadable file, and the Android
writers pull in Hadoop-sized dependencies. The backend can convert finished
recordings to Parquet.

## Layout

```text
<getExternalFilesDir(null)>/recordings/<session_id>/
  meta.json      small; replaced atomically (write temp, rename)
  hr.log         append-only frame log (the H10's and a heart-rate strap's beats)
  ecg.log        append-only frame log
  acc.log        append-only frame log
  ppg.log        append-only frame log, when the tracker offers PPG
  ppi.log        append-only frame log, when the tracker offers pulse intervals
  gyro.log       append-only frame log, when the tracker offers a gyroscope
  mag.log        append-only frame log, when the tracker offers a magnetometer
  events.jsonl   append-only, one JSON object per line
```

The file names and the streams they hold come from the recording's `streams` list, so a
new tracker needs no change here beyond the codec of what it records. The H10 and every
recording made before streams were listed keep the names above.

The upload state of a recording is not part of its directory: it lives in the app's
database, one row per recording and account.

`session_id` is the local start time, `yyyyMMdd_HHmmss`, a dash and four random
lowercase letters or digits (`20261023_223000-k3f9`). The server stores the
recording under the same id, and ids are unique across all accounts there, so the
suffix keeps two phones that start in the same second apart; an id that already
exists as a directory is drawn again. Recordings made before the suffix was added
keep their plain `yyyyMMdd_HHmmss` id. A stream file exists only if that stream
was enabled for the session.

## meta.json

Written at start with `state: "recording"`, rewritten on stop / force stop.
No counts, sizes or checksums (they are derivable and would need a finalize
step).

```json
{
  "format": "taiga-recording",
  "version": 1,
  "session_id": "20261023_223000-k3f9",
  "state": "recording",
  "mode": "stream",
  "start_utc_ms": 1792798200000,
  "end_utc_ms": null,
  "timezone": "Europe/Amsterdam",
  "app": { "version": "0.2.0", "version_code": 2000 },
  "phone": { "manufacturer": "OnePlus", "model": "CPH2769", "android": "16", "sdk": 36 },
  "sensor": {
    "kind": "polar-h10",
    "id": "069A4737",
    "name": "Polar H10 069A4737",
    "firmware": "3.0.35",
    "battery_percent_at_start": 87
  },
  "config": { "hr": true, "ecg": true, "acc": true, "ecg_rate_hz": 130, "acc_rate_hz": 50, "acc_range_g": 8 },
  "streams": [
    { "id": "hr", "type": "heart_rate", "codec": "polar-hr-v1", "file": "hr.log", "rate_hz": 1.0, "settings": {} },
    { "id": "rr", "type": "rr_interval", "codec": "polar-hr-v1", "file": "hr.log", "settings": {} },
    { "id": "ecg", "type": "ecg", "codec": "polar-ecg-v1", "file": "ecg.log", "rate_hz": 130.0,
      "settings": { "resolution_bits": "14" } },
    { "id": "acc", "type": "accelerometer", "codec": "polar-acc-v1", "file": "acc.log", "rate_hz": 50.0,
      "settings": { "range_g": "8" } }
  ]
}
```

`streams` is the recording's own list: one entry per stream it holds, with the id the
tracker's driver uses, the type of data, the payload codec, the file, the rate and the
settings the tracker actually negotiated. A recording written before this key existed
has no `streams`; its streams are the ones its `config` names, with the codecs below.
Two entries may name the same file when one payload carries both, as heart rate and R-R
do. `config` stays for an older reader: it says whether the three original streams were
on, and it is not what the writer, the uploader or the monitor read.

Stream types are `heart_rate`, `rr_interval`, `ecg`, `accelerometer`, `ppg`,
`pulse_interval`, `gyroscope` and `magnetometer`.

`state` is one of `recording`, `stopped` (clean stop), `forced_stop` (one-sided
stop without sensor connection), `interrupted` (found as `recording` on app
start with no live marker and no live service, or ended because the phone refused to keep the
service in the foreground; never a recording in `on_device` mode, which stays `recording`
until it is stopped, discarded or force stopped), or `fetch_pending` (the tracker
still holds part of the night and a fetch must be retried). `mode` is `stream` (data
arrived while recording) or `on_device` (the strap stored it and the phone fetched it
at the end); a recording written before modes existed has no key and means `stream`.

The Polar H10 can record HR/RR on itself when the configuration is HR-only. In
`on_device` mode the `hr.log` file is written from the fetched samples when the
recording stops, so it still holds the same frames as a streamed HR-only night. The
strap stores beat intervals; each becomes one frame with the heart rate derived from it,
timed forward from that exercise's own start time, with each RR interval added in
order. An RR interval of 0 or less produces no frame, so it stays a gap, and the
gap between two exercises stays a gap too. The app sets the strap's clock before an
on-device recording starts and records a `clock_sync` event, so the exercise start is
real time; it still anchors absolute time to the phone's clock where it can.

An `on_device` recording has one extra metadata object:

```json
"on_device": {
  "fetch_pending": false,
  "imported_entries": { "exercise-id": 1234 },
  "import_started": "exercise-id",
  "import_start": { "1": { "bytes": 8192, "frames": 128 } },
  "tracker_removal_pending": ["exercise-id"],
  "tracker_removal_failures": 0
}
```

`fetch_pending` is written before the fetch starts, so a process kill during the
fetch is found again after a restart. `imported_entries` maps each tracker entry id
to how many of its samples are already on the phone; a retry imports the rest and
never duplicates an entry. Before an entry is appended, `import_started` names it and
`import_start` holds the byte and frame position of each stream log; if the process
dies mid-entry, the next fetch truncates back to that position and imports the entry
once, and if it cannot, the fetch is refused rather than importing the entry twice.
Both fields are cleared when the entry is marked imported. The tracker's copy is
removed only after the imported frames are flushed and fsynced and the imported sample
count equals the fetched one. A recording without this object has nothing on the
tracker.

`tracker_removal_pending` names the tracker entries the phone has stored but not
removed yet. It is written before the recording is finished, so a process that dies
between finishing and removing finds the removal again; the state is `stopped` by
then, and the removal is retried the next time the tracker is connected.
`tracker_removal_failures` counts the connections in a row on which it failed, and a
removal is recorded as an error event only once it has kept failing.

`end_utc_ms` is null while recording; for an `interrupted` session it is the time of
the last valid HR frame. When the OS restarts the service and it resumes a session,
the state stays (or returns to) `recording` and a `service_restart` event is appended.

## Frame log (`*.log`)

```text
header (8 bytes):  'T' 'S' 'L' 'G'  | u8 version = 1 | u8 stream | u16 reserved = 0
frame:             u32 payload_len | payload | u32 crc32(payload)
```

All integers little-endian. `stream`: 1 = HR, 2 = ECG, 3 = ACC, 4 = PPG, 5 = PPI,
6 = GYRO, 7 = MAG. A codec decides how a payload of that stream is laid out; the file
name does not have to be one of the three original names.

**Recovery rule.** A write that fails is rolled back to the end of the last whole frame, so the
next frame is appended after good data and the recording goes on. The lost frame is counted in the
recording state and logged as an `error` event. A reader takes a frame only when its length fits,
its CRC matches and its payload decodes as that stream's own layout, so random bytes cannot pass
for a frame. A region that holds no such frame is skipped to the next offset that does, so one bad
frame does not hide the rest of the file; this holds for the HR, ECG and ACC logs alike. Every
skipped byte range is reported as a gap by whatever reads the log, appears as a warning in the
analysis result, and marks the time it covered as missing in the input quality, so downstream steps
treat it as a gap and not as a continuous signal. When the app resumes it truncates the file to the
end of the last whole frame before appending, which keeps every frame after a damaged region in the
middle. The worker's check of a downloaded log leaves damage in the middle in place for the decoder
to step over, and cuts back a damaged tail as before, so the stream ends at its last whole frame.

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

The ECG payload layout is `polar-ecg-v2`. Its `ts_mode` byte holds two things: bit 0 selects
the timestamp form, bit 1 says whether the samples are 16-bit or 32-bit integers.

```text
i64 phone_ns | i64 elapsed_ns | u8 ts_mode | u16 n | ts | voltage_uV[n]
ts_mode bit 0 clear (0): i64 t_first | i64 t_last
ts_mode bit 0 set   (1): i64 t[n]
ts_mode bit 1 clear:     i32 voltage_uV[n]
ts_mode bit 1 set:       i16 voltage_uV[n]
```

`voltage_uV` is the SDK's microvolt value verbatim. A batch is stored with 16-bit samples when
every value in it fits in i16, and with 32-bit samples when one does not; nothing is clamped,
so both forms are lossless. The choice is flagged in the batch, so a log may hold batches of
either width and the reader needs no guess. A batch without the width bit is byte-for-byte the
`polar-ecg-v1` layout, which is what every recording made before the flag existed is, so those
recordings decode unchanged. The H10 delivers values within about ±18 mV, far inside i16.

`t_*` are the SDK sample timestamps verbatim: nanoseconds since **2000-01-01 00:00:00 UTC**
(the Polar epoch — do not convert). Unix ns = Polar ns + 946684800000000000.

The strap keeps its own clock, which is only right if the app set it: a recording with
no `clock_sync` event can be days off (an unset H10 was 2.85 days behind the phone). The
app sets the strap to UTC before its streams start and records a `clock_sync` event, and
sets it again after every reconnect (a strap that lost its battery comes back with its clock
reset, so sample timestamps can jump at a reconnect; the reader reports these as clock jumps). Readers
should still anchor absolute time on `phone_ns` (the phone's clock when the batch arrived) and
use the strap timestamps for the spacing between samples; compare the two to detect a bad clock.

Linear (bit 0 clear) reconstruction, for `i` in `0..n-1`:
`t[i] = t_first + ((t_last - t_first) * i) / (n - 1)` (64-bit integer division,
truncating; `n == 1` gives `t_first`). The writer must use it **only if**
this reproduces every SDK timestamp exactly; otherwise it writes explicit timestamps. Never
lossy.

### ACC payload (stream 3) — one per SDK batch

```text
i64 phone_ns | i64 elapsed_ns | u8 ts_mode | u16 n | ts | i16 xyz_mG[n * 3]
```

The timestamp part of `ts_mode` follows the ECG rules. ACC values are always i16 and carry no
width bit, so an ACC batch uses only the low bit of `ts_mode`. `xyz_mG` is milli-g,
interleaved x, y, z, verbatim from the SDK. The writer must not clamp silently: if a value
does not fit in i16, log an `error` event and clamp.

### A standard heart-rate strap (`ble-hrs-v1`)

The Bluetooth Heart Rate Service's Measurement characteristic (0x2A37) is stored as
received, with the two clocks in front so a frame can be placed on the recording:

```text
i64 phone_ns | i64 elapsed_ns | u16 n | u8 measurement[n]
```

`measurement` is the characteristic value verbatim: flags, the 8- or 16-bit heart rate,
the optional energy expended and the optional R-R intervals in units of 1/1024 s. The
app and the worker share the decoder and its fixtures (`shared/codec-fixtures`), so every
flag combination reads the same way in both.

### PPG (`polar-ppg-v1`)

```text
i64 phone_ns | i64 elapsed_ns | u16 n
per sample: i64 t_first | i32 channel | i16 status_bits
```

### Pulse intervals (`polar-ppi-v1`)

```text
i64 phone_ns | i64 elapsed_ns | u16 n
per sample: i64 t_polar | u16 ppi_ms | u16 error_ms | u8 hr_bpm | u8 flags
```

`flags`: bit0 = skin contact supported, bit1 = skin contact, bit2 = blocker bit.

### Gyroscope and magnetometer (`polar-gyro-v1`, `polar-mag-v1`)

```text
i64 phone_ns | i64 elapsed_ns | u16 n
per sample: i64 t_polar | f32 x | f32 y | f32 z
```

The values are the SDK's, in the unit it reports for that stream; no conversion is done
on the phone.

## events.jsonl

```json
{"phone_ns": 1792798200000000000, "elapsed_ns": 123456789, "type": "start", "message": "recording started"}
```

Types in use: `start`, `stop`, `force_stop`, `connect`, `disconnect`,
`reconnect`, `service_restart`, `watchdog`, `error`, `clock_sync` (the strap's clock was
set), `stream` (a stream delivered its first sample), `contact` (the raw HR contact flag changed; the live
state applies 60 s/5 s hysteresis before it changes the notification), `ecg_signal` (the ECG was pinned at the sensor's limits, or recovered), `stream_stalled` (a
stream stopped delivering without a disconnect), `stream_recovered` (a stalled stream came
back), `recovery` (a stream that stopped delivering was restarted, or the link was made again,
with the reason in the message), `battery` (the sensor battery crossed the low threshold), `storage_error` (a raw frame
or fsync failed), `storage_low` (free space fell below an hour of the selected streams),
`storage_ok` (a failed write was followed by a good one, or free space came back above the
threshold), `fetch` (an on-device fetch was retried), `readiness` (a snapshot of the battery/standby
settings at start and on every state change), `wake` (the smart alarm was armed, moved or
switched off, with its window), `wake_rang` (the smart alarm rang, with why). Keep messages short
and free of personal data.

The service owns the lifecycle lines (`start`, `stop`, `force_stop`, `readiness`,
`service_restart`); the live monitor owns `connect`, `disconnect`, `reconnect`, `contact`,
`ecg_signal`, `stream_stalled`, `stream_recovered`, `recovery`, `battery` and `storage_error`. Contact,
ECG quality, rates and staleness are derived only by the live monitor, never by the writer.

## Durability

- Each frame is `write()`n to the file as soon as it arrives (a process kill
  cannot lose it); `fsync`/`FileDescriptor.sync()` every 30–60 s and on stop.
- The writer never holds more than one batch in memory.
- `meta.json` is the only file that is rewritten; use write-temp + rename.

## Export / upload

Because files only grow, the uploader sends `(file, byte_offset, bytes)` and
resumes from the server's current length. The recording is created with
`POST /api/collections/recordings/records` and a body of
`{"id": "<session_id>", "meta": <meta.json>}`, each file is appended with
`PATCH /v1/recordings/{session_id}/files/{name}` and an `Upload-Offset` header, and
`PATCH /api/collections/recordings/records/{session_id}` with
`{"complete": true}` ends the upload. `meta.json` is not uploaded as a file. See
[`api.md`](api.md) for the full contract.

For manual export the app zips the chosen contents of the session directory on demand
(never stored permanently): the stream logs go in as stored entries, the metadata is
deflated. The backend stores the same files under `<pb_data>/recordings/<recording id>/`;
`meta.json` is kept in the recording itself.

Every stream file the recording's `streams` list names is uploaded; the backend stores
the names it is given and does not know their codecs.

The upload can optionally compress the stream and event files with one gzip
member per chunk; the server then stores `hr.log.gz` and similar names. The
local recording format described in this document never changes. See
[`upload-compression.md`](upload-compression.md) for the protocol.

## Importing PolarRecorder sessions

Recordings from PolarRecorder (`com.wboelens.polarrecorder`, JSONL batches per
stream) can be converted into this layout with
[`tools/import_polarrecorder.py`](../tools/import_polarrecorder.py):

```bash
uv run --project backend/worker --frozen python tools/import_polarrecorder.py \
  /sdcard/polar/PolarRecorder --out "$HOME/taiga-imported" --min-hours 3
```

The tool reads and writes the frame format through the worker's
`taiga_worker.frames` module, so it runs in the worker environment; see
[`tools/README.md`](../tools/README.md). It writes one session directory per recording
(named after the source's `PolarRecording_*` timestamp), validates every frame it wrote,
and never overwrites an existing session. Empty sessions are skipped automatically and
`--min-hours` also skips short test recordings. The sensor id and name come from the
source's device directory; the phone and app fields stay generic because PolarRecorder
does not record them reliably. Copy the result into the app's `recordings` directory (or
upload it) to get the nights into the history.
