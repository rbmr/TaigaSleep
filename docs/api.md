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

- **Resources are nouns, methods are verbs.** `GET` reads, `PUT` creates or replaces at a
  known id, `POST` creates at a server-chosen id or performs an action that is not CRUD,
  `PATCH` changes part of a resource, `DELETE` removes it. `GET`, `PUT`, `DELETE` and
  `HEAD` are idempotent.
- **JSON, snake_case, RFC 3339 UTC timestamps.** File bytes and the event stream are the
  only exceptions.
- **Authentication** is `Authorization: Bearer <token>` from `POST /v1/auth/login`.
  Endpoints say whether they take a `user`, a `worker` or are public.
- **Ownership.** A user only ever sees their own recordings and analyses. Anything that
  belongs to someone else is `404`, never `403`, so ids do not leak.
- **Status codes.** `200` with a body, `201` with a body and `Location` when something was
  created, `204` without a body, `400` malformed request, `401` not authenticated, `403`
  wrong role, `404` not found or not yours, `409` the resource is in a state that does not
  allow this, `413` too large, `422` well-formed but invalid, `5xx` ours.
- **One error shape**, for every error from every endpoint:

  ```json
  { "code": "recording_complete", "message": "the recording is complete and can no longer change" }
  ```

  `code` is a stable lowercase identifier a client may switch on, `message` is for a
  person. The codes a client acts on:

  | Code | Status | Meaning |
  | --- | --- | --- |
  | `unauthorized` | 401 | No token, or a token the server does not accept. |
  | `forbidden` | 403 | The token's role may not call this endpoint. |
  | `not_found` | 404 | The resource does not exist, is not yours, or a worker does not hold its lease. |
  | `too_many_attempts` | 429 | Too many failed logins; wait before trying again. |
  | `id_taken` | 409 | That recording id belongs to another account. |
  | `recording_incomplete` | 409 | An analysis was asked for before the recording was sealed. |
  | `recording_in_progress` | 409 | The recording is still being recorded. |
  | `recording_complete` | 409 | A sealed recording can no longer change. |
  | `no_data` | 422 | The recording has no stream file, so it cannot be sealed. |
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
  | `internal` | 500 | The server failed; it is logged, and retrying later is reasonable. |

  The other codes name a malformed request, with the same shape: `invalid_body`,
  `invalid_query`, `invalid_meta`, `invalid_id`, `invalid_algorithm`, `invalid_version`,
  `invalid_params`, `invalid_offset`, `invalid_gzip`, `unsupported_encoding`,
  `empty_chunk`, `unreadable_body`, `meta_too_large`, `too_many_algorithms`.
- **Lists** take `limit` (default 50, at most 200) and `offset`, and answer
  `{ "items": [...], "limit": 50, "offset": 0, "total": 123 }`. Sorting is a documented
  `sort` parameter per endpoint; there is no free-form filter language.
- **Idempotency.** Creating a recording is `PUT` at a client-chosen id, so a retry after a
  lost response is safe. Requesting an analysis that already exists returns the existing
  one.
- **Caching.** `GET` responses are `Cache-Control: no-store`.

## System

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /v1/health` | public | Liveness: `{ "status": "ok", "time": ... }` |
| `GET /v1/version` | public | `{ "service": "taiga-sleep", "version": "0.6.1", "api": 4 }`. A client compares `api` with the one it was built for before it does anything else. |
| `GET /v1/openapi.json` | public | The OpenAPI 3.1 document of this build. |
| `GET /v1/docs` | public | Browsable documentation of the same. |

## Authentication

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `POST /v1/auth/login` | public | Body `{ "email", "password" }`. Answers `{ "token", "role", "user": { "id", "email" } }`. `role` is `user` or `worker`. |
| `POST /v1/auth/refresh` | any | A fresh token for the current one, same shape. |
| `GET /v1/me` | any | `{ "id", "email", "role" }`: also how a client checks a stored token. |

Accounts are created by the operator in the admin UI. There is no sign-up endpoint.

## Recordings

A recording is created by its owner at an id the client chooses (`[A-Za-z0-9_-]`, 1 to 40
characters, globally unique), so the phone's id for a recording is also the backend's.

```json
{
  "id": "20261023_223000-k3f9",
  "state": "stopped",
  "device": "Polar H10 069A4737",
  "started_at": "2026-10-23T20:30:00Z",
  "ended_at": "2026-10-24T05:41:12Z",
  "duration_s": 33072,
  "complete": true,
  "size_bytes": 28412345,
  "meta": { "format": "taiga-recording", "version": 1, "...": "see recording-format.md" },
  "created": "2026-10-24T05:50:01Z",
  "updated": "2026-10-24T05:52:40Z"
}
```

`meta` is the recording's `meta.json` verbatim (fields the backend does not know are kept); `state`, `device`, the times and the
duration are derived from it. `complete` means all files have been uploaded and the
recording is sealed.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /v1/recordings` | user | List own recordings. Query: `state`, `complete`, `from`, `to`, `sort` (`started_at`, `-started_at`, `created`, `-created`, `duration_s`, `-duration_s`). |
| `PUT /v1/recordings/{id}` | user | Body is the recording's `meta.json`, whose `session_id` must equal `{id}`. Creates (`201`) or replaces the metadata (`200`). `409 recording_complete` once sealed, `409 id_taken` if another user has the id. |
| `GET /v1/recordings/{id}` | user, worker with a lease | The recording. |
| `PATCH /v1/recordings/{id}` | user | Body `{ "complete": true }`: seals the recording. `409 recording_in_progress` if `meta.state` is still `recording`, `422 no_data` if it has no stream file. Nothing else can be patched, and a sealed recording cannot be unsealed. |
| `DELETE /v1/recordings/{id}` | user | Deletes the recording, its files and its analyses. `204`. |

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
  "status": "succeeded",
  "error": "",
  "attempts": 1,
  "created": "2026-10-24T05:52:40Z",
  "updated": "2026-10-24T05:58:03Z",
  "finished_at": "2026-10-24T05:58:03Z",
  "summary": { "...": "headline figures, small" },
  "result": { "contract_version": 3, "...": "see analysis-contract.md" }
}
```

`status` is `queued`, `running`, `succeeded` or `failed`; the last two are terminal.
`summary` is a small, derived headline (scores and the stage segments) so lists do not
have to carry the full `result`; it is `null` until the analysis has succeeded. `result`
is the worker's output, validated for its envelope only (`contract_version` and the
fields the contract requires). Its shape is versioned and documented in
[`analysis-contract.md`](analysis-contract.md), and it is flexible on purpose: what is
present depends on the data and the algorithm.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `POST /v1/recordings/{id}/analyses` | user | Body `{ "algorithm", "version"?, "params"? }`. The version defaults to the newest a worker offers, ignoring workers that stopped announcing themselves (`TAIGA_WORKER_TTL_S`, 900 s) unless none is left. Creates a queued analysis (`201`) or returns the existing one for the same recording, algorithm, version and params (`200`). A failed existing one is replaced by a fresh queued one (`201`), which is how a retry is made. `409 recording_incomplete` before the recording is sealed, `422 unknown_algorithm` when no worker offers it. |
| `GET /v1/recordings/{id}/analyses` | user | Analyses of one recording. Without `result` unless `expand=result`. |
| `GET /v1/analyses` | user, worker | A user sees their own analyses; a worker sees only the ones it holds the lease of. Query: `recording`, `status`, `expand=result`, `sort` (`created`, `-created`). Without `result` unless expanded. |
| `GET /v1/analyses/{id}` | user, worker | One analysis, with `summary` and `result`. A worker only while it holds the lease. |
| `DELETE /v1/analyses/{id}` | user | Removes it; a running one is cancelled, and the worker's next call about it gets `404`. `204`. |

## Algorithms

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `GET /v1/algorithms` | user | What registered workers can run: id, version, name, description, the streams it needs, the stages it can produce, parameter schema, citation and licence (the manifest of `analysis-contract.md`). |

## Worker

The worker is a client with the `worker` role. It uses the endpoints above plus these.
There are no worker-only copies of recording or file routes, and a worker sees nothing
outside the lease: while it holds a `running` analysis it may read that analysis, its
recording and the recording's files, and nothing else. Everything else is `404`, so ids
do not leak.

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `PUT /v1/workers/me` | worker | Body `{ "name", "version", "algorithms": [...] }`: announces what this worker can run. Refreshes its last-seen time. |
| `POST /v1/analyses/claim` | worker | Body `{ "algorithms"?: [{ "id", "version"? }], "lease_seconds"? }`. Atomically takes the oldest queued analysis it can run and marks it `running`: `200` with the analysis (including `recording` and `params`), or `204` when there is nothing to do. |
| `POST /v1/analyses/{id}/complete` | worker | Body `{ "result": {...} }`. Stores the result and marks it `succeeded`. `409 not_leased` if the worker no longer holds the lease. |
| `POST /v1/analyses/{id}/fail` | worker | Body `{ "error", "retryable"? }`. A retryable failure goes back to `queued` until its attempts are used up; otherwise the analysis is `failed`. |

A lease that expires puts the analysis back in the queue; the backend does this itself
(on every claim and once a minute), so there is no requeue endpoint.

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

## Closed on purpose

PocketBase's own `/api/collections/...` record endpoints are closed for every client; the
data is reachable only through the API above. The PocketBase admin UI (`/_/`) remains the
operator's tool for creating accounts and inspecting data.
