# Backend

Self-hosted Go PocketBase API plus a Python analysis worker for Taiga Sleep.

This directory contains everything the optional backend needs: the database
schema, owner rules, health checks, the durable analysis queue, the analysis
worker, deployment examples and backup guidance. It accepts append-only
recordings through an owner-only upload API, runs staging algorithms in a
separate worker process, and stores one result per session/algorithm/version/
parameter set. The optional wav2sleep ECG model is available as a worker extra;
SleepECG is tracked in #22.

## What is here

```
backend/
├── api/
│   ├── Dockerfile
│   ├── docker-entrypoint.sh
│   ├── go.mod / go.sum
│   ├── internal/api/           # Go routes, OpenAPI docs, validation, queue
│   └── migrations/             # embedded Go schema migrations
├── worker/
│   ├── Dockerfile
│   ├── pyproject.toml
│   ├── taiga_worker/           # supervisor, analysis library and baseline stager
│   └── tests/
├── tests/test_integration.py   # real Go API + real worker process
├── deploy/
│   ├── docker-compose.yml
│   ├── .env.example
│   ├── Caddyfile
│   └── systemd/
└── scripts/                    # benchmark and model download
```

The custom API, record hooks and schema migrations are all Go. The migrations
are embedded in the binary; there is no JavaScript runtime or hooks directory.

The Go API process is the only process that touches the SQLite database and the
stored files. The worker talks to the API only, so it can run on the same
machine or on a larger one without code changes.

## Requirements

- Go **1.27+** for the API. PocketBase is pinned as a Go module at **0.40.6**.
- Python **3.10+** and [uv](https://docs.astral.sh/uv/) for the worker. The
  worker uses numpy for decoding, metrics and the baseline stager; the idle
  supervisor does not import it. The optional wav2sleep ECG model needs Python
  **3.11+** and is installed as a worker extra, so the base worker stays small.
  See [`docs/wav2sleep.md`](../docs/wav2sleep.md).
- Optional: Docker Compose for the container deployment.

## Quick start (without Docker)

```bash
cd backend/api
go build -o ./taiga-sleep-api .

# Apply the schema into a local data directory and start the API.
./taiga-sleep-api serve \
  --http=127.0.0.1:8090 \
  --dir=./pb_data \
  --automigrate=false
```

Open `http://127.0.0.1:8090/_/` and create the first admin account, or run
`./taiga-sleep-api superuser upsert <email> <password>`. The dashboard is **not**
meant to be exposed to the internet; see [Admin access](#admin-access).

The schema is applied automatically on `serve`. To apply it without serving:

```bash
./taiga-sleep-api migrate up --dir=./pb_data
```

### Create the user and worker accounts

Public registration is disabled. Create the single app user from the dashboard,
then create one service account for the worker from the dashboard too (the
`workers` collection is under **Collections**). The worker account needs only an
email and a password; it cannot read records directly.

For scripted setup, authenticate as the admin and use the collection API:

```bash
# Example only; keep the token and password out of shell history and git.
curl -s -X POST http://127.0.0.1:8090/api/collections/users/records \
  -H "Authorization: <admin-token>" -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"<password>","passwordConfirm":"<password>"}'

curl -s -X POST http://127.0.0.1:8090/api/collections/workers/records \
  -H "Authorization: <admin-token>" -H "Content-Type: application/json" \
  -d '{"email":"worker@example.com","password":"<password>","passwordConfirm":"<password>","name":"worker-1"}'
```

### Run the worker

```bash
cd backend/worker
uv sync --frozen

export TAIGA_API_URL=http://127.0.0.1:8090
export TAIGA_WORKER_EMAIL=worker@example.com
export TAIGA_WORKER_PASSWORD='<password>'
uv run --frozen python -m taiga_worker
```

It logs in, reports the algorithms it has installed, polls the claim endpoint,
downloads one recording through the API, runs one stager in a fresh subprocess,
and reports the result. The supervisor never reads the database or recording
files directly.

The analysis library lives in `taiga_worker/analysis/`: the stager contract,
recording decoder, shared metrics/quality and the built-in `baseline` stager.
See [`docs/analysis-contract.md`](../docs/analysis-contract.md) for the plugin
interface, canonical stages, result shape and a minimal third-party example.

### Optional wav2sleep stager

The ECG model is deliberately not installed by default:

```bash
cd backend/worker
uv sync --frozen --extra wav2sleep
cd ..
uv run --project worker --frozen --extra wav2sleep \
  python scripts/download_wav2sleep_checkpoint.py --dest /srv/taiga/models/wav2sleep
export TAIGA_WAV2SLEEP_CHECKPOINT=/srv/taiga/models/wav2sleep
```

The checkpoint is pinned by revision and SHA-256 and is never downloaded during
a job. When the extra and checkpoint are present the worker advertises
`wav2sleep`; the app prefers it over the baseline when the recording has ECG.
The adapter gates on Polar HR contact and ECG signal quality, so a detached
strap becomes `unknown` instead of wake, and smooths stage probabilities to
remove per-epoch flicker. Set `TAIGA_JOB_MEMORY_MB=2048` (the default 1024 is
too low for PyTorch) and expect about 1.5 GB of extra disk. Full install,
adapter, provenance and benchmark notes:
[`docs/wav2sleep.md`](../docs/wav2sleep.md).

## Configuration

### PocketBase

PocketBase itself is configured with flags. The deployment examples use these
variables and expand them in the unit/compose files:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TAIGA_HTTP_ADDR` | `127.0.0.1:8090` | Listen address. Use `0.0.0.0:8090` inside a container. |
| `TAIGA_DATA_DIR` | `pb_data` / `/pb/pb_data` | Data directory (database, files, logs). Back this up. |
| `TAIGA_APP_URL` | unset | Stored in PocketBase settings; used in auth email links. |
| `TAIGA_APP_NAME` | `Taiga Sleep` | Display name in the dashboard and emails. |
| `TAIGA_ENCRYPTION_KEY` | unset | Optional 32-character key for PocketBase settings encryption. |
| `TAIGA_MAX_RECORDING_MB` | `256` | Total bytes allowed for one recording directory. |
| `TAIGA_MAX_RECORDING_FILE_MB` | `64` | Bytes allowed for one stream file. |
| `TAIGA_MAX_UPLOAD_CHUNK_MB` | `8` | Maximum upload chunk; keep it below PocketBase's 10 MB request-body cap. |
| `TAIGA_MAX_META_KB` | `64` | Maximum `meta.json` size. |

The API also reads `TAIGA_JOB_LEASE_S` as the default claim lease (default
`1800`, clamped to 1 day) and `TAIGA_JOB_MAX_ATTEMPTS` as the retry limit for
retryable analysis failures (default `3`).

### Worker

Command-line flags take precedence over environment variables.

| Variable | Flag | Default | Purpose |
| --- | --- | --- | --- |
| `TAIGA_API_URL` | `--api-url` | `http://127.0.0.1:8090` | API base URL. |
| `TAIGA_WORKER_EMAIL` | `--email` | required | Worker service account email. |
| `TAIGA_WORKER_PASSWORD` | `--password` | required | Password; prefer the file/env form. |
| `TAIGA_WORKER_PASSWORD_FILE` | `--password-file` | unset | Read the password from a root-only file. |
| `TAIGA_WORKER_ID` | `--worker-id` | hostname-pid | Name recorded on a lease. |
| `TAIGA_POLL_INTERVAL_S` | `--poll-interval` | `15` | Sleep when the queue is empty. |
| `TAIGA_JOB_LEASE_S` | `--lease-seconds` | `1800` | Lease length; an expired lease is requeued. |
| `TAIGA_JOB_MEMORY_MB` | `--memory-mb` | `1024` | Address-space limit for one job; `0` disables it. Use `2048` for wav2sleep. |
| `TAIGA_JOB_TIMEOUT_S` | `--timeout` | `3600` | Wall-clock limit for one job. |
| `TAIGA_WAV2SLEEP_CHECKPOINT` | - | unset | Local checkpoint directory for the optional wav2sleep extra. |
| `TAIGA_LOG_LEVEL` | `--log-level` | `INFO` | Python log level. |
| `TAIGA_ALGORITHMS` | `--algorithms` | unset | Comma-separated algorithm id allowlist. Unset means every installed stager. |

Numeric libraries are forced to one thread in the child process
(`OMP_NUM_THREADS=1`, `OPENBLAS_NUM_THREADS=1`, `MKL_NUM_THREADS=1`,
`NUMEXPR_NUM_THREADS=1`, `VECLIB_MAXIMUM_THREADS=1`, `BLIS_NUM_THREADS=1`).
This keeps a small server from being oversubscribed by a single analysis.

### Dependencies

The worker environment is managed by uv. `backend/worker/uv.lock` is committed,
so installs are reproducible:

```bash
cd backend/worker
uv sync --frozen          # exact lockfile, including the interpreter
uv sync --frozen --extra wav2sleep   # optional CPU torch + wav2sleep
uv add <package>          # update pyproject.toml and uv.lock together
uv run --frozen python -m taiga_worker
```

Both the Docker image and CI use `uv sync --frozen` without the extra, so they
stay small. The extra pins PyTorch from the CPU-only index and `wav2sleep` at a
specific git commit. Do not install packages with `pip` into the worker
environment or edit `uv.lock` by hand.

## API

### Health and auth checks

`GET /api/taiga/health` is unauthenticated. `GET /api/taiga/auth-check` requires
a valid token and confirms which collection it belongs to.

```bash
curl -s http://127.0.0.1:8090/api/taiga/health
# {"status":"ok","service":"taiga-sleep","time":"..."}

curl -s http://127.0.0.1:8090/api/taiga/auth-check -H "Authorization: <token>"
# {"ok":true,"id":"...","collection":"users"}
```

These are the two checks the app's connection screen (#9) uses.

### OpenAPI document

Every custom route is declared once in `api/internal/api/openapi.go`. That
manifest mounts the handler and generates the OpenAPI 3.1 document, so the
router and the documentation cannot disagree.

The document is generated when the process starts and kept in memory. Nothing
is checked in, so there is no generated artifact to regenerate or keep in sync:

- `GET /api/taiga/docs` (public) is the interactive Swagger UI page for the
  running build. It works offline: Swagger UI 5.34.0 is vendored under
  `api/internal/api/swaggerui/` (Apache-2.0, see the `LICENSE` there) and
  embedded in the binary, so there is no CDN, no extra container and no config.
  The page is plain Swagger UI, unmodified, with no Taiga chrome added.
- `GET /api/taiga/openapi.json` (public) serves the generated document for any
  tool that wants to read or download it.

Both routes are public, like FastAPI's `/docs`: the document describes shapes
only and contains no recordings, health values or credentials. PocketBase's
dashboard remains the only authenticated UI.

To use the document elsewhere, point any of these at the URL of your deployment,
for example `http://127.0.0.1:8090/api/taiga/openapi.json`:

- **Redoc** — <https://redocly.github.io/redoc/> with the URL in the `url`
  parameter, or the `redocly` CLI for a static build.
- **Postman / Insomnia** — `Import > Link` with the same URL. The two security
  schemes map to the `Authorization` header; paste the raw PocketBase token
  without a `Bearer` prefix.
- **VS Code** — the *OpenAPI (Swagger) Editor* extension can open the downloaded
  `openapi.json`.

PocketBase's built-in collection API is not part of this document; it has its
own docs. Nothing in the document is derived from a recording or a health
value, only from the Go types.

### Recording uploads

Uploading and analysing are separate steps. An upload only stores and validates
the recording and marks it `uploaded`; it never queues analysis. Analysis
requests, results and the worker download path are added by the rest of #10.

All upload routes are owner-only (`users` token). A session is created or
resumed from `meta.json` and identified by `(owner, client_session_id)`, so a
retried create returns the existing session instead of duplicating it.

```text
POST /api/taiga/sessions                          create or resume from { "meta": {...} }
GET  /api/taiga/sessions/{id}/upload              current lengths and validation state
PUT  /api/taiga/sessions/{id}/upload/meta         replace meta.json
PUT  /api/taiga/sessions/{id}/upload/file/{file}?offset=N   append raw bytes
POST /api/taiga/sessions/{id}/upload/complete     validate and mark uploaded
```

- `{file}` is one of `hr.log`, `ecg.log`, `acc.log`, `events.jsonl`. The stream
  files are the frame logs from `docs/recording-format.md`; `meta.json` is
  written through the meta route, not as raw bytes.
- `offset` must equal the server's current length of that file. A wrong offset
  returns `409` with the expected length in the message. Re-read
  `GET /upload` to resume, or the response's `length`/`validated_length`.
- Chunks are raw bytes (`application/octet-stream`, up to 8 MB). The response
  reports `length`, `validated_length`, `frames`/`lines`, `state` and any error.
- Validation is incremental. Complete frames are accepted until the first
  truncated or bad-CRC frame. A truncated final frame is kept while uploading
  and cut at completion (`truncated: true`, warning in `upload_error`). A bad
  frame truncates the file to the last valid frame and is reported; the valid
  prefix can still be completed and analysed.
- `complete` requires a valid `meta.json`, a non-`recording` state, and at least
  one valid frame. The session's `state`, `started_at`, `ended_at`,
  `duration_s` and `device` are derived from `meta.json`.
- Recordings live in `<pb_data>/recordings/<session id>/`, so PocketBase backups
  include them. Delete a session with the standard collection route
  (`DELETE /api/collections/sessions/records/{id}`); the delete hook removes the
  recording directory and cascades its analyses and jobs.

Limits are configurable through `TAIGA_MAX_RECORDING_MB` (256),
`TAIGA_MAX_RECORDING_FILE_MB` (64), `TAIGA_MAX_UPLOAD_CHUNK_MB` (8) and
`TAIGA_MAX_META_KB` (64). The events file has a fixed 8 MB cap. The effective
limits are returned in the upload manifest.

Example flow:

```bash
# 1. Create/resume (the body is the meta.json object).
curl -s -X POST http://127.0.0.1:8090/api/taiga/sessions \
  -H "Authorization: <user-token>" -H "Content-Type: application/json" \
  -d '{"meta": {"format":"taiga-recording", "version":1, "session_id":"20261023_223000", "state":"stopped", "start_utc_ms":1792798200000, "end_utc_ms":1792828200000, "timezone":"Europe/Amsterdam", "sensor":{"kind":"polar-h10"}, "config":{"hr":true,"ecg":true,"acc":true,"ecg_rate_hz":130,"acc_rate_hz":50,"acc_range_g":8}}}'

# 2. Append a chunk at the current length.
curl -s -X PUT "http://127.0.0.1:8090/api/taiga/sessions/<id>/upload/file/hr.log?offset=0" \
  -H "Authorization: <user-token>" -H "Content-Type: application/octet-stream" \
  --data-binary @chunk.bin

# 3. Finish.
curl -s -X POST "http://127.0.0.1:8090/api/taiga/sessions/<id>/upload/complete" \
  -H "Authorization: <user-token>" -H "Content-Type: application/json" -d '{}'
```

### Analysis and results

Analysis is an explicit second step after upload. A session can have several
analyses at once (one per algorithm/version/parameter set), and the app can
switch between them without re-uploading the recording.

```text
GET  /api/taiga/algorithms                        algorithms reported by workers
POST /api/taiga/sessions/{id}/analysis            request one or more models
GET  /api/taiga/sessions                          paginated summary; filters: state, upload_status, from, to, sort
GET  /api/taiga/sessions/{id}                     session, upload, analyses, jobs, summary
GET  /api/taiga/analyses/{id}                     one full result
POST /api/taiga/jobs/{id}/retry                   requeue a failed analysis
```

- `POST /analysis` takes `{ "models": [ { "model": "baseline", "version": "1.1.0", "params": {} } ] }`
  (up to 8). `version` is required; read it from `GET /algorithms`. A single
  `{model, version, params}` object is also accepted.
- Requests are idempotent on `(session, model, version, params_hash)`. Asking
  for the same analysis again returns the existing row and job. A failed
  analysis stays failed until `POST /jobs/{id}/retry` is used.
- A worker claims only jobs for algorithms it has installed. The claim response
  includes the analysis `params`, `model_version` and `params_hash`.
- `GET /sessions` uses PocketBase filters/sort and returns `hasMore` rather than
  a total count. Supported filters: `state`, `upload_status`, `from`, `to`;
  `sort` accepts `started_at`, `created`, `updated`, `duration_s` and a leading
  `-` for descending. Model/analysis-status filtering is left to the app.
- The full result contains `contract_version`, `model`, `model_version`,
  `params`, `params_hash`, compact `stages`, `metrics`, `metric_support`,
  `quality`, `capabilities`, `provenance`, `extras` and `warnings`.
- Worker-only download routes are scoped to the job the worker currently holds:
  `GET /api/taiga/worker/sessions/{id}/manifest?job=...&worker=...` and
  `GET /api/taiga/worker/sessions/{id}/files/{file}?job=...&worker=...`.
- Workers report installed algorithms with
  `POST /api/taiga/worker/capabilities`; the API stores the list and the
  last-seen time. `GET /algorithms` returns the union of all workers, with
  `worker_count`.
- Failed results are retried up to `TAIGA_JOB_MAX_ATTEMPTS` (default 3) with
  exponential backoff when the worker marks the failure retryable. Invalid
  input, missing streams, unknown algorithms and malformed results fail
  permanently with the reason stored on the analysis.

### Collections

- `users` — built-in auth. Registration is closed (`createRule` is null).
  Users can only list/view/update/delete their own record.
- `sessions` — one row per recording: `owner`, `client_session_id`, `device`,
  `started_at`, `ended_at`, `duration_s`, `state`, `upload_status`, `upload`
  (per-file lengths and validation state), `uploaded_at`, `upload_error`, plus
  `created` and `updated`. Owner-only rules.
- `analyses` — one row per `(session, model, model_version, params_hash)`:
  `session`, `model`, `model_version`, `params`, `params_hash`, `status`,
  `contract_version`, `stages`, `metrics`, `metric_support`, `quality`,
  `capabilities`, `provenance`, `extras`, `warnings`, `error`, plus timestamps.
  Users can list/view their own rows; create/update/delete are server-only.
- `jobs` — durable queue: `session`, `analysis`, `kind`, `model`,
  `model_version`, `params_hash`, `status`, `attempts`, `available_at`,
  `lease_until`, `worker`, `error`, `result`, plus timestamps. Users can
  list/view and create their own jobs; direct creation is a tooling/test path.
  Update/delete are server-only. The app uses the analysis request route.
- `workers` — auth collection for worker service accounts: `name`, `version`,
  `algorithms`, `last_seen`. Public create, list, update and delete are
  disabled; workers cannot read sessions or jobs directly.

### Job flow

1. Upload completion marks the session `uploaded` and does **not** create a
   job.
2. `POST /api/taiga/sessions/{id}/analysis` creates an `analyses` row and one
   `jobs` row per requested model. Creation is idempotent on
   `(session, model, model_version, params_hash)`; the unique index is the
   race-safe backstop.
3. A worker reports its installed algorithms, then calls
   `POST /api/taiga/worker/jobs/claim` with its worker id and algorithm ids.
   The hook selects the oldest eligible job it can run and updates it
   conditionally inside a transaction, so exactly one caller gets it. The
   analysis status becomes `running` and the response includes the params.
4. The worker downloads the recording through the scoped worker routes, runs
   the stager in a fresh subprocess, and reports `done` or `failed` through
   `POST /api/taiga/worker/jobs/{id}/result`. Retried reports are harmless.
5. A done result is written into the analysis row atomically with the job
   status. A retryable failure is requeued with exponential backoff up to
   `TAIGA_JOB_MAX_ATTEMPTS`; a permanent failure stores the reason on both rows.
6. Every minute `taiga_requeue_expired_jobs` requeues `running` jobs whose lease
   has expired and resets their analysis status to `queued`. `attempts` is
   preserved for observability. `POST /api/taiga/worker/jobs/requeue` runs the
   same operation on demand.

All `/api/taiga/worker/*` routes require a `workers` token. A normal user token
is rejected.

## Tests

Go unit tests cover frame/CRC validation, meta validation and params hashing:

```bash
cd backend/api
go test ./...
```

Both Python suites run under pytest. It is a dev-only dependency of the worker
(`[dependency-groups]` in `backend/worker/pyproject.toml`), so the production
image built with `uv sync --frozen --no-dev` stays pytest-free. Dependencies and
the exact interpreter are managed by uv from `backend/worker/uv.lock`:

```bash
cd backend/worker
uv sync --frozen
uv run --frozen pytest
```

The integration suite builds the Go API, starts it in a temporary directory,
applies the migrations, and exercises the routes and a real worker subprocess.
It covers upload/resume/validation, analysis requests, algorithm reporting,
scoped worker downloads, the baseline end-to-end run, result storage, retries,
the served OpenAPI document and docs page, and owner isolation. Set `POCKETBASE_BIN` to the
built Go binary; the suite skips when it is not available:

```bash
cd backend
(cd api && go build -o /tmp/taiga-sleep-api .)
uv sync --project worker --frozen
POCKETBASE_BIN=/tmp/taiga-sleep-api \
  uv run --project worker --frozen pytest tests/test_integration.py
```

Set `TAIGA_KEEP_TEST_DATA=1` to keep the temporary data directory after a run.

CI (`.github/workflows/backend.yml`) runs the Go tests, the worker tests and the
integration suite on every pull request that touches `backend/`.

## Deployment

### systemd

The units assume:

- the Go binary and migrations live in `/opt/taiga-sleep`;
- data lives in `/var/lib/taiga-sleep` (`StateDirectory`);
- secrets live in `/etc/taiga-sleep/*.env`, owned by root and mode `0640`.

```bash
sudo useradd --system --home /var/lib/taiga-sleep --shell /usr/sbin/nologin taiga
sudo install -d -o taiga -g taiga /opt/taiga-sleep
(cd backend/api && go build -trimpath -o taiga-sleep-api .)
sudo install -m 0755 backend/api/taiga-sleep-api /opt/taiga-sleep/taiga-sleep-api
sudo install -m 0644 backend/deploy/systemd/taiga-sleep-api.service /etc/systemd/system/
sudo install -m 0644 backend/deploy/systemd/taiga-sleep-worker.service /etc/systemd/system/
sudo install -m 0640 -g taiga backend/deploy/systemd/taiga-sleep-api.env.example /etc/taiga-sleep/api.env
sudo install -m 0640 -g taiga backend/deploy/systemd/taiga-sleep-worker.env.example /etc/taiga-sleep/worker.env
# edit both env files
sudo -u taiga uv sync --frozen --no-dev --project /opt/taiga-sleep/worker
sudo systemctl daemon-reload
sudo systemctl enable --now taiga-sleep-api taiga-sleep-worker
```

Install uv on the server first if it is not already available. Both units bind
the API to loopback. Use a reverse proxy for remote access.

### Docker Compose

```bash
cd backend
cp deploy/.env.example deploy/.env
# edit deploy/.env (never commit it)
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

The API image builds the Go binary from `backend/api`; the worker image is
unchanged. The API port is published on loopback by default, and the data
directory is a named volume. The worker has a configurable container limit
(`TAIGA_WORKER_MEMORY`) with the job subprocess limit slightly below it
(`TAIGA_JOB_MEMORY_MB`). On the first start, `TAIGA_ADMIN_EMAIL` and
`TAIGA_ADMIN_PASSWORD` can bootstrap the admin account; remove them afterwards.

### Admin access

Do not expose the PocketBase dashboard (`/_/`) to the internet. The included
`Caddyfile` proxies only `/api/*` and returns 404 for everything else:

```bash
TAIGA_DOMAIN=sleep.example.com caddy run --config backend/deploy/Caddyfile
```

Reach the dashboard through an SSH tunnel instead:

```bash
ssh -L 8090:127.0.0.1:8090 user@server
# then open http://127.0.0.1:8090/_/
```

If you really need to listen on a LAN without a proxy, set
`TAIGA_HTTP_ADDR=0.0.0.0:8090` (or `TAIGA_BIND_ADDR=0.0.0.0` for Compose) and
accept that the dashboard is reachable by anyone who can reach the port.

### Small machines and swap

The server and worker supervisors are small, but sleep staging (issue #10) can
peak above 1 GB. On a 1 GB machine add swap before enabling the worker:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Then set `TAIGA_JOB_MEMORY_MB` to a realistic value for the machine. The limit
is an address-space cap, not a reservation, so the idle footprint stays small.

## Footprint

Measured on x86_64 Linux with PocketBase 0.40.6 and the
worker idle, using `ps -o rss=` (resident set size, including shared pages):

| Process | Idle RSS |
| --- | --- |
| Go API (`serve`, no requests) | ~35 MiB |
| Worker supervisor (after algorithm discovery, polling an empty queue) | ~40 MiB |

The supervisor imports numpy when it reports installed algorithms. That keeps
the worker self-contained; the analysis subprocess is still the only heavy
part. Its limit is configurable via `TAIGA_JOB_MEMORY_MB`, and numeric libraries
are single-threaded.

The Go binary is about 38 MB on disk. Roughly 1.9 MB of that is the embedded
Swagger UI for `/api/taiga/docs`; it is read-only data that the idle server
never touches, so it does not count towards the idle figures above.

## Benchmarks

Measure one stager on a local recording without the API or database:

```bash
cd backend
uv run --project worker --frozen python scripts/benchmark_analysis.py \
  /path/to/recording --model baseline
```

It runs the real job subprocess and reports input size, runtime, wall time, peak
RSS, stage seconds, quality and warnings. Use it on real H10 nights to decide
`TAIGA_JOB_MEMORY_MB` for each machine and model. See
[`docs/benchmarks.md`](../docs/benchmarks.md) for the method, the synthetic
reference run and the real-night table. Do not commit recordings or results.

## Backup and restore

Backups use PocketBase's built-in feature; the repository ships no backup
script. A backup is a zip of the **whole data directory**, not just `data.db`:
recordings live under `pb_data/recordings/` and PocketBase's uploaded files under
`pb_data/storage/`, so both are included. The archive is a consistent snapshot;
the API keeps serving while it is written.

### Schedule and retention

Configure this under `Settings -> Backups` in the dashboard, or patch the
settings as a superuser (`jq` is only used to read the token here):

```bash
BASE=http://127.0.0.1:8090
TOKEN=$(curl -fsS -X POST "$BASE/api/collections/_superusers/auth-with-password" \
  -H 'Content-Type: application/json' \
  -d '{"identity":"admin@example.com","password":"..."}' | jq -r .token)

curl -fsS -X PATCH "$BASE/api/settings" \
  -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"backups":{"cron":"0 3 * * *","cronMaxKeep":14}}'
```

- `cron` is a five-field cron expression. It is **empty by default**, which
  leaves automatic backups disabled. Choose a schedule that matches how much
  history you are willing to lose; a nightly run with two weeks of copies
  (`0 3 * * *`, `cronMaxKeep: 14`) is a common baseline, not a requirement.
- `cronMaxKeep` is required once `cron` is set and limits how many automatic
  backups are kept.
- Automatic backups are written to `<data dir>/backups/` as
  `@auto_pb_backup_<app name>_<timestamp>.zip` and listed under
  `Settings -> Backups`.
- Budget free disk space for roughly twice the data directory while a backup is
  being created, and remember that these copies live on the same disk.
- **Retention caveat:** `cronMaxKeep` prunes only `@auto_pb_backup_*` files.
  Manual backups created in the dashboard or through the API are never deleted
  automatically, so remove them yourself.

The cron job runs inside the API process, so scheduled backups need no superuser
credentials at run time; the password above is only used to change the settings.

### Offsite copies (optional)

An automatic backup on the same disk is not disaster recovery. Both of the
following are first class; pick one, both or neither.

**PocketBase's S3 backup target.** Fill in `Settings -> Backups -> S3` with the
bucket, region, endpoint, access key, secret and, for S3-compatible servers such
as MinIO, `forcePathStyle`. All fields are required when the target is enabled;
`endpoint` is your provider's S3 endpoint (for AWS, for example
`https://s3.eu-central-1.amazonaws.com`). While this is enabled, new backups are
uploaded to the bucket instead of being written to `pb_data/backups/`, and
`cronMaxKeep` applies to the bucket contents.

The access key and secret are stored in the app settings in `pb_data/data.db`.
Protect that directory and set [`TAIGA_ENCRYPTION_KEY`](#configuration) if you
want the settings encrypted at rest; remember that a restore then needs the same
key.

**rclone or another file-sync tool.** Leave the S3 backup target disabled and
copy the local backup directory offsite:

```bash
rclone sync /var/lib/taiga-sleep/backups remote:taiga-sleep-backups
```

Sync only `<data dir>/backups/`. Copying `pb_data/` while the API is running
captures SQLite files that are changing underneath you; the zips in `backups/`
are self-contained and safe. `rclone sync` mirrors the local retention, so old
automatic backups disappear offsite as well; use `rclone copy` plus a remote-side
retention policy if you want a longer offsite history. Run it from cron, a
systemd timer or whichever scheduler your sync tool provides. With the Compose
example the data directory is `/pb/pb_data` inside the `taiga-data` volume, so
mount that volume into the sync container.

### Restore

Restore from the dashboard (`Settings -> Backups`, restore icon) while the API
runs. PocketBase moves the current data aside, unpacks the archive and
re-executes the API process in place, so systemd and Docker do not need to
restart the unit. Stop the worker first (it may be reading recordings or writing
results while the data directory is replaced) and start it again afterwards.

To restore a downloaded archive on a stopped instance:

1. Stop the API and the worker.
2. Move the current data directory aside, keeping it until the restore is verified.
3. Extract the archive into an empty data directory so that `data.db`,
   `auxiliary.db`, `storage/` and `recordings/` are at its top level.
4. Fix ownership, start the API, then check `/api/taiga/health` and one session.
5. Remove the old directory once the restored instance looks right.

```bash
sudo systemctl stop taiga-sleep-api taiga-sleep-worker
sudo mv /var/lib/taiga-sleep /var/lib/taiga-sleep.old-$(date -u +%Y%m%d-%H%M%S)
sudo install -d -o taiga -g taiga /var/lib/taiga-sleep
sudo -u taiga unzip -q /srv/backups/@auto_pb_backup_taiga_sleep_20260101030000.zip \
  -d /var/lib/taiga-sleep
sudo systemctl start taiga-sleep-api
```

If `TAIGA_ENCRYPTION_KEY` is set, restore with the same key; otherwise the
settings stored in `data.db`, including the S3 secret, cannot be decrypted.

With the Compose example, the data directory is `/pb/pb_data` inside the
`taiga-data` volume; run the same steps in a helper container that mounts the
volume, or use the dashboard restore.

For a stopped instance without a PocketBase archive, an archive of the data
directory is enough. This is the only case that needs the API to be down:

```bash
sudo systemctl stop taiga-sleep-api
sudo tar -C /var/lib -czf /srv/backups/taiga-sleep-data-$(date -u +%Y%m%d-%H%M%S).tar.gz taiga-sleep
sudo systemctl start taiga-sleep-api
```

That includes `pb_data/backups/` unless you exclude it, so store the result off
the same disk. Verify a restore on a scratch instance after changing the backup
setup; an unverified backup is not a backup.

## Upgrade

1. Back up first.
2. Stop the API and worker.
3. Rebuild the Go binary from the same commit; migrations are embedded.
4. Start the API; new migrations apply automatically.
5. Check `/api/taiga/health`, the cron list and one worker claim.

Keep the worker and API versions from the same commit. The worker only uses the
public API, but the job payload shape is expected to stay in sync.

## Security notes

- No secrets, tokens or recordings are stored in this repository. `.env` files,
  `pb_data/` and Python caches are ignored.
- PocketBase settings, including the optional backup S3 secret, live in
  `pb_data/data.db`. Set `TAIGA_ENCRYPTION_KEY` to encrypt them at rest.
- Registration is disabled; there is one app user in the intended setup.
- Worker credentials are separate from the app user. The worker cannot read
  records directly.
- The admin dashboard is loopback-only in the examples.
- The API is HTTP by default. Terminate TLS at the reverse proxy (Caddy does so
  automatically) before using it outside your LAN.

## Out of scope

The real wav2sleep and SleepECG adapters (#22, #23), accuracy claims without
ground-truth labels, optional compressed uploads (#24), OpenAPI generation
(#28), the app-side upload and result UI (#11), and a sleep score. The score is
a product policy over stages and metrics, not a stager.

