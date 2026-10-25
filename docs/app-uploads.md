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
stages, metrics, quality and HRV come from the server.

## Automatic upload

Automatic upload is a master checkbox with two optional restrictions:

| Setting | Effect |
| --- | --- |
| Upload automatically | Off means no automatic uploads; manual upload still works. |
| Only on Wi-Fi | Required unmetered network. Off allows any connected network. |
| Only while charging | Requires the phone to be charging. |

The default is **enabled, Wi-Fi only, not requiring charging**. Results are
downloaded whenever the server is reachable, independently of these upload
restrictions. A night can contain hundreds of megabytes of ECG and
accelerometer data, so the default avoids cellular data.

## Upload lifecycle

History shows one of these states per recording:

- **Only on this phone** — no upload has been requested.
- **Waiting to upload** — queued in WorkManager, or waiting for its network and
  charging constraints.
- **Uploading N%** — bytes are moving. The notification keeps the transfer alive
  while the app is in the background.
- **Waiting for analysis** — the upload completed and the analysis job is queued
  or running on a worker.
- **Quality score / done** — a result is available. The row also shows whether
  the full result has been downloaded; the app fetches missing results as soon as
  it can.
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

## Stager selection

The app owns a hardcoded best-to-worst preference list
(`wav2sleep`, `sleepecg`, `baseline`). It fetches the installed algorithms and
their stream requirements from the backend, filters out those the recording
cannot feed, and requests the best compatible id and version. `baseline` is the
safe fallback. The backend still requires an explicit model and version.
User-configurable stager preferences are tracked in #32.

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

For now **local raw recordings are kept forever after a successful upload.**
Uploading does not delete, truncate or archive the phone copy. The History row
remains usable offline and the original files can still be exported or inspected.

The metadata and analysis results are treated as the enduring parts of a
recording. A finished analysis is cached in the app's private `files/analysis/`
directory so the detail screen can show the last result when the server is
unreachable. That cache is not uploaded and contains no credentials.

Configurable raw cleanup (immediate, 5, 14 or 30 days) is tracked in #33.

## Deleting a recording

Holding a History row opens **Export recording** and **Delete recording**; the same options are in the night-detail three-dot menu.
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
