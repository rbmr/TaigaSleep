# Backend

Self-hosted Go PocketBase API plus a Python analysis worker for Taiga Sleep.

This directory contains everything the optional backend needs: the database
schema, owner rules, health checks, the durable analysis queue, the analysis
worker, deployment examples and backup guidance. It accepts append-only
recordings through an owner-only API, runs staging algorithms in a
separate worker process, and stores one result per recording/algorithm/version/
parameter set. The optional wav2sleep ECG model and SleepECG HR/RR stager are
available as worker extras.

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
  supervisor does not import it. The optional wav2sleep and SleepECG models need
  Python **3.11+** and are installed as worker extras, so the base worker stays
  small. See [`docs/wav2sleep.md`](../docs/wav2sleep.md) and
  [`docs/sleepecg.md`](../docs/sleepecg.md).
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

It logs in, reports the algorithms it has installed, listens to the API's event
stream (`GET /v1/events`) and claims an analysis the moment one is queued, and
once after every (re)connect, since events are not replayed. It then downloads one
recording through the API, runs one stager in a fresh subprocess, and reports the
result. While the queue is empty it also claims every `TAIGA_POLL_INTERVAL_S`
seconds (60 by default), which finds what no event announces, such as a retry whose
delay ran out, and keeps a worker whose stream is down (a proxy that buffers it,
say) working. The supervisor never reads the database or recording files directly.

The analysis library lives in `taiga_worker/analysis/`: the stager contract,
recording decoder, shared metrics, data quality, the sleep score and the built-in `baseline` stager.
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
`wav2sleep`; the app prefers wav2sleep and uses baseline only when it is unavailable or incompatible.
The adapter gates on Polar HR contact and ECG signal quality, so a detached
strap becomes `unknown` instead of wake, and smooths stage probabilities to
remove per-epoch flicker. Set `TAIGA_JOB_MEMORY_MB=2048` (the default 1024 is
too low for PyTorch) and expect about 1.5 GB of extra disk. Full install,
adapter, provenance and benchmark notes:
[`docs/wav2sleep.md`](../docs/wav2sleep.md).

### Optional SleepECG stager

SleepECG stages from heartbeat times. It is the fallback for HR/RR-only nights
and is useful for comparing a different feature set and classifier:

```bash
cd backend/worker
uv sync --frozen --extra sleepecg
```

The bundled classifiers are part of the package, so there is no separate
checkpoint or runtime download. The default three-class model distinguishes
Wake/REM/NREM; a two-class Wake/Sleep classifier is also available. Deep sleep
is never invented. Use `TAIGA_JOB_MEMORY_MB=2048` and expect about 1.5 GB of
extra disk. See [`docs/sleepecg.md`](../docs/sleepecg.md).

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
| `TAIGA_POLL_INTERVAL_S` | `--poll-interval` | `60` | Fallback when the queue is empty: claim again after this long without an event. New work normally arrives as an event. |
| `TAIGA_JOB_LEASE_S` | `--lease-seconds` | `1800` | Lease length; an expired lease is requeued. |
| `TAIGA_JOB_MEMORY_MB` | `--memory-mb` | `1024` | Address-space limit for one job; `0` disables it. Use `2048` for wav2sleep or SleepECG. |
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
uv sync --frozen --extra sleepecg    # optional Keras + CPU torch + SleepECG
uv add <package>          # update pyproject.toml and uv.lock together
uv run --frozen python -m taiga_worker
```

Both the Docker image and CI use `uv sync --frozen` without the extra, so they
stay small. The extra pins PyTorch from the CPU-only index and `wav2sleep` at a
specific git commit. Do not install packages with `pip` into the worker
environment or edit `uv.lock` by hand.

## API

The whole HTTP contract is `/v1`. [`docs/api.md`](../docs/api.md) is the design (the
conventions and what each resource means); the running backend serves the same thing as
an OpenAPI 3.1 document at `GET /v1/openapi.json` and as Swagger UI at `GET /v1/docs`.
Both are public, generated from the route manifest when the process starts, and work
offline (Swagger UI 5.34.0 is vendored under `api/internal/api/swaggerui/`, Apache-2.0,
see the `LICENSE` there). Nothing is checked in, so there is no generated artifact to
keep in sync; every route is declared once in `api/internal/api/routes.go`, which both
mounts the handler and documents it.

```bash
curl -s http://127.0.0.1:8090/v1/health
# {"status":"ok","time":"..."}
curl -s http://127.0.0.1:8090/v1/version
# {"service":"taiga-sleep","version":"0.3.0","api":2}

TOKEN=$(curl -s -X POST http://127.0.0.1:8090/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"<password>"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -s http://127.0.0.1:8090/v1/me -H "Authorization: Bearer $TOKEN"
# {"id":"...","email":"you@example.com","role":"user"}
```

To use the document elsewhere, point any of these at
`http://127.0.0.1:8090/v1/openapi.json`: **Redoc**, **Postman / Insomnia**
(`Import > Link`; the bearer scheme maps to the `Authorization` header) or the VS Code
*OpenAPI (Swagger) Editor* extension.

PocketBase's own record API is closed: every collection is superuser-only, and password
login through PocketBase's endpoints is disabled. The API above is the only way in for
users and workers. The PocketBase dashboard (`/_/`) is the operator's tool for creating
accounts and inspecting data.

### Recordings and files

A recording is created by its owner with `PUT /v1/recordings/{id}`, where the body is the
recording's `meta.json` and the id is the one the phone chose, so a retry is safe. Files
(`hr.log`, `ecg.log`, `acc.log`, `events.jsonl`) are appended with
`PATCH /v1/recordings/{id}/files/{name}` and an `Upload-Offset` header, resumed with
`HEAD` on the same path, and downloaded with `GET`. Each file is stored as the sender
chose: plain, or as a sequence of complete gzip members (one per chunk, with
`Content-Encoding: gzip`); see [`docs/upload-compression.md`](../docs/upload-compression.md).
`PATCH /v1/recordings/{id}` with `{"complete": true}` seals the recording, after which
nothing in it changes.

The backend stores the bytes and does not look inside them: the worker validates the frame
logs (`docs/recording-format.md`) when it analyses, keeps the valid prefix, and says so in
the analysis warnings. Recordings live in `<pb_data>/recordings/<id>/`, so PocketBase
backups include them. Deleting a recording (`DELETE /v1/recordings/{id}`) removes its
files and analyses.

Limits are configurable through `TAIGA_MAX_RECORDING_MB` (256),
`TAIGA_MAX_RECORDING_FILE_MB` (64), `TAIGA_MAX_RECORDING_UNCOMPRESSED_MB` (512),
`TAIGA_MAX_RECORDING_UNCOMPRESSED_FILE_MB` (128), `TAIGA_MAX_UPLOAD_CHUNK_MB` (8),
`TAIGA_MAX_UPLOAD_UNCOMPRESSED_CHUNK_MB` (16), `TAIGA_MAX_UPLOAD_MEMBERS` (10000) and
`TAIGA_MAX_META_KB` (64). A request over a limit is answered with `413`.

### Analyses and the worker

An analysis is one algorithm applied to one recording. Nothing creates one by itself:
a user asks with `POST /v1/recordings/{id}/analyses` (idempotent on recording, algorithm,
version and params; asking again for a failed one is how a retry is made). The analysis
row is the queue item, so there is no separate jobs resource:

1. The analysis is `queued`.
2. The worker announces its algorithms with `PUT /v1/workers/me`, then
   `POST /v1/analyses/claim` takes the oldest queued analysis it can run. The update is
   conditional inside a transaction, so exactly one caller gets it. The analysis is
   `running` for a lease (`TAIGA_JOB_LEASE_S`, default 1800 s).
3. While it holds the lease, the worker may read the recording and its files through the
   normal read endpoints (there are no worker-only copies).
4. `POST /v1/analyses/{id}/complete` stores the result; `POST /v1/analyses/{id}/fail`
   reports a failure. A retryable failure is requeued with exponential backoff up to
   `TAIGA_JOB_MAX_ATTEMPTS` (default 3); a permanent one stores the reason.
5. Expired leases are put back in the queue (or failed when the attempts are used up) on
   every claim and by the `taiga_requeue_expired_analyses` cron every minute.

The result is the worker's output as documented in
[`docs/analysis-contract.md`](../docs/analysis-contract.md); the backend checks its
envelope only and derives a small `summary` for lists.

### Collections

All collections are superuser-only through PocketBase's own API.

- `users` — built-in auth for app accounts. Registration is closed.
- `workers` — auth collection for worker service accounts: `name`, `version`,
  `algorithms`, `last_seen`.
- `recordings` — one row per recording. The record id is the client's recording id.
  `owner`, `state`, `device`, `started_at`, `ended_at`, `duration_s`, `complete`, `meta`
  (the recording's meta.json), `files` (per-file encoding and uncompressed size), plus
  `created` and `updated`.
- `analyses` — one row per `(recording, algorithm, version, params_hash)`: `recording`,
  `algorithm`, `version`, `params`, `params_hash`, `status`, `error`, `attempts`,
  `worker`, `available_at`, `lease_until`, `finished_at`, `summary`, `result`, plus
  timestamps.

Upgrading from API 1 (sessions, jobs): the migration `1760000000_api_v1` carries
recordings and finished analyses over, renames the recording directories and drops the
jobs. Recordings without their `meta.json` on disk are skipped.

## Tests

Go unit tests cover the OpenAPI document, meta validation, gzip framing, the migration and the HTTP behaviour of the routes:

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
It covers uploads and resuming, analysis requests, the worker queue and its leases,
leased worker reads, the baseline end-to-end run, result storage, retries, the served
OpenAPI document and docs page, and owner isolation. Set `POCKETBASE_BIN` to the
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

# Published images, rebuilt from every push to main:
docker compose --env-file deploy/.env -f deploy/docker-compose.yml pull
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --no-build
```

The published images are `ghcr.io/rbmr/taigasleep-api` and
`ghcr.io/rbmr/taigasleep-worker`. Each backend release is tagged twice:

- `:latest` follows the newest backend build;
- `:v<version>`, for example `:v0.1.0`, is immutable, so pin it in
  `deploy/docker-compose.yml` for a reproducible deployment.

The version, and the API revision it speaks, live in
[`backend/version.properties`](version.properties); see
[`docs/versioning.md`](../docs/versioning.md). An app refuses a backend that
speaks a different API revision, and says which side to update.

To build both images from source instead, use `up -d --build`. The API image
builds the Go binary from `backend/api`; the worker image installs the locked
Python dependencies. The API port is published on loopback by default, and the
data directory is a named volume. The worker has a configurable container limit
(`TAIGA_WORKER_MEMORY`) with the job subprocess limit slightly below it
(`TAIGA_JOB_MEMORY_MB`). On the first start, `TAIGA_ADMIN_EMAIL` and
`TAIGA_ADMIN_PASSWORD` can bootstrap the admin account; remove them afterwards.

### Admin access

Do not expose the PocketBase dashboard (`/_/`) to the internet. The included
`Caddyfile` proxies only `/v1/*` and returns 404 for everything else:

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
Swagger UI for `/v1/docs`; it is read-only data that the idle server
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
4. Fix ownership, start the API, then check `/v1/health` and one recording.
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
5. Check `/v1/health`, the cron list and one worker claim.

Keep the worker and API versions from the same commit. The worker only uses the
public API, but the job payload shape is expected to stay in sync.

Read the per-release notes for the version you are installing before you start:
they live in [`docs/releases.md`](../docs/releases.md#upgrade-notes). No entry
means there is nothing to do beyond this procedure.

### Stale analysis results after an upgrade

An analysis is keyed by recording, algorithm, version and params. The backend
stores one row per key, and `POST /v1/recordings/{id}/analyses` returns the
existing row when all four match. A release that changes the shape of a result
without changing the algorithm version therefore keeps the old analysis: the
worker never recomputes a succeeded run, and the API keeps serving the old
payload. The app downloads a succeeded result when the run it shows has no
cached payload for that id, and never downloads that id again, so changing a
status in the database does not refresh it either.

The app asks for a run only after an upload (when automatic analysis is on) or
when the user asks for one from the night's analysis sheet. It does not look for
runs that are missing, so an affected night is not repaired by opening the app.

After such an upgrade, a night that was analysed before it can be ready and
still show no sleep score and no statistics, or only the statistics the old
result carried. The server lists its analysis as `succeeded`; it is not queued,
and nothing will recompute it. The same happens to any night whose stored result
predates the fields the current app reads.

To rerun a night, delete its analyses and request them again. The new request
creates analyses with new ids, which the app has not downloaded, so it fetches
the new results. Do not reset a row's status instead: the app keeps the result
of that id and would never download it again.

Deleting a run does not touch the recording or its files, and no app data needs
to be cleared. The app hears about the delete over the event stream, or reads it
at the next open: the run leaves the night's list and History shows the night as
processing again. The payload it had already downloaded stays on the phone under
the old id; the new run has a different id, so that payload is not used for it.
The app does not request a replacement on its own; automatic
analysis only asks for a run as part of an upload. Request the new run with one
of the routes below, or in the app by opening the night and choosing an analysis.
A run that succeeds is downloaded while the app is open (events) or at the next
open, because its id is new.

#### Rerun a recording with the user's token

This route replaces the runs of one recording and needs no superuser. `jq` is
used only to read the token and the ids.

```bash
BASE=http://127.0.0.1:8090
RECORDING_ID=<recording id>
ALGORITHM=baseline

TOKEN=$(curl -fsS -X POST "$BASE/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"<password>"}' | jq -r .token)

# 1. See the runs of this recording.
curl -fsS "$BASE/v1/analyses?recording=$RECORDING_ID" \
  -H "Authorization: Bearer $TOKEN" \
  | jq -r '.items[] | [.id, .algorithm, .version, .status] | @tsv'

# 2. Delete all of them. Deleting and requesting again is the replacement; a
#    status update is not.
curl -fsS "$BASE/v1/analyses?recording=$RECORDING_ID" \
  -H "Authorization: Bearer $TOKEN" \
  | jq -r '.items[].id' \
  | while read -r id; do
      curl -fsS -X DELETE "$BASE/v1/analyses/$id" \
        -H "Authorization: Bearer $TOKEN"
    done

# 3. Ask for a new run. Add "version" and "params" if the old run used anything
#    other than the newest worker version and the default parameters.
curl -fsS -X POST "$BASE/v1/recordings/$RECORDING_ID/analyses" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"algorithm\":\"$ALGORITHM\"}"
```

The response is the queued analysis with its new id, and the worker claims it as
usual. To replace the runs of every recording, use the loop below after the
deletions; it requests the chosen algorithm for every complete recording and
leaves recordings that are still uploading alone. It also adds a run to a
complete recording that had none, so skip it when only particular nights should
be replaced.

```bash
curl -fsS "$BASE/v1/recordings?complete=true&limit=200" \
  -H "Authorization: Bearer $TOKEN" \
  | jq -r '.items[].id' \
  | while read -r id; do
      curl -fsS -X POST "$BASE/v1/recordings/$id/analyses" \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{\"algorithm\":\"$ALGORITHM\"}"
    done
```

#### Rerun from the operator side with the superuser token

PocketBase's collection API is the operator's route to the same rows. It is
superuser-only, and a superuser token cannot call `/v1`; use it to delete, then
request the new runs with the user's token above or in the app.

```bash
BASE=http://127.0.0.1:8090
RECORDING_ID=<recording id>
ANALYSIS_ID=<analysis id>

ADMIN=$(curl -fsS -X POST "$BASE/api/collections/_superusers/auth-with-password" \
  -H 'Content-Type: application/json' \
  -d '{"identity":"admin@example.com","password":"<password>"}' | jq -r .token)

# The runs of one recording, with the key they are stored under.
curl -fsS -G "$BASE/api/collections/analyses/records" \
  -H "Authorization: $ADMIN" \
  --data-urlencode "perPage=200" \
  --data-urlencode "fields=id,recording,algorithm,version,status" \
  --data-urlencode "filter=(recording='$RECORDING_ID')" \
  | jq -r '.items[] | [.id, .recording, .algorithm, .version, .status] | @tsv'

# Delete one run...
curl -fsS -X DELETE "$BASE/api/collections/analyses/records/$ANALYSIS_ID" \
  -H "Authorization: $ADMIN"

# ...or every run of the recording.
curl -fsS -G "$BASE/api/collections/analyses/records" \
  -H "Authorization: $ADMIN" \
  --data-urlencode "perPage=200" \
  --data-urlencode "fields=id" \
  --data-urlencode "filter=(recording='$RECORDING_ID')" \
  | jq -r '.items[].id' \
  | while read -r id; do
      curl -fsS -X DELETE "$BASE/api/collections/analyses/records/$id" \
        -H "Authorization: $ADMIN"
    done
```

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

Accuracy claims without ground-truth labels and the app-side upload and result
UI (#11). The data-quality score and the sleep score are product policies over
stages and metrics, implemented in the shared derive layer rather than in a
stager. Optional compressed uploads (#24) are implemented and documented in
[`docs/upload-compression.md`](../docs/upload-compression.md); they are the
app's default and can be turned off.

