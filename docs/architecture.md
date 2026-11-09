# Architecture

This document describes how Taiga Sleep is put together and which rules keep it that
way. [`vision.md`](vision.md) says what the project is for; this says how the code is
organised to serve it. When the two disagree, the vision wins and the code is what gets
fixed.

## One word: recording

A **recording** is one run of a tracker: its metadata and its raw streams. The app, the
backend, the worker and the docs all use this word. Neither "session" nor "night" names a
recording, because three names for one concept is how structure gets lost.

## The three responsibilities of the app

1. **Record.** Connect a tracker, configure it, keep the recording alive, store the raw
   data on the phone.
2. **Talk to the backend.** Move recordings to the backend, ask for analyses, receive the
   results, manage what is stored there.
3. **Show results.** Present analysis results as statistics a person can read.

Everything in the app belongs to exactly one of these, or is shared plumbing for them.
A feature that needs two of them talks to both through their public interfaces and owns
no state of either.

## Backend and worker

```text
   app / any client ──HTTP /v1──▶ backend (Go, PocketBase) ◀──HTTP /v1── worker (Python)
                                       │
                          database + recording files
```

- The **backend** owns data and state: recordings, their files, analyses. It validates
  requests, enforces ownership and serves results. It stores bytes and does not
  interpret them.
- The **worker** does the analysis. It is a client of the same API with a `worker` role,
  not a privileged second API. It reads a recording only while it holds the lease of an
  analysis of that recording.
- The **API** ([`api.md`](api.md)) is the whole contract. PocketBase's own record API is
  closed; nothing in the app or the worker uses it. This keeps the backend usable by
  other clients and independent of PocketBase's internals.

Realtime is Server-Sent Events on `GET /v1/events`. A client that is connected learns at
once that an analysis became available; a client that is not connected asks when it next
opens. Events carry ids and statuses only, never data, so a missed event costs a refetch,
not correctness.

## What the app does with the backend

The full list, and nothing else:

| Action | Purpose |
| --- | --- |
| Create, read, update, delete a recording | the recording row and its metadata |
| Upload, download, delete a recording's files | bulk raw storage, compressed or not, the sender decides |
| Create, read, delete an analysis | results are created once, filled by the worker, then immutable |
| List algorithms | so the user can choose how a recording is analysed |
| Log in, check the token | authentication |
| Health, version | reachability and compatibility |

Anything else a user wants (complex queries, bulk export, a web UI) is a client of the
same API and not a feature of the app.

## App structure

One Gradle module, one package per concern under `com.rbmr.taigasleep`:

| Package | Responsibility | May depend on |
| --- | --- | --- |
| `tracker` | What a tracker is: its capabilities, its recording mode, its configuration. Implementations per device (`polar`), a simulated one in debug builds. | `diagnostics` |
| `recording` | Capturing: the selected tracker, the recording state machine, the foreground service, the on-disk format, the local store, export. | `tracker`, `diagnostics` |
| `guard` | The preventing-shutdown flow: per-device steps, checks, the final readiness list. | `tracker`, `diagnostics` |
| `backend` | A client for API v1: DTOs, requests, authentication state, the event stream. No business logic. | `diagnostics` |
| `sync` | Moving recordings to and from the backend: upload queue, retention, deletion, analysis requests, result cache. `SyncService` is its entry point, and the only thing outside the package calls. | `recording`, `backend`, `insights`, `diagnostics` |
| `insights` | Turning analysis results into what is displayed: statistics, series, scores, the algorithm catalogue and the user's algorithm preference. | `backend`, `diagnostics` |
| `library` | The recordings the user owns, as one list: local state, sync state and results composed for presentation. | `recording`, `sync`, `insights`, `backend`, `diagnostics` |
| `ui` | Screens and components. The only package that knows about Compose. | every other package |
| `diagnostics` | Logging and crash evidence. A leaf. | nothing |

The direction is strict: a package never imports one that is not in its row. An
architecture test fails the build when it does, so the structure is enforced rather than
remembered. A needed dependency that is not allowed is a signal to move code, not to widen
the table. A new package needs a row first: the test also fails for a package that is not
in the table.

Two things sit outside the table:

- **The root package is the app's wiring**: `TaigaApplication`, `MainActivity`,
  `AppSettings`, `AppContainer`. It may depend on everything and nothing else may depend on
  it, apart from the generated `R` and `BuildConfig`. This is where packages that must not
  know each other are connected: `recording` defines what it needs from the rest of the app
  (`RecordingHooks`: may it start, what are the phone's readiness settings, a recording
  ended) and the wiring answers with `guard` and `sync`. `AppContainer` holds the one
  instance of each long-lived collaborator; other packages read it through the
  `Context.appContainer` accessor, so they share state without importing the wiring itself.
- **`debug` is a build-variant seam, not a package of the app.** `src/debug` holds the
  debug-only tools and `src/release` an empty twin with the same API (see
  [`debug-build.md`](debug-build.md)), so any package may use `debug`. It stands in for
  types of `tracker` (the simulated sensor) and `guard` (the guidance preview picker, a
  Compose dialog), and may use those two and nothing else.

Four rules inside the packages:

- **One type per fact.** `recording.store.Recording` is a recording on the phone,
  `backend.RecordingDto` is the server's copy, `library.Analysis` is one analysis, and
  `sync.UploadRecord` is the sync state. `library` composes them into `RecordingView` for
  the screens; nothing else merges them into one object.
- **State has one owner.** Whatever stores or derives a piece of state is the only thing
  that writes it. Screens observe; they do not decide.
- **`sync` has one entry point.** `SyncService` owns the sequences: the upload queue, the
  retention policy, the analysis requests and the result cache are its pieces. Nothing
  outside the package reaches them, and the sync-surface check in `ArchitectureTest` keeps
  it that way.
- **A screen has a state holder.** The History, Home and recording detail screens take their
  state from a view model: it reads the library, sync and the settings, owns what the screen is
  waiting on, and the composable draws that state and forwards the user's actions. The import
  ratchet (`app/src/test/resources/ui-import-baseline.txt`) records how many imports every file
  under `ui/` except the view models still takes from the packages that own state, and fails
  when one rises and when one falls. A screen that needs a new one moves the call into its
  state holder instead.

### Analyses and results

An analysis and its result are owned by what the server said, and the two caches that keep it
on the phone:

- `sync.ResultCache` keeps the full payload of a finished analysis that was downloaded.
- `sync.RemoteHistoryCache` keeps the server's recording list with its analysis summaries, and
  the run each recording shows. The choice of run is a property of the row, not of the analysis.

An analysis summary has one owner: `sync.RemoteHistoryCache`, filled from the server's recording
list and selected-run choices. `library` reads it, and derives what a History row shows about an
analysis — the score, the stage bar, the model, the selected run, whether the payload is cached —
from that cache and `sync.ResultCache` alone. It keeps no copy of those facts. `sync.UploadRecord`
is therefore only the sync state of one recording: where its bytes are, how far they got, why an
attempt failed, when the upload finished, and the retention flags. A score never lives in
`sync.json`.

## The recording lifecycle

### Recording mode

A tracker offers one or both **recording modes** for a given configuration:

- **Stream**: data flows to the phone while recording. The phone has to stay alive for the
  whole recording, so the preventing-shutdown flow applies. The Polar H10 streams HR, ECG and
  ACC.
- **OnDevice**: the tracker stores the data itself and the phone downloads it afterwards.
  The phone does not have to stay awake, so the user is not asked to configure anything.
  The H10 can do this only when recording just HR/RR; the Verity Sense and the Loop are
  expected to work this way.

The mode follows from the tracker and the chosen streams, not from a separate setting the
user has to understand. A mode the tracker offers but the chosen streams rule out stays
visible, greyed out with the reason, so the feature is not mistaken for missing.

An OnDevice recording needs the tracker only twice, when it starts and when it ends. Once the
tracker confirms it is storing, the foreground service and the watchdog stop; the recording
stays `recording` on disk, and the app, a restart of the app or a reboot of the phone finds it
again and shows it as active. Stop and Discard reconnect to the tracker then.

### Keeping a recording alive

A Stream recording is handed from one process to the next instead of being declared dead:

- While a recording runs, the phone keeps a marker with its id in `RecordingPrefs` and the
  service renews its partial wake lock before the timeout runs out, so one lock covers a whole
  night.
- Opening the app, a reboot and the watchdog all hand a marked recording back to the service
  with the same resume action. Only a recording without a marker is an orphan and becomes
  `interrupted`; the service, not those callers, decides whether a marked one can continue.
- The watchdog also rebuilds the live session of a service that is still there but whose
  heartbeat stopped, because resuming it would find its session in place and change nothing.
- A recording the tracker stores itself is adopted on resume, never started a second time.
- When the system refuses to keep the service in the foreground, the recording ends as
  `interrupted` with that reason instead of restarting into the same refusal.

### Ending a recording

Three actions, whose meaning depends on the mode:

| | Stream | OnDevice |
| --- | --- | --- |
| **Stop** | Close the streams gracefully, finish the recording. | Fetch everything from the tracker, store it, then stop the tracker safely. |
| **Force stop** | Finish the recording one-sidedly, without waiting for the tracker. Keep what is already on the phone. | Accept the loss: keep the partial or empty recording locally and do not wait for the tracker. |
| **Discard** | Stop gracefully, then delete everything stored locally. | Tell the tracker to stop and delete its data, do not download it, delete the recording on the phone. |

These are states of one machine with explicit transitions, not flags checked in several
places. Each mode supplies what its transitions do; the machine owns when they happen.

### Start flow

1. Connect a tracker.
2. Configure it: what to record.
3. Preventing-shutdown flow, only if the mode is Stream and the device needs it.
4. Start.

### Preventing-shutdown flow

1. Select a device, defaulting to the detected one.
2. Walk the steps for that device. Each step has an explanation and a check; a check is
   automatic or needs the user's confirmation.
3. The final screen lists every step with its check. When all pass, the device is trusted
   not to shut the recording down while it runs.

## Syncing

The phone cannot keep every recording (ECG fills storage quickly) and cannot run the
analysis, so recordings go to the backend. Sync is a property of a recording, not a
screen:

- A recording is local only, uploading, or on the backend; the sync state is stored with
  the recording on the phone and survives a process kill.
- Uploading is resumable and append-only, matching the recording format.
- Nothing starts an analysis on the backend by itself. The app may request one on the
  user's behalf; that is an app setting, on by default, and the backend never decides it.
- Results are small. They are fetched when they are wanted: live while the app is open
  (events), otherwise on the next open.

## Showing results

Statistics come in three kinds: a single **number**, a **series** (graph) and a **score**
(a weighted combination of percentages). Each carries what the user needs to trust it:
what it means and where its data came from. Figures reach the app only as typed `items` in
the analysis result: there are no parallel result keys to fall back to. The app renders what
the analysis provides and hides what it does not; it does not announce what is missing. See
[`vision.md`](vision.md#show-what-is-available-hide-what-is-not).

## Tests that keep this true

- The architecture test (package dependencies, above).
- The sync-surface check in the same test: nothing outside `sync` reaches past `SyncService`.
- The UI import ratchet (`ui-import-baseline.txt`), described under the rules above.
- The API contract test on the backend: every registered route is in the OpenAPI
  document and the other way round.
- The integration suite, which runs the real backend binary with the real worker.
