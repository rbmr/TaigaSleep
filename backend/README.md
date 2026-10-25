# Backend

Self-hosted PocketBase API plus a Python analysis worker for Taiga Sleep.

This directory contains everything the optional backend needs: the database
schema, owner rules, health checks, the durable analysis queue, the worker
skeleton, deployment examples and backup tooling. It accepts append-only
recordings through an owner-only upload API. The analysis contract, result
endpoints and worker download path are the next slices of issue #10.

## What is here

```
backend/
├── pocketbase/
│   ├── Dockerfile
│   ├── docker-entrypoint.sh
│   ├── pb_hooks/               # PocketBase JS hooks (routes, cron, queue)
│   └── pb_migrations/          # versioned collections and settings
├── worker/
│   ├── Dockerfile
│   ├── pyproject.toml
│   ├── taiga_worker/           # supervisor, analysis library and baseline stager
│   └── tests/
├── tests/test_integration.py   # real PocketBase + real worker process
├── deploy/
│   ├── docker-compose.yml
│   ├── .env.example
│   ├── Caddyfile
│   └── systemd/
└── scripts/                    # online and offline backups
```

PocketBase is the only process that touches the SQLite database and the stored
files. The worker talks to the API only, so it can run on the same machine or on
a larger one without code changes.

## Requirements

- PocketBase **0.40.6** (pinned; the hooks use its current JS API).
- Python **3.10+** and [uv](https://docs.astral.sh/uv/) for the worker. The
  worker uses numpy for decoding, metrics and the baseline stager; the idle
  supervisor does not import it, and real ML models are optional extras.
- Optional: Docker Compose for the container deployment.

## Quick start (without Docker)

```bash
cd backend/pocketbase

# Download the pinned server binary.
curl -fsSL -o pocketbase.zip \
  https://github.com/pocketbase/pocketbase/releases/download/v0.40.6/pocketbase_0.40.6_linux_amd64.zip
unzip -q pocketbase.zip pocketbase
chmod +x pocketbase

# Apply the schema into a local data directory and start the API.
./pocketbase serve \
  --http=127.0.0.1:8090 \
  --dir=./pb_data \
  --migrationsDir=./pb_migrations \
  --hooksDir=./pb_hooks \
  --automigrate=false
```

Open `http://127.0.0.1:8090/_/` and create the first admin account, or run
`./pocketbase superuser upsert <email> <password>`. The dashboard is **not**
meant to be exposed to the internet; see [Admin access](#admin-access).

The schema is applied automatically on `serve`. To apply it without serving:

```bash
./pocketbase migrate up --dir=./pb_data --migrationsDir=./pb_migrations
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

It logs in, polls the claim endpoint, and sleeps when the queue is empty. One
job at a time runs in a fresh subprocess.

The analysis library lives in `taiga_worker/analysis/`: the stager contract,
recording decoder, shared metrics/quality and the built-in `baseline` stager.
The job subprocess is wired to it in the next slice of #10. See
[`docs/analysis-contract.md`](../docs/analysis-contract.md) for the plugin
interface, canonical stages, result shape and a minimal third-party example.

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
| `TAIGA_HOOKS_POOL` | `4` | Prewarmed JS runtimes. The deployment examples use 4 instead of PocketBase's default 15 to keep idle memory down. |
| `TAIGA_MAX_RECORDING_MB` | `256` | Total bytes allowed for one recording directory. |
| `TAIGA_MAX_RECORDING_FILE_MB` | `64` | Bytes allowed for one stream file. |
| `TAIGA_MAX_UPLOAD_CHUNK_MB` | `8` | Maximum upload chunk; keep it below PocketBase's 10 MB request-body cap. |
| `TAIGA_MAX_META_KB` | `64` | Maximum `meta.json` size. |

The API also reads `TAIGA_JOB_LEASE_S` as the default claim lease (default
`1800`, clamped to 1 day).

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
| `TAIGA_JOB_MEMORY_MB` | `--memory-mb` | `1024` | Address-space limit for one job; `0` disables it. |
| `TAIGA_JOB_TIMEOUT_S` | `--timeout` | `3600` | Wall-clock limit for one job. |
| `TAIGA_LOG_LEVEL` | `--log-level` | `INFO` | Python log level. |

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
uv add <package>          # update pyproject.toml and uv.lock together
uv run --frozen python -m taiga_worker
```

Both the Docker image and CI use `uv sync --frozen`. Do not install packages
with `pip` into the worker environment or edit `uv.lock` by hand.

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
  include them. Deleting a session removes its directory.

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

### Collections

- `users` — built-in auth. Registration is closed (`createRule` is null).
  Users can only list/view/update/delete their own record.
- `sessions` — one row per recording: `owner`, `client_session_id`, `device`,
  `started_at`, `ended_at`, `duration_s`, `state`, `upload_status`, `upload`
  (per-file lengths and validation state), `uploaded_at`, `upload_error`,
  analysis fields (`analysis`, `stages`, `metrics`, `quality`, `error`), plus
  `created` and `updated`. The analysis fields are the #8 placeholder; the
  analysis collection replaces them in the next slice of #10. Owner-only rules.
- `jobs` — durable queue: `session`, `kind`, `model`, `status`, `attempts`,
  `available_at`, `lease_until`, `worker`, `error`, `result`, plus timestamps.
  Owner-only rules evaluated through `session.owner`.
- `workers` — auth collection for the worker service account. Public create,
  list, update and delete are disabled; it cannot read sessions or jobs
  directly.

### Job flow

1. The app (or an admin) creates a job for a session. The hook forces
   `status=queued`, `attempts=0`, `available_at=now`, and clears the lease.
   Creation is idempotent on `(session, model, kind)`: a repeat returns the
   existing row. A unique index is the race-safe backstop.
2. A worker calls `POST /api/taiga/worker/jobs/claim` with its worker id and an
   optional lease. The hook selects the oldest eligible job and updates it
   conditionally inside a transaction, so exactly one caller gets it.
3. The worker runs the job and reports `done` or `failed` through
   `POST /api/taiga/worker/jobs/{id}/result`. Retried reports are harmless.
4. Every minute the `taiga_requeue_expired_jobs` cron requeues `running` jobs
   whose lease has expired. `attempts` is preserved for observability.
   `POST /api/taiga/worker/jobs/requeue` runs the same operation on demand.

All `/api/taiga/worker/*` routes require a `workers` token. A normal user token
is rejected.

## Tests

Worker unit tests use the standard library plus numpy. Dependencies and the
exact interpreter are managed by uv from `backend/worker/uv.lock`:

```bash
cd backend/worker
uv sync --frozen
uv run --frozen python -m unittest discover -s tests -t .
```

The integration suite starts a real PocketBase server in a temporary directory,
applies the migrations, and exercises the hooks and a real worker subprocess.
Set `POCKETBASE_BIN` to the binary (or put it on `PATH`):

```bash
cd backend
uv sync --project worker --frozen
POCKETBASE_BIN=/path/to/pocketbase \
  uv run --project worker --frozen python -m unittest tests.test_integration -v
```

Set `TAIGA_KEEP_TEST_DATA=1` to keep the temporary data directory after a run.

CI (`.github/workflows/backend.yml`) runs both suites with uv on every pull
request that touches `backend/`.

## Deployment

### systemd

The units assume:

- the binary and assets live in `/opt/taiga-sleep`;
- data lives in `/var/lib/taiga-sleep` (`StateDirectory`);
- secrets live in `/etc/taiga-sleep/*.env`, owned by root and mode `0640`.

```bash
sudo useradd --system --home /var/lib/taiga-sleep --shell /usr/sbin/nologin taiga
sudo install -d -o taiga -g taiga /opt/taiga-sleep
# copy pocketbase, pb_hooks/ and pb_migrations/ into /opt/taiga-sleep
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

The API port is published on loopback by default, and the data directory is a
named volume. The worker has a configurable container limit
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

Measured on x86_64 Linux with PocketBase 0.40.6 (`--hooksPool=4`) and the
worker idle, using `ps -o rss=` (resident set size, including shared pages):

| Process | Idle RSS |
| --- | --- |
| PocketBase (`serve`, hooks loaded, no requests) | ~44 MiB |
| Worker supervisor (Python 3.12, polling an empty queue) | ~25 MiB |

For comparison, PocketBase's default `--hooksPool=15` adds JS runtimes that a
single-user install does not need. Raising `TAIGA_HOOKS_POOL` buys concurrency,
not throughput for this workload.

The analysis subprocess is the only heavy part. Its limit is configurable via
`TAIGA_JOB_MEMORY_MB`, and numeric libraries are single-threaded.

## Backup and restore

Back up the **whole data directory**, not just `data.db`: recordings live under
`pb_data/recordings/` (and PocketBase's own uploaded files under
`pb_data/storage/`). The provided script uses PocketBase's backup API, which
snapshots the database and files together:

```bash
cd backend
TAIGA_API_URL=http://127.0.0.1:8090 \
TAIGA_ADMIN_EMAIL=you@example.com \
./scripts/backup.sh /srv/backups
```

It downloads `taiga-sleep-backup-<timestamp>.zip`, validates it, and removes the
server-side copy. Use `--keep-remote` to keep it. For a stopped instance (or a
consistent filesystem snapshot), use:

```bash
./scripts/backup-offline.sh /var/lib/taiga-sleep /srv/backups
```

Restore with the service stopped:

1. Move the current data directory aside.
2. Extract the archive so that `data.db`, `auxiliary.db` and `storage/` are at
   the top level of the data directory.
3. Fix ownership (`taiga:taiga`) and start the service.

PocketBase can also restore an uploaded backup from the dashboard, but that
restarts the process. Verify a restore periodically on a scratch machine; an
unverified backup is not a backup.

## Upgrade

1. Back up first.
2. Stop the API and worker.
3. Replace the PocketBase binary (or image tag) and update `pb_hooks/` and
   `pb_migrations/` from the new release.
4. Start the API; new migrations apply automatically.
5. Check `/api/taiga/health`, the cron list and one worker claim.

Keep the worker and API versions from the same commit. The worker only uses the
public API, but the job payload shape is expected to stay in sync.

## Security notes

- No secrets, tokens or recordings are stored in this repository. `.env` files,
  `pb_data/`, `backups/` and Python caches are ignored.
- Registration is disabled; there is one app user in the intended setup.
- Worker credentials are separate from the app user. The worker cannot read
  records directly.
- The admin dashboard is loopback-only in the examples.
- The API is HTTP by default. Terminate TLS at the reverse proxy (Caddy does so
  automatically) before using it outside your LAN.

## Out of scope

Receiving recordings, downloading them in the worker, sleep staging, result
endpoints and the app-side connection screen. Those are #9 and #10.
