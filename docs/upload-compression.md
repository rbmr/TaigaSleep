# Compressed recording uploads

Compression is an opt-in property of the **upload and server-side storage
layer**. The recording on the phone stays append-only, uncompressed and
unchanged, and the algorithm contract is untouched: the worker decompresses to
a temporary directory and analyses exactly the same bytes it would have
received before.

The mode is documented here because it changes the upload protocol, the storage
layout and the worker's first step. Compression is the default; the setting can
be turned off per user.

## Why per-chunk gzip

A single zip or tar of a night is not resumable: the central directory is
written at the end, so a killed upload cannot append. Instead the uploader
compresses **one complete gzip member per chunk** and appends the members:

```text
chunk 0        chunk 1        chunk 2
[member][member][member] ...            -> hr.log.gz
```

Concatenated gzip members are a standard stream, and every member carries its
own CRC and uncompressed size. Resuming works at member boundaries at the
compressed byte offset, and a recording that is still growing can keep
uploading. Python's `gzip`, zlib and every common tool read the resulting file.

zstd is not used because it would add a native dependency to the Android app.
At level 6 the ratio is already close to the limit for these binary streams, and
higher levels cost a lot of CPU for almost nothing (see the benchmark).

## Protocol

The app requests the mode per append:

```text
PATCH /v1/recordings/{id}/files/{name}
Upload-Offset: N
Content-Encoding: gzip
```

- `HEAD` on the same path reports the stored size in `Upload-Offset` and, for a
  compressed file, `Content-Encoding: gzip`. A plain file has no
  `Content-Encoding`, and a file that has no chunk yet answers `404`, so the
  upload starts at 0.
- The encoding is chosen by the first chunk of each file and cannot change
  afterwards. Mixing compressed and plain bytes in one file returns `409`
  (`encoding_mismatch`).
- Each request body is exactly **one complete gzip member**. The member must
  use no optional header fields (no FEXTRA, FNAME, FCOMMENT or FHCRC) and no
  reserved flags, so its header is always 10 bytes.
- `Upload-Offset` is the compressed byte offset and must equal the stored file
  length, exactly like the identity mode. A wrong offset answers
  `409 offset_mismatch` with the stored length in the `Upload-Offset` header.
- `meta.json` is never compressed and is not a file of the recording: it is the
  `meta` of the record created with `POST /api/collections/recordings/records`.
- The app writes a deterministic member: mtime 0, XFL 0, OS unknown, zlib level
  6, and a hand-written header so no timestamp or filename leaks into the
  stream and re-running the same input produces the same member.
- The response is `204` with `Upload-Offset` set to the new stored size.

`PATCH /api/collections/recordings/records/{id}` with `{"complete": true}` seals the
recording without inflating anything. Frame validation is the worker's job in this mode; the API
checks the gzip framing and its integrity, not the frames inside.

### What the API validates

Per chunk, the API inflates the member to a discard sink, bounded by the size its
trailer declares, and checks it before anything is stored:

- gzip magic, compression method, reserved and optional flags;
- a non-empty deflate payload with a legal block type;
- the trailer's declared uncompressed size (`ISIZE`) against
  `max_uncompressed_chunk_bytes` and `max_expansion_ratio`;
- the CRC and the real inflated size against that declaration: a member that
  fails its CRC, ends early, or inflates to anything other than exactly its
  declared size is refused (`400 invalid_gzip`);
- the stored file/total caps and the member count cap.

The declared size is therefore true, and everything the API keeps about a file
is derived from it, including the bound of an inflated download. The inflate is
limited to the declared size plus one byte, so a member that understates what it
holds stops there instead of expanding without bound. Frame validation stays the
worker's job: the API checks the gzip framing and its integrity, not the frames
inside.

## Storage layout

```text
<pb_data>/recordings/<recording id>/
  events.jsonl.gz    concatenated gzip members
  hr.log.gz
  ecg.log.gz
  acc.log.gz
```

Only the compressed form is stored on the server, so backups and disk usage
shrink. The worker always extracts to its temporary directory and deletes it
after the job. `GET /v1/recordings/{id}/files` names each stored file and its
encoding, so a worker never has to guess.

The download route (`GET /v1/recordings/{id}/files/{name}`) reads the same layout.
A client that asks for `Accept-Encoding: gzip` gets the stored members verbatim,
with their stored size. Any other request gets the file inflated on the way out,
as the original bytes: the phone does this to restore a raw file it cleaned up
locally. The phone asks for `identity` because a stored file is several gzip
members, and HTTP clients that decode `Content-Encoding` themselves often read
only the first (OkHttp fails on the second). An inflated download is bounded by
the uncompressed size the recording declares and declares that size as its
`Content-Length`. If inflating fails, or the result is not exactly that size,
the API aborts the connection: the client sees a failed download instead of a
short file that ends like a complete one.

## Limits

Defaults, all overridable per deployment:

| Variable | Default | Meaning |
| --- | --- | --- |
| `TAIGA_MAX_RECORDING_MB` | 256 | Stored bytes per recording (compressed when in gzip mode). |
| `TAIGA_MAX_RECORDING_FILE_MB` | 64 | Stored bytes per file. |
| `TAIGA_MAX_RECORDING_UNCOMPRESSED_MB` | 512 | Reckoned uncompressed bytes per recording. |
| `TAIGA_MAX_RECORDING_UNCOMPRESSED_FILE_MB` | 128 | Reckoned uncompressed bytes per file. |
| `TAIGA_MAX_UPLOAD_CHUNK_MB` | 8 | Request body size; one gzip member per request. |
| `TAIGA_MAX_UPLOAD_UNCOMPRESSED_CHUNK_MB` | 16 | Bytes one member may expand to. |
| `TAIGA_MAX_UPLOAD_MEMBERS` | 10000 | Compressed members per file. |
| `max_expansion_ratio` | 200 | Fixed in the API; per member decompressed/compressed. |

The worker downloads a compressed file as stored (`Accept-Encoding: gzip`),
inflates it locally member by member, bounded by the uncompressed size the file
listing advertises, and lets `zlib` verify each member's CRC and size. A
download that is cut short, or that does not inflate to exactly the advertised
size, fails the job instead of handing the analysis a short night. The worker
enforces the same limits after download: total and per-file decompressed size,
member count, expansion ratio per member and a whitelist of file names. A
truncated or corrupt member fails the job with `truncated gzip member` or
`invalid gzip member`. After extraction the worker validates frame headers and
CRCs, keeps the valid prefix of each stream and reports truncation as a warning.

## App behaviour

The setting is **Settings → Recordings → Compress uploads**, on by default. It
can be turned off for a very fast server or to keep the server-side files
directly readable. When it is on:

- a file that has no chunk on the server yet is sent as gzip; a file that already
  has compressed bytes continues in gzip even if the setting was turned off;
- a file that already has plain bytes stays plain;
- the server's chunk limit is not advertised; the app starts at 2 MiB and halves
  the chunk after a `413 chunk_too_large`;
- the app keeps a compressed cache under
  `<files>/upload-cache/<local session id>/` with a small boundary index;
- every chunk is appended to the cache and its boundary recorded **before** it
  is sent, so a kill can only leave the cache ahead of the server, never
  behind;
- on resume the server's compressed offset is mapped back to the uncompressed
  offset in the recording. The cache is cut back to the reported member
  boundary and the transfer continues;
- the cache is deleted when the upload completes. If the cache is lost while
  the server already holds bytes, the app fails with an explicit message and
  the user can delete the recording on the server and upload again.

## Benchmark

Run the checked-in script on any local recording. It writes temporary copies
only, never uploads anything and never commits data:

```bash
cd backend
uv run --project worker --frozen python scripts/benchmark_compression.py \
  /path/to/recording --levels 1,6,9
```

It reports raw and stored bytes, ratio, member count, compression and
decompression time, frame-verification time and peak RSS. Keep the output
local.

### Reference run (real H10 night, x86_64 laptop)

One real 9-hour night (75,789,899 raw bytes: HR, ECG, ACC and events), 2 MiB
chunks, Python `gzip.compress`; the app uses zlib level 6:

| Level | Stored bytes | Ratio | Compress (Python) | Decompress | Frame verify | Peak RSS |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 36,752,332 | 2.06x | 2.30 s | 0.54 s | 0.17 s | 144 MiB |
| 6 | 33,873,144 | 2.24x | 8.05 s | 0.49 s | 0.16 s | 167 MiB |
| 9 | 33,868,175 | 2.24x | 48.97 s | 0.58 s | 0.19 s | 167 MiB |

Level 9 saves 0.01% over level 6 and costs six times the CPU, so it is rejected.
Level 1 saves 5.75 s of CPU at the price of 2.88 MB of storage; level 6 is the
chosen default because phone CPU is the scarcer resource during an upload and
the worker decompression cost is identical.

The same file set compressed with the app's JVM implementation (level 6,
2 MiB chunks) on the same laptop: 5.56 s, 33,873,144 stored bytes, 2.24x.
Android's zlib is the same implementation family as the JVM's `Deflater`, but
the phone result must be measured on the phone; the app reports upload progress,
so a real night's compression cost is the upload time minus the network time.

Worker decompression is cheap: under a second and well under 200 MiB peak RSS
for a full night, far below the analysis stagers' own memory use. A full
end-to-end run on the same night (compressed upload, decompression, frame
verification and the built-in baseline) took 3.8 s of worker wall time and
peaked at 508 MiB including the Python interpreter and the analysis.

### Decision

**Compression is the default**, and the setting remains as an opt-out. On the
real night above it cuts the upload and the stored copy from 75.8 MB to 33.9 MB
(2.24x). On the laptop, the raw upload took 1.38 s (39 requests, 75.8 MB) and
the compressed upload took 9.11 s total, of which 8.57 s was Python
compression and 0.54 s was the API; the API path itself is cheaper because it
writes half as many bytes and skips frame validation. The JVM implementation
used by the app compresses the same night in 5.56 s.

The phone's compression cost is the only variable not measured on the device
yet. Taking 5.6 s as a laptop proxy, compression pays for itself whenever the
uplink is slower than about 60 Mbit/s (335 Mbit saved / 5.6 s); if the phone is
twice to four times slower, the break-even drops to roughly 15–30 Mbit/s. At a
typical home or mobile uplink that is the common case, and it always saves
42 MB of transfer and server storage. On a very fast local server the
uncompressed path is a few seconds faster in wall time, which is why the
opt-out stays.

### One representation, not two

The bytes sent by the app are the bytes stored on the server: one complete gzip
member per chunk, appended verbatim. Transfer compression and storage
compression are therefore the same thing here, not two separate codecs.

They *could* differ. A server could accept a transport encoding and recompress
for storage, or the app could send a fast level and the worker re-encode to a
stronger one. That would let the phone use a cheap level while storage uses an
expensive one, or let storage switch to zstd. The costs are real:

- the API would have to inflate the request (or the worker would have to
  re-encode after download), adding CPU and a second integrity boundary;
- resume would no longer be against the stored bytes unless a temporary
  transport file is kept;
- level 9 buys 0.01% over level 6 for six times the CPU, so stronger gzip has
  nothing to offer;
- zstd might save another 10–15%, but it adds a native dependency to the app
  or a worker recompression step and a second decode path for re-analysis.

So the single representation is deliberate. It keeps byte-offset resume,
member framing checks and the storage saving in one place. Revisit only if
server storage or upload bandwidth becomes the binding constraint.

## Failure modes

| Symptom | Where | Result |
| --- | --- | --- |
| Not a gzip member, bad flags, empty member, too much expansion | API | `400` before anything is stored |
| A member that fails its CRC or inflates to another size than it declares | API | `400 invalid_gzip` before anything is stored |
| Offset not at a member boundary | API | `409 offset_mismatch`, resume from the `Upload-Offset` it carries |
| Mixed encodings in one file | API | `409 encoding_mismatch`, the first encoding wins |
| Stored member damaged after the upload | API on download | the inflated response aborts, the client sees a failed download |
| Truncated or corrupt member at the worker | Worker | job fails with the member reason; lease/retry logic applies |
| Download cut short, or shorter or longer than advertised | Worker | job fails before analysis; lease/retry logic applies |
| Decompression bomb | Worker | job fails before analysis; size/member/ratio limits |
| Cache lost while the server has bytes | App | explicit error; delete the recording on the server and retry |
| Bad frame or CRC after decompression | Worker | valid prefix kept, warning in the result provenance |
