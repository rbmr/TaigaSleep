# Recording uploads and results in the app

This document describes how a local recording reaches the optional backend, how
its state is stored, and what happens to the files afterwards. Recording itself
is unchanged and never depends on the network.

## Local-first

Every night starts and stays as a normal local recording under
`<external files>/recordings/<session id>/`. History reads the local recordings
and a cached remote summary, so a logged-out or offline user still sees every
night that exists on the phone. Uploading is an extra step attached to that
local record; it never moves or rewrites the recording.

The session id is the local start time, a dash and four random lowercase
letters or digits (`20261023_223000-k3f9`). The server keeps the recording
under the same id, so the phone needs no separate server id: ids are unique
across all accounts on a server, and the suffix keeps two phones that start
in the same second apart. Recordings made before the suffix keep their id.

There is no local sleep analysis. The phone records and talks to the backend;
sleep stages, the sleep score, heart-rate statistics, data quality and HRV come
from the server.

## Where the state lives

There are two stores, and one owner per fact:

- **On disk**, under `<external files>/recordings/<session id>/`: the recording itself —
  `meta.json`, the frame logs and `events.jsonl` — plus the compressed-upload boundary
  cache under `<files>/upload-cache/<session id>/` while an upload is running. These are
  the bytes; sync never rewrites them.
- **In the app's database**: everything learned from a server — the upload state and its
  confirmation, the server's recording list with its analysis summaries, the run each
  recording shows, downloaded result payloads and queued remote deletes. Every row belongs
  to one account, so switching accounts switches what is shown without deleting anything.

History and the night screen read the recordings from disk joined with those rows, in one
query. See [`architecture.md`](architecture.md#analyses-and-results).

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
upload completes. A file whose upload already started compressed continues in
gzip even if the setting is later turned off; a file that started plain stays
plain, because a file's encoding is fixed by its first chunk.

The API stores the bytes as they are and only checks gzip framing. The worker
inflates with size/member/ratio limits, validates frame headers and CRCs, keeps
the valid prefix and records the compression ratio in the analysis provenance.
Full details and the measured trade-off are in
[`upload-compression.md`](upload-compression.md).

## Upload lifecycle

History shows one status per recording:

- **Only on this phone** — no upload has been requested.
- **Waiting to upload** — queued in WorkManager, or waiting for its network and
  charging constraints.
- **Uploading N%** — bytes are moving. The notification keeps the transfer alive
  while the app is in the background.
- **Analysing** — the upload completed and the analysis is queued or running.
- **Ready** — the row shows the sleep score and the stage bar. A full result that is
  not on the phone yet is fetched when it arrives or at the next open; opening the
  night uses the cached copy.
- **Failed** — the row says Upload failed, Analysis failed, Login needed or Update
  needed. Opening the night shows the error and the retry action.

Opening a night is the only action in the list. Retry, upload, export and delete
live in the night screen and its Night details sheet.

WorkManager uses one unique work item per local session. An upload is resumable:
the worker creates the recording on the server from `meta.json` (a repeat of that
request is harmless until the recording is complete), asks for the stored length of
each file with `HEAD` and continues from that offset. A conflict
(`409 offset_mismatch`, another attempt moved the offset) carries the server's
current offset in the `Upload-Offset` header, and the upload continues from there.
When every file is there the recording is marked complete. Force-stopping the app
or losing the network therefore does not restart a night from zero. The server's
chunk limit is configuration, not part of the API; a `413` makes the uploader halve
its chunks and continue.

The upload worker ends as soon as the analysis has been requested, with the night in
the processing state. Nothing polls and nothing runs in the background for a result.
While the app is on screen it keeps the event stream (`GET /v1/events`, see
[`api.md`](api.md#events)) open:

- Every time the stream connects, and once at open if it does not, the app refreshes
  everything pending: the History list, every night whose analysis is not settled
  (queued, running, or stored as processing) and any finished result that is not on
  the phone yet. Events are not replayed, so this is how a night that finished,
  failed or was replaced while the app was closed gets settled.
- An `analysis` or `recording` event refreshes only that recording, so History and
  the night screen update at once. Events for one recording that arrive close
  together are one refresh.
- The stream is closed when the app leaves the screen. Without a stream (a proxy
  that blocks it) the refresh at open is all there is, and **Check again** on the
  night repeats it for that night.

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

The sleep score is the prominent score in History and in the night header. It is labelled
Experimental, because the stages come from a model that has not been checked against sleep-lab
measurements on this sensor; tapping the label says so, and that changes between nights matter
more than the absolute number. When the data quality is too low the worker withholds the score,
and the card shows the reason instead of a number. The data-quality score is separate and
explicitly labelled: it describes the recording and the analysis, not how well the night was
slept. A movement block follows for results that contain accelerometer figures.

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
fallback when no worker has reported. The backend would pick the newest version
itself, but the app always names the one it chose.

**Settings → Analysis** controls the automatic flag and the preferred order.
Both are app-wide, not per account. Automatic analysis is on by default; turning
it off stores the night on the server without requesting a result. The order
dialog lists the algorithms the server reports and lets the user move one up or
down. When a preferred algorithm is not installed or cannot use the recording,
the next compatible one is used; if none is reported, the built-in baseline is
the fallback. A failed run is not replaced automatically: the analysis sheet
offers another algorithm or the existing retry. A manual request can ask for any
compatible algorithm and ignores the automatic flag and order. The backend
removes duplicates by algorithm, version and parameters.

## Choosing an analysis

The night screen names the algorithm that produced the shown result. Tapping
that line opens the analysis sheet. It lists the night's runs with their
status, and the installed algorithms this recording can feed with their name,
description and supported stages. Selecting a finished run changes the shown
result; the choice is kept for this night. **Run** requests one manual
analysis, marked as manual so a finished manual run wins the default selection.
The automatic flag and the ordered preference list live in Settings → Analysis.

## Recording states

- **Stopped** and **force-stopped** recordings upload immediately.
- A recording that was killed mid-night is marked **interrupted** on the next app
  start and then uploads like any other finished night. A recording the phone still
  marks as live is not closed that way: the service resumes it instead.
- A recording that is still live is never uploaded. The `RecordingService`
  enqueues it when it finishes; uploading an open file would race the writer.
- A recording in `fetch_pending` state is not finished either: part of the night is
  still on the tracker. It is not uploaded and its raw files are not cleaned up
  until the fetch completes; the recording is then queued as a new upload with
  every sample in the files.
- The backend's `meta.json` state must be neither `recording` nor `fetch_pending`
  before completion. The worker closes a crashed session with the last frame time,
  the same rule the app uses on startup.

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
kept. The upload state, the cached History list and the cached results are not
files at all: they live in the app's database, one row per recording and account,
so raw cleanup cannot touch them, and deleting a recording removes its rows.
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
them through `GET /v1/recordings/{id}/files/{name}` with progress and then
opens the share sheet. The backend inflates its compressed copy on the fly, so the
restored files are the original streams. A restored night is protected from
automatic cleanup until its raw data is deleted explicitly.

The metadata, upload state and analysis results are the enduring parts of a
recording. A finished analysis is kept in the app's database, so the detail screen
can show the last result when the server is unreachable. Those rows are not
uploaded and contain no credentials.

Raw data that was never uploaded exists only on this phone. Deleting the app or
its data loses it, and no other copy exists. Once the upload is confirmed, the
server copy is what makes the night recoverable after local cleanup.

## Accounts

An account is one server address plus the user id the server returns at login;
the email is only shown to the user, so renaming an address book entry does not
move data. Everything the app learns from a server belongs to the account it was
learned from: the upload state and its confirmation, the remote id, the cached
History list, the chosen run of a night and the cached result payloads.

Logging out or into another account keeps the local recordings and the first
account's remote data. The night screen then shows a recording as only on this
phone, because this account's server does not have it, and History hides the
other account's remote-only nights and results instead of deleting them; logging
back in brings them back. Uploading a night that another account already has asks
first, naming the server and the account the new copy would go to.

Signing out, signing into another account or a token that the server no longer
accepts cancels the uploads, deletes and analysis requests queued for the old
account before the new one becomes active, including work that is already
running. A delete queued for one account is never sent to another, and a `404`
only completes a delete of the account that asked. Raw cleanup counts only a
confirmation made by the active account, so switching servers cannot trim files
the new server never received.

## Exporting a recording

Open a night and use its **Night details** sheet to export it. The sheet chooses what
the archive contains:

- **Raw recording** — the stream logs (`hr.log`, `ecg.log`, `acc.log`).
- **Metadata** — everything that is not a stream log: `meta.json`, the event log and
  any other file of the recording directory.
- **Analysis results** — the payload of every run this phone has already saved, one
  JSON file per run under `analysis/`.

Raw and metadata are selected by default because they are the recording as it was
made; analysis results are opt-in, and a night without a saved result does not offer
them. Any combination works, including results only.

The archive is built in the cache directory while the sheet reports how far it is: an
indeterminate bar for a short export and a percentage with the bytes written for a
large one. Stream logs are stored in the archive without deflating them, which is what
made a large night take seconds before. The archive goes to the share sheet and stays
in the cache until the next export replaces it; exports an earlier run left are removed
when the app starts, but only once they are an hour old, because a share target can
still be reading the file after the chooser has closed.

An export never asks for an analysis and never downloads a result: it writes the
payloads the result cache already holds. The only server round trip is the existing
restore of the raw files, and only when raw data is selected for a night whose copy
was deleted from this phone.

## Deleting a recording

Open a night and use its **Night details** sheet to export or delete it.
Delete removes the local recording, its cached analysis and the remote recording.
If the server is unreachable, the remote deletion is queued and retried; a `404`
is treated as success because the recording is already gone. The local copy is
removed immediately, so the History row disappears even when the remote delete is
still pending. Pending remote deletes are shown in History and survive a restart.

## Failures

- **Login required** — the token expired or no server is configured. The night
  screen links to Settings → Remote.
- **Network** — the server or network is unavailable. WorkManager retries with
  exponential backoff; after several failed attempts the night is marked failed
  and can be retried manually.
- **Server** — the backend rejected the upload (invalid meta, a recording id that
  belongs to another account, limits). The message is shown verbatim.
- **Analysis** — the worker failed, the analysis was lost, or the backend refused
  to queue it because no worker offers the algorithm yet (`422`; the upload itself
  is complete). Retry asks for the analysis again, which makes the backend replace
  a failed one with a fresh queued one. The new run is reported by the event stream.

## Privacy

Uploads go only to the address saved in Settings → Remote. The app has no
analytics or tracking. Tokens are stored with Android Keystore-backed encryption,
and no recording, token or health data is written to the repository or logs.
## Accounts

An account is one server address plus the user id the server returns at login;
the email is only shown to the user, so renaming an address book entry does not
move data. Everything the app learns from a server belongs to the account it was
learned from: the upload state and its confirmation, the remote id, the cached
History list, the chosen run of a night and the cached result payloads.

Logging out or into another account keeps the local recordings and the first
account's remote data. The night screen then shows a recording as only on this
phone, because this account's server does not have it, and History hides the
other account's remote-only nights and results instead of deleting them; logging
back in brings them back. Uploading a night that another account already has asks
first, naming the server and the account the new copy would go to.

Signing out, signing into another account or a token that the server no longer
accepts cancels the uploads, deletes and analysis requests queued for the old
account before the new one becomes active, including work that is already
running. A delete queued for one account is never sent to another, and a `404`
only completes a delete of the account that asked. Raw cleanup counts only a
confirmation made by the active account, so switching servers cannot trim files
the new server never received.
