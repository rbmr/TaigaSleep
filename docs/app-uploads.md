# Recording uploads and results in the app

This document describes the app side of issue #11: how a local recording reaches
the optional backend, how its state is stored, and what happens to the files
afterwards. Recording itself is unchanged and never depends on the network.

## Local-first

Every night starts and stays as a normal local recording under
`<external files>/recordings/<session id>/`. History reads the local recordings
and a cached remote summary, so a logged-out or offline user still sees every
night that exists on the phone. Uploading is an extra step attached to that
local record; it never moves or rewrites the recording.

There is no local sleep analysis. The phone records and talks to the backend;
sleep stages, the sleep score, heart-rate statistics, data quality and HRV come
from the server.

## Automatic upload

Automatic upload is a master checkbox with two optional restrictions:

| Setting | Effect |
| --- | --- |
| Upload automatically | Off means no automatic uploads; manual upload still works. |
| Only on Wi-Fi | Required unmetered network. Off allows any connected network. |
| Only while charging | Requires the phone to be charging. |
| Compress uploads | Send stream and event files as gzip. On by default; manual uploads use it too. |

The default is **enabled, Wi-Fi only, not requiring charging**, with compression
**on**. Results are downloaded whenever the server is reachable, independently
of these upload restrictions. A night can contain hundreds of megabytes of ECG
and accelerometer data, so the default avoids cellular data.

Compression is on by default because it roughly halves the bytes sent and kept
on the server, and on most home or mobile uplinks that is faster than sending
the raw stream. On a very fast local server it costs a few seconds of phone CPU
for no network gain; that trade-off is the reason the setting exists.

## Compressed uploads

Compression applies to the upload and the server-side copy only. The recording
under `recordings/<session id>/` stays append-only and uncompressed, and the
analysis input is byte-for-byte the same after decompression.

The uploader sends one complete gzip member per 2 MiB chunk, appends the members
to `<file>.gz` on the server, and keeps a small boundary index under
`<files>/upload-cache/<local session id>/` so the server's compressed offset can
be mapped back to the local file after a kill. The cache is deleted when the
upload completes. A night whose upload already started compressed continues in
gzip even if the setting is later turned off; a night that started plain stays
plain, because a session's encoding cannot change.

Frame validation moves to the worker in this mode: the API only checks gzip
framing, the worker inflates with size/member/ratio limits, validates frame
headers and CRCs, keeps the valid prefix and records the compression ratio in
the analysis provenance. Full details and the measured trade-off are in
[`upload-compression.md`](upload-compression.md).

## Upload lifecycle

History shows one of these states per recording:

- **Only on this phone** — no upload has been requested.
- **Waiting to upload** — queued in WorkManager, or waiting for its network and
  charging constraints.
- **Uploading N%** — bytes are moving. The notification keeps the transfer alive
  while the app is in the background.
- **Waiting for analysis** — the upload completed and the analysis job is queued
  or running on a worker.
- **Sleep score / done** — a result is available. The row also shows whether the
  full result has been downloaded; the app fetches missing results as soon as it
  can.
- **Failed** — login, network, server or analysis failure. The error is shown and
  Retry is available.

WorkManager uses one unique work item per local session. An upload is resumable:
before sending bytes the worker asks the server for the current length of each
file and continues from that offset. A conflict (`409`, another attempt moved the
offset) re-reads the manifest and retries. Force-stopping the app or losing the
network therefore does not restart a night from zero.

Analysis polling is a separate work item. It keeps the transfer notification
closed, backs off exponentially while the queue is empty, and stops when the
analysis is done, failed or the user logs out. History also proactively downloads
any finished result that is not in the local cache yet.

## Night detail

A finished night reads as three sections, in this order:

- **Sleep** — the sleep-stages chart and the stage distribution with time and
  percentage per stage, followed by recording length, sleep efficiency, total
  sleep, time to fall asleep, awakenings and WASO. An awakening is a wake
  period longer than two minutes.
- **Heart rate** — average, lowest and highest heart rate and HRV RMSSD and
  SDNN, plus a heart-rate chart built from the cached epoch series. Gaps in the
  series stay gaps and are never interpolated.
- **Data quality** — the data-quality score and its breakdown, with scored and
  unscored time.

The sleep score is the prominent score in History and in the night header. The
data-quality score is separate and explicitly labelled: it describes the
recording and the analysis, not how well the night was slept. A movement block
follows for results that contain accelerometer figures.

Both time charts draw an hourly grid with `HH:mm` labels, keep the plot above
the grid, and support touch scrubbing: pressing a chart shows the time and the
value at that point in a tooltip away from the finger. The expand button opens
the chart in landscape full screen; close returns to the night.

A result cached before these fields existed still loads. The heart-rate chart is
hidden, and the sleep score shows as unavailable instead of reusing the
data-quality value.

## Stager selection

The app owns an ordered preference list (`wav2sleep`, `sleep-ecg`, `baseline`).
After an upload it fetches the installed algorithms and their stream
requirements from the backend, filters out those the recording cannot feed, and
requests the first compatible id and version. wav2sleep is the default for
recordings with ECG; SleepECG is the best HR-only option; `baseline` is the safe
fallback when no worker has reported. The backend still requires an explicit
model and version.

Automatic analysis is on by default. Turning it off stores the night on the
server without requesting a result; a manual request can then ask for one or
several compatible algorithms. The backend removes duplicates by model, version
and parameter hash, so asking twice does not create a second row. The settings
screen for the automatic flag, the order and the analysis sheet is #32.

## Recording states

- **Stopped** and **force-stopped** recordings upload immediately.
- A recording that was killed mid-night is marked **interrupted** on the next app
  start and then uploads like any other finished night.
- A recording that is still live is never uploaded. The `RecordingService`
  enqueues it when it finishes; uploading an open file would race the writer.
- The backend's `meta.json` state must be non-`recording` before completion. The
  worker closes a crashed session with the last frame time, the same rule the app
  uses on startup.

## Local retention

Raw stream files (`hr.log`, `ecg.log`, `acc.log`) are the large part of a
recording. **Settings → Recordings → Raw recordings** chooses when they may leave
the phone:

| Setting | Effect |
| --- | --- |
| Keep raw data on this phone | Default. Raw files are never removed automatically. |
| Delete raw data straight after upload | Removed as soon as the server confirms the upload. |
| Delete raw data after 5 / 14 / 30 days | Removed once that many days have passed since the confirmed upload. |

`meta.json` and `events.jsonl` are metadata, not raw stream data, and are always
kept, so History, the upload state and the cached analysis survive raw cleanup.
Cleanup only runs after the server confirmed the upload, so a recording that was
never transferred is never touched. The policy is applied on app start, when
History opens, when an upload completes and when the setting changes, so a night
whose retention time has already passed is cleaned up at the next of those
moments. Changing the setting therefore applies immediately to old nights.

The backend keeps its raw copy until the whole recording is deleted. The
retention setting decides when old raw files may leave the phone. The **Night
details** sheet can also remove the local raw streams immediately; it offers
**Delete local raw data** only when the night also has a remote copy, so the
recording is never left without a copy. Deleting the whole night removes both
copies.

The **Night details** sheet shows the recording facts and where the copies are.
Export prepares the raw files first: if they are only on the server, it fetches
them through `GET /api/taiga/sessions/{id}/files/{file}` with progress and then
opens the share sheet. The backend inflates its compressed copy on the fly, so the
restored files are the original streams. A restored night is protected from
automatic cleanup until its raw data is deleted explicitly.

The metadata, upload state and analysis results are the enduring parts of a
recording. A finished analysis is cached in the app's private `files/analysis/`
directory so the detail screen can show the last result when the server is
unreachable. That cache is not uploaded and contains no credentials.

Raw data that was never uploaded exists only on this phone. Deleting the app or
its data loses it, and no other copy exists. Once the upload is confirmed, the
server copy is what makes the night recoverable after local cleanup.

## Deleting a recording

Holding a History row opens **Export recording** and **Delete recording**; the same actions are in the **Night details** sheet.
Delete removes the local recording, its cached analysis and the remote session.
If the server is unreachable, the remote deletion is queued and retried; a `404`
is treated as success because the session is already gone. The local copy is
removed immediately, so the History row disappears even when the remote delete is
still pending. Pending remote deletes are shown in History and survive a restart.

## Failures

- **Login required** — the token expired or no server is configured. The History
  card links to Settings → Remote.
- **Network** — the server or network is unavailable. WorkManager retries with
  exponential backoff; after several failed attempts the night is marked failed
  and can be retried manually.
- **Server** — the backend rejected the upload (invalid meta, no valid frames,
  limits). The message is shown verbatim.
- **Analysis** — the worker failed or the job was lost. Retry requeues the failed
  job through the backend's job retry route, then polls again.

## Privacy

Uploads go only to the address saved in Settings → Remote. The app has no
analytics or tracking. Tokens are stored with Android Keystore-backed encryption,
and no recording, token or health data is written to the repository or logs.
Account isolation and merge scenarios are tracked in #34.
