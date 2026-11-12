# API v1

The HTTP contract of the Taiga Sleep backend. The running backend serves the same thing
as an OpenAPI document at `GET /v1/openapi.json` and as browsable documentation at
`GET /v1/docs`; a test fails when a route and the document disagree, so the document is
complete. This page is the design: the conventions every endpoint follows and what each
resource means. See [`architecture.md`](architecture.md) for why it is shaped this way.

The contract revision is `api` in `backend/version.properties` and is reported by
`GET /v1/version`. The `/v1` in the path is the style generation of the API, not the
contract revision; a breaking change bumps `api` (see [`versioning.md`](versioning.md)).

## Conventions

- **Recordings and analyses are PocketBase records.** They are read and written through
  PocketBase's record API at `/api/collections/{collection}/records`, and the
  collection rules decide what a caller may see and change. Everything else is under
  `/v1`.
- **Resources are nouns, methods are verbs.** `GET` reads, `POST` creates, `PATCH`
  changes part of a resource, `DELETE` removes it. The file append route is the one
  `PATCH` that adds bytes.
- **JSON, snake_case, timestamps as PocketBase stores them for records** (`YYYY-MM-DD
  HH:MM:SS.sssZ`) and RFC 3339 in `/v1` answers. File bytes and the event stream are the
  only non-JSON responses.
- **Authentication** is `Authorization: Bearer <token>` from PocketBase's auth route of
  the account's collection, or a read-only access token from `POST /v1/tokens`.
- **Ownership.** A user only ever sees their own recordings and analyses. Anything that
  belongs to someone else is `404`, never `403`, so ids do not leak. A worker sees a
  recording (and its analyses) only while it holds a live lease of one of its analyses.
- **Lists** are PocketBase's: `page` (1-based), `perPage` (30 by default, at most 1000),
  `filter`, `sort`, `expand`, `fields` and `skipTotal`. The answer is
  `{ "page", "perPage", "totalItems", "totalPages", "items" }`.
- **One error shape**, PocketBase's, for every error from every route:

  ```json
  { "status": 409, "message": "the recording is complete and can no longer change", "data": { "code": "recording_complete" } }
  ```

  `status` repeats the HTTP status, `message` is for a person, and `data` carries the
  API's stable lowercase code under `code` where a route has one. PocketBase's rule and
  field validation failures have an empty `data` or per-field entries, so a client maps
  those by status. The codes a client acts on:

  | Code | Status | Meaning |
  | --- | --- | --- |
  | `not_found` | 404 | The resource does not exist, is not yours, or a worker does not hold its lease. |
  | `id_taken` | 409 | That recording id belongs to another account. |
  | `recording_incomplete` | 409 | An analysis was asked for before the recording was sealed. |
  | `recording_in_progress` | 409 | The recording is not finished: it is still being recorded, or the rest of it is still on the tracker (`fetch_pending`). |
  | `recording_complete` | 409 | A sealed recording can no longer change. |
  | `no_data` | 422 | The recording has no stream file, so it cannot be sealed. |
  | `invalid_meta` | 400, 422 | The recording's metadata is not valid or does not match its id. |
  | `invalid_params` | 400, 422 | The analysis parameters are not an object, or do not match the algorithm's announced schema. |
  | `offset_mismatch` | 409 | An upload chunk does not start where the stored file ends; `Upload-Offset` says where it does. |
  | `encoding_mismatch` | 409 | The chunk's encoding differs from the file's. |
  | `chunk_too_large` | 413 | The chunk is over the server's chunk limit. |
  | `file_too_large` | 413 | The file is over the server's per-file limit. |
  | `recording_too_large` | 413 | The recording is over the server's per-recording limit. |
  | `too_many_members` | 413 | A compressed file has too many gzip members. |
  | `storage_quota_exceeded` | 413 | The chunk would take the account over its storage quota (`TAIGA_USER_QUOTA_GB`); deleting a recording frees room. |
  | `unknown_algorithm` | 422 | No registered worker offers that algorithm and version. |
  | `invalid_result` | 422 | A worker's result does not match the analysis or the contract envelope; the analysis is failed. |
  | `not_leased` | 409 | The worker no longer holds the lease of the analysis. |

  The other codes name a malformed request on a custom route or in a hook:
  `invalid_body`, `invalid_algorithm`, `invalid_version`, `invalid_offset`,
  `invalid_gzip`, `unsupported_encoding`, `empty_chunk`, `unreadable_body`,
  `meta_too_large`, `too_many_algorithms`.

  A missing or invalid token (`401`), a token of a role the route does not take
  (`403`), a rate limit (`429`) and a server failure (`500`) come from PocketBase's
  middleware or from the error handler; they carry an empty `data`, so the status is the
  answer.
- **Idempotency.** Creating a recording is `POST` with the client-chosen id; repeating
  it for an open recording replaces the metadata, so a retry after a lost response is
  safe. Requesting an analysis that already exists returns the existing one.
- **Caching.** `GET /v1` responses are `Cache-Control: no-store`.

## System

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /v1/health` | public | Liveness: `{ "status": "ok", "time": ... }` |
| `GET /v1/version` | public | `{ "service": "taiga-sleep", "version": "0.6.21", "api": 6 }`. A client compares `api` with the one it was built for before it does anything else. |
| `GET /v1/openapi.json` | public | The OpenAPI 3.1 document of this build, including the record API generated from the collection schema. |
| `GET /v1/docs` | public | Browsable documentation of the same. |

## Authentication

Users and workers log in through PocketBase's auth routes for their collection; the
backend is PocketBase, so it serves them. The answer is PocketBase's
`{ "token", "record": { "id", "email", ... } }`, and `record.id` is the account.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `POST /api/collections/users/auth-with-password` | public | Body `{ "identity": "<email>", "password" }`. A user token. Repeated requests from one client address are answered `429`. |
| `POST /api/collections/users/auth-refresh` | user | A fresh token for the current one, same shape. Also how a client checks that a stored token is still valid. |
| `POST /api/collections/workers/auth-with-password` | public | The same for the worker account. |
| `POST /api/collections/workers/auth-refresh` | worker | A fresh worker token. |

Accounts are created by the operator in the admin UI. Sign-up is closed for both
collections, and their record APIs are closed. The former `/v1/auth/*` routes no longer
exist.

## Access tokens

A user can make a **read-only access token** for their own assistant or script, so it can
read their data without their password and without being able to change anything. It is a
PocketBase static auth token of the `access_tokens` collection: it is shown once, when it
is created, and the server keeps the record, not the secret. See
[`assistant-access.md`](assistant-access.md) for how to use one.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `POST /v1/tokens` | user | Body `{ "name" }`. Answers `201` with the token (`id`, `name`, `hint`, `created`, `token`), which is the only time `token` is in an answer. |
| `GET /v1/tokens` | user | The account's tokens without the secret (`id`, `name`, `hint`, `created`). |
| `DELETE /v1/tokens/{id}` | user | Revokes it by deleting its record; it stops working at once. `204`. |

A token may read through the record API for its owner: their recordings (metadata, not the
raw files), analyses and results. Every write is refused: a record create rule refuses the
collection, an update or delete rule hides the record, and the `/v1` routes answer `403`.
A recording or analysis of another account is `404`, like any other caller's. A revoked
token has no identity any more, so the record API answers as it does to a caller with no
token (and the custom routes answer `401`).

## Recordings

A recording is created by its owner at an id the client chooses (`[A-Za-z0-9_-]`, 1 to 40
characters, globally unique), so the phone's id for a recording is also the backend's.
The create body carries the id and the recording's `meta.json`; the owner, state, device,
times, duration and file totals are derived from it by the server.

```json
{
  "id": "20261023_223000-k3f9",
  "owner": "k3f9a1b2c3d4e5f",
  "state": "stopped",
  "device": "Polar H10 069A4737",
  "started_at": "2026-10-23 20:30:00.000Z",
  "ended_at": "2026-10-24 05:41:12.000Z",
  "duration_s": 33072,
  "complete": true,
  "stored_bytes": 28412345,
  "meta": { "format": "taiga-recording", "version": 1, "...": "see recording-format.md" },
  "files": { "hr.log": { "encoding": "gzip", "uncompressed_size": 123, "members": 3 } },
  "created": "2026-10-24 05:50:01.123Z",
  "updated": "2026-10-24 05:52:40.456Z"
}
```

`meta` is the recording's `meta.json` verbatim (fields the backend does not know are kept);
`state`, `device`, the times and the duration are derived from it. `complete` means all
files have been uploaded and the recording is sealed. `stored_bytes` is what its files
take up, which is what the account's quota counts.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /api/collections/recordings/records` | user, token, worker with a lease | List the recordings the caller may see. PocketBase `filter`, `sort`, `page`, `perPage`, `expand`. |
| `POST /api/collections/recordings/records` | user | Body `{ "id", "meta" }`. Creates (`200`) or replaces the metadata of an open recording (`200`). `409 recording_complete` once sealed, `409 id_taken` if another user has the id, `422/400 invalid_meta` if the metadata is invalid or its `session_id` is not the id. |
| `GET /api/collections/recordings/records/{id}` | user, token, worker with a lease | The recording. |
| `PATCH /api/collections/recordings/records/{id}` | user | Body `{ "meta" }` replaces the metadata while open; `{ "complete": true }` seals it. `409 recording_in_progress` if `meta.state` is still `recording` or `fetch_pending`, `422 no_data` if it has no stream file. A sealed recording cannot change, and no other field can be patched. |
| `DELETE /api/collections/recordings/records/{id}` | user | Deletes the recording, its files, its analyses and its bytes from the account's total. `204`. |

## Recording files

Raw data, stored verbatim: `hr.log`, `ecg.log`, `acc.log`, `events.jsonl`. The backend
does not interpret them; checking frames and CRCs is the worker's job at analysis time.

The sender chooses, per file, whether to send it compressed. A compressed file is a
sequence of complete gzip members, one per chunk, so appending stays valid.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /v1/recordings/{id}/files` | user, worker with a lease | `{ "items": [{ "name", "size", "encoding", "uncompressed_size" }] }`. `size` is stored bytes. |
| `HEAD /v1/recordings/{id}/files/{name}` | user, worker with a lease | `Upload-Offset` is the stored size, i.e. where to resume. `Content-Encoding` is the file's encoding. `404` for a file that does not exist yet. |
| `PATCH /v1/recordings/{id}/files/{name}` | user | Appends the body. `Upload-Offset` must equal the stored size, else `409 offset_mismatch` with the current `Upload-Offset` in the response. `Content-Encoding: gzip` for a gzip member, absent for plain bytes; a file's encoding is fixed by its first chunk. Offset 0 creates the file. `204` with `Upload-Offset` set to the new size. `409 recording_complete`, `413 chunk_too_large`, `413 storage_quota_exceeded`. |
| `GET /v1/recordings/{id}/files/{name}` | user, worker with a lease | The file. A compressed file is inflated unless the request says `Accept-Encoding: gzip`, in which case it is sent as stored with `Content-Encoding: gzip`. |
| `DELETE /v1/recordings/{id}/files/{name}` | user | Removes one file, e.g. to restart it. `409` once complete. |

Limits (total size, chunk size, expansion ratio) are server configuration, documented in
the backend README, and answered with `413`. An account also has a storage quota
(`TAIGA_USER_QUOTA_GB`, 10 GB by default, `0` for none): a chunk that would push the
stored bytes of all its recordings over the quota is refused with
`413 storage_quota_exceeded`, and deleting a recording frees the room again.

## Analyses

An analysis is one algorithm applied to one recording with some parameters. It is created
once, filled in by the worker, and then never changes: users can create, read and delete,
not update.

```json
{
  "id": "a1b2c3d4e5f6g7h",
  "recording": "20261023_223000-k3f9",
  "algorithm": "wav2sleep",
  "version": "0.1.0",
  "params": {},
  "params_hash": "1a2b3c4d5e6f7a8b",
  "status": "succeeded",
  "error": "",
  "attempts": 1,
  "created": "2026-10-24 05:52:40.123Z",
  "updated": "2026-10-24 05:58:03.456Z",
  "finished_at": "2026-10-24 05:58:03.456Z",
  "summary": { "...": "headline figures, small" },
  "result": { "contract_version": 3, "...": "see analysis-contract.md" }
}
```

`status` is `queued`, `running`, `succeeded` or `failed`; the last two are terminal.
`summary` is a small, derived headline (scores and the stage segments) so a client need
not derive it from the full `result`; it is `null` until the analysis has succeeded, and
`result` is `null` until then. `params_hash` is the server's hash of the parameters.
`result` is the worker's output, validated for its envelope only (`contract_version` and
the fields the contract requires). Its shape is versioned and documented in
[`analysis-contract.md`](analysis-contract.md), and it is flexible on purpose: what is
present depends on the data and the algorithm.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `POST /api/collections/analyses/records` | user | Body `{ "recording", "algorithm", "version"?, "params"? }`. The version defaults to the newest a worker offers, ignoring workers that stopped announcing themselves (`TAIGA_WORKER_TTL_S`, 900 s) unless none is left. Answers `200` with a queued analysis, or with the existing one for the same recording, algorithm, version and parameters. A failed existing one is replaced, which is how a retry is made. `409 recording_incomplete` before the recording is sealed, `422 unknown_algorithm` when no worker offers it, `422 invalid_params` when the parameters do not match its announced schema. |
| `GET /api/collections/analyses/records` | user, token, worker with a lease | List the analyses the caller may see. PocketBase `filter`, `sort`, `page`, `perPage`. |
| `GET /api/collections/analyses/records/{id}` | user, token, worker with a lease | One analysis, with `summary` and `result`. A worker only while it holds the lease. |
| `DELETE /api/collections/analyses/records/{id}` | user | Removes it; a running one is cancelled, and the worker's next call about it gets `404`. `204`. |

An analysis cannot be changed through the record API: its update rule allows no client.
The worker queue's own routes are what fill it in.

## Algorithms

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /v1/algorithms` | user | What registered workers can run: id, version, name, description, the streams it needs, the stages it can produce, parameter schema, citation and licence (the manifest of `analysis-contract.md`). |

## Worker

The worker is a client with the `worker` role. It reads recordings and analyses through
the record API plus these routes.

There are no worker-only copies of recording or file routes, and a worker sees nothing
outside the lease: while it holds a `running` analysis it may read that analysis, its
recording and the recording's files, and nothing else. Everything else is `404`, so ids
do not leak.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `PUT /v1/workers/me` | worker | Body `{ "name", "version", "algorithms": [...] }`: announces what this worker can run. Refreshes its last-seen time. |
| `POST /v1/analyses/claim` | worker | Body `{ "algorithms"?: [{ "id", "version"? }], "lease_seconds"? }`. Atomically takes the oldest queued analysis it can run and marks it `running`: `200` with the analysis (including `recording` and `params`), or `204` when there is nothing to do. |
| `POST /v1/analyses/{id}/lease` | worker | Body `{ "lease_seconds"? }`. Extends the lease on an analysis this worker is running: `200` with the analysis. `409 not_leased` when the analysis was reassigned or the lease expired, and `404` when it is gone. |
| `POST /v1/analyses/{id}/complete` | worker | Body `{ "result": {...} }`. Stores the result and marks it `succeeded`. `409 not_leased` if the worker no longer holds the lease. |
| `POST /v1/analyses/{id}/fail` | worker | Body `{ "error", "retryable"? }`. A retryable failure goes back to `queued` until its attempts are used up; otherwise the analysis is `failed`. |

A lease that expires puts the analysis back in the queue; the backend does this itself
(on every claim and once a minute), so there is no requeue endpoint. A job that can run
longer than one lease extends it while it works, so an analysis is not requeued under a
worker that is still busy with it.

## Events

`GET /v1/events` is a Server-Sent Events stream for the caller. A comment line (`:`) is
sent every 20 seconds so intermediaries keep the connection open.

```text
event: analysis
data: {"id": "a1b2c3d4e5f6g7h", "recording": "20261023_223000-k3f9", "status": "succeeded"}

event: recording
data: {"id": "20261023_223000-k3f9", "change": "deleted"}
```

- `analysis` events carry the analysis status (`queued`, `running`, `succeeded`, `failed`)
  or `deleted`; `recording` events carry `change` (`created`, `updated`, `deleted`); `updated` means the metadata changed or the recording was sealed, not that a chunk arrived.
- A **user** receives events for their own recordings and analyses.
- A **worker** receives `analysis` events with `status: "queued"`, which is its signal to
  claim; it also claims once on connecting, because events are not replayed.
- Events carry ids and a status, never data. A client that missed one fetches the
  resource. Clients reconnect on their own and treat a reconnect as "refetch what I show".

## Open on purpose, closed on purpose

The record API of `recordings` and `analyses` is open, with the collection rules above.
Every other collection's record API stays closed: `users`, `workers`, `access_tokens` and
the system collections are superuser-only. The PocketBase admin UI (`/_/`) remains the
operator's tool for creating accounts and inspecting data.
