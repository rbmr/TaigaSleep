// Recording storage and validation helpers.
//
// Recordings are directories under <pb_data>/recordings/<session id>/ with the
// append-only files from docs/recording-format.md. PocketBase is the only
// process that touches them; the worker downloads through the API.
//
// All file access goes through $os.openRoot(sessionDir), so a filename can
// never escape the session directory.

const dates = require(`${__hooks}/lib/dates.js`);
const frames = require(`${__hooks}/lib/frames.js`);

const META_FILE = "meta.json";
const EVENTS_FILE = "events.jsonl";
const LOG_FILES = Object.keys(frames.STREAM_BY_FILE);
const FILE_NAMES = [META_FILE, EVENTS_FILE].concat(LOG_FILES);

const STATES = ["recording", "stopped", "forced_stop", "interrupted"];

function envInt(name, fallback) {
  const value = parseInt($os.getenv(name) || "", 10);
  return isNaN(value) || value <= 0 ? fallback : value;
}

const MAX_TOTAL_BYTES = envInt("TAIGA_MAX_RECORDING_MB", 256) * 1024 * 1024;
const MAX_FILE_BYTES = envInt("TAIGA_MAX_RECORDING_FILE_MB", 64) * 1024 * 1024;
const MAX_EVENTS_BYTES = 8 * 1024 * 1024;
const MAX_CHUNK_BYTES = envInt("TAIGA_MAX_UPLOAD_CHUNK_MB", 8) * 1024 * 1024;
const MAX_META_BYTES = envInt("TAIGA_MAX_META_KB", 64) * 1024;

function requireUser(e) {
  if (!e.auth || e.auth.collection().name !== "users") {
    throw new ForbiddenError("user account required");
  }
}

function baseDir() {
  return $app.dataDir() + "/recordings";
}

function sessionDir(sessionId) {
  if (typeof sessionId !== "string" || !/^[A-Za-z0-9]{1,32}$/.test(sessionId)) {
    throw new BadRequestError("invalid session id");
  }
  return baseDir() + "/" + sessionId;
}

function openSessionRoot(sessionId) {
  $os.mkdirAll(baseDir(), 0o750);
  const dir = sessionDir(sessionId);
  $os.mkdirAll(dir, 0o750);
  return $os.openRoot(dir);
}

function withSessionRoot(sessionId, fn) {
  const root = openSessionRoot(sessionId);
  try {
    return fn(root);
  } finally {
    root.close();
  }
}

function removeDir(sessionId) {
  try {
    $os.removeAll(sessionDir(sessionId));
  } catch (err) {
    console.log("taiga: cannot remove recording directory: " + err);
  }
}

function fileSize(root, name) {
  try {
    return root.stat(name).size();
  } catch (_) {
    return 0;
  }
}

function totalBytes(root) {
  let total = 0;
  for (let i = 0; i < FILE_NAMES.length; i++) {
    total += fileSize(root, FILE_NAMES[i]);
  }
  return total;
}

function readText(root, name) {
  if (fileSize(root, name) === 0) return "";
  const file = root.open(name);
  try {
    return readerToString(file);
  } finally {
    file.close();
  }
}

function writeTextAtomic(root, name, text) {
  const tmp = name + "." + Date.now() + ".tmp";
  try {
    root.writeFile(tmp, text, 0o640);
    root.rename(tmp, name);
  } catch (err) {
    try {
      root.remove(tmp);
    } catch (_) {}
    throw err;
  }
}

function parseMetaText(text) {
  try {
    return JSON.parse(text);
  } catch (_) {
    return null;
  }
}

function readMetaFromRoot(root) {
  return parseMetaText(readText(root, META_FILE));
}

function readMeta(sessionId) {
  return withSessionRoot(sessionId, readMetaFromRoot);
}

function validateMeta(meta) {
  if (!meta || typeof meta !== "object" || Array.isArray(meta)) {
    throw new BadRequestError("meta must be a JSON object");
  }
  if (meta.format !== "taiga-recording") {
    throw new BadRequestError("meta.format must be 'taiga-recording'");
  }
  if (meta.version !== 1) {
    throw new BadRequestError("unsupported meta.version");
  }
  if (typeof meta.session_id !== "string" || !/^[A-Za-z0-9_-]{1,40}$/.test(meta.session_id)) {
    throw new BadRequestError("meta.session_id is invalid");
  }
  if (STATES.indexOf(meta.state) === -1) {
    throw new BadRequestError("meta.state is invalid");
  }
  if (!Number.isInteger(meta.start_utc_ms) || meta.start_utc_ms <= 0) {
    throw new BadRequestError("meta.start_utc_ms is invalid");
  }
  if (meta.end_utc_ms != null) {
    if (!Number.isInteger(meta.end_utc_ms) || meta.end_utc_ms < meta.start_utc_ms) {
      throw new BadRequestError("meta.end_utc_ms is invalid");
    }
  } else if (meta.state !== "recording") {
    throw new BadRequestError("meta.end_utc_ms is required for a finished recording");
  }
  if (typeof meta.timezone !== "string" || meta.timezone.length > 64) {
    throw new BadRequestError("meta.timezone is invalid");
  }
  if (!meta.sensor || typeof meta.sensor !== "object" || typeof meta.sensor.kind !== "string") {
    throw new BadRequestError("meta.sensor.kind is required");
  }
  if (!meta.config || typeof meta.config !== "object") {
    throw new BadRequestError("meta.config is required");
  }
  const config = meta.config;
  for (const key of ["hr", "ecg", "acc"]) {
    if (typeof config[key] !== "boolean") {
      throw new BadRequestError("meta.config." + key + " must be a boolean");
    }
  }
  for (const key of ["ecg_rate_hz", "acc_rate_hz"]) {
    if (typeof config[key] !== "number" || config[key] <= 0) {
      throw new BadRequestError("meta.config." + key + " must be a positive number");
    }
  }

  const serialized = JSON.stringify(meta);
  if (serialized.length > MAX_META_BYTES) {
    throw new BadRequestError("meta.json is too large");
  }
  return meta;
}

function applyMeta(record, meta) {
  const device =
    (meta.sensor && (meta.sensor.name || meta.sensor.kind)) || "Polar H10";
  record.set("device", String(device).slice(0, 120));
  record.set("started_at", dates.toPbDate(new Date(meta.start_utc_ms)));
  record.set("ended_at", meta.end_utc_ms == null ? "" : dates.toPbDate(new Date(meta.end_utc_ms)));
  record.set(
    "duration_s",
    meta.end_utc_ms == null
      ? 0
      : Math.max(0, Math.round((meta.end_utc_ms - meta.start_utc_ms) / 1000))
  );
  record.set("state", meta.state);
  if (!record.get("client_session_id")) {
    record.set("client_session_id", meta.session_id);
  }
  if (!record.get("upload_status") || record.get("upload_status") === "none") {
    record.set("upload_status", "pending");
  }
}

function emptyUpload() {
  return { version: 1, files: {}, completed: false, completed_at: "" };
}

function getUpload(record) {
  let upload = record.get("upload");
  // PocketBase returns a JSON field as raw bytes in the JSVM. A plain object
  // also works when the record was just built in memory.
  if (Array.isArray(upload)) {
    try {
      upload = JSON.parse(toString(upload));
    } catch (_) {
      upload = null;
    }
  } else if (typeof upload === "string") {
    try {
      upload = JSON.parse(upload);
    } catch (_) {
      upload = null;
    }
  }
  if (!upload || typeof upload !== "object" || Array.isArray(upload)) {
    upload = emptyUpload();
  }
  if (!upload.files || typeof upload.files !== "object") upload.files = {};
  if (upload.version == null) upload.version = 1;
  upload.completed = !!upload.completed;
  upload.completed_at = upload.completed_at || "";
  return upload;
}

function fileEntry(upload, name) {
  let entry = upload.files[name];
  if (!entry || typeof entry !== "object") {
    entry = {};
    upload.files[name] = entry;
  }
  entry.length = Number.isFinite(entry.length) ? entry.length : 0;
  entry.validated_length = Number.isFinite(entry.validated_length) ? entry.validated_length : 0;
  entry.frames = Number.isFinite(entry.frames) ? entry.frames : 0;
  entry.lines = Number.isFinite(entry.lines) ? entry.lines : 0;
  entry.state = entry.state || "unvalidated";
  entry.error = entry.error || "";
  entry.truncated = !!entry.truncated;
  return entry;
}

function markMetaEntry(upload, root) {
  const size = fileSize(root, META_FILE);
  if (size === 0) return;
  const entry = fileEntry(upload, META_FILE);
  entry.length = size;
  entry.validated_length = size;
  entry.state = "ok";
  entry.error = "";
  entry.truncated = false;
  entry.updated = new Date().toISOString();
}

function serializeSession(record) {
  return {
    id: record.id,
    owner: record.get("owner"),
    client_session_id: record.get("client_session_id") || "",
    device: record.get("device") || "",
    started_at: record.get("started_at") || "",
    ended_at: record.get("ended_at") || "",
    duration_s: record.get("duration_s") || 0,
    state: record.get("state") || "",
    upload_status: record.get("upload_status") || "none",
    uploaded_at: record.get("uploaded_at") || "",
    upload_error: record.get("upload_error") || "",
    created: record.get("created") || "",
    updated: record.get("updated") || "",
  };
}

function manifest(record) {
  return withSessionRoot(record.id, (root) => {
    const upload = getUpload(record);
    const files = {};
    let total = 0;

    for (let i = 0; i < FILE_NAMES.length; i++) {
      const name = FILE_NAMES[i];
      const size = fileSize(root, name);
      const existing = upload.files[name];
      if (size === 0 && !existing) continue;

      const item = fileEntry(upload, name);
      item.length = size;
      if (item.validated_length > size) {
        item.validated_length = 0;
        item.state = "unvalidated";
        item.error = "";
      }
      files[name] = item;
      total += size;
    }

    return {
      session: record.id,
      upload_status: record.get("upload_status") || "none",
      uploaded_at: record.get("uploaded_at") || "",
      upload_error: record.get("upload_error") || "",
      completed: !!upload.completed,
      completed_at: upload.completed_at || "",
      total_bytes: total,
      limits: {
        max_total_bytes: MAX_TOTAL_BYTES,
        max_file_bytes: MAX_FILE_BYTES,
        max_chunk_bytes: MAX_CHUNK_BYTES,
      },
      meta: readMetaFromRoot(root),
      files: files,
    };
  });
}

function getSession(app, sessionId, owner) {
  let record;
  try {
    record = app.findRecordById("sessions", sessionId);
  } catch (_) {
    throw new NotFoundError("session not found");
  }
  if (record.get("owner") !== owner) {
    throw new NotFoundError("session not found");
  }
  return record;
}

function createOrResume(app, owner, meta) {
  let record = null;
  try {
    record = app.findFirstRecordByFilter(
      "sessions",
      "owner = {:owner} && client_session_id = {:client}",
      { owner: owner, client: meta.session_id }
    );
  } catch (_) {
    // New recording.
  }

  if (record) {
    if (record.get("upload_status") === "uploaded") {
      return { record: record, created: false };
    }
    withSessionRoot(record.id, (root) => {
      writeTextAtomic(root, META_FILE, JSON.stringify(meta, null, 2));
      const upload = getUpload(record);
      markMetaEntry(upload, root);
      record.set("upload", upload);
    });
    applyMeta(record, meta);
    app.save(record);
    return { record: record, created: false };
  }

  const collection = app.findCollectionByNameOrId("sessions");
  record = new Record(collection);
  record.set("owner", owner);
  record.set("client_session_id", meta.session_id);
  record.set("upload", emptyUpload());
  record.set("upload_error", "");
  applyMeta(record, meta);
  app.save(record);

  try {
    withSessionRoot(record.id, (root) => {
      writeTextAtomic(root, META_FILE, JSON.stringify(meta, null, 2));
      const upload = getUpload(record);
      markMetaEntry(upload, root);
      record.set("upload", upload);
    });
    app.save(record);
  } catch (err) {
    try {
      app.delete(record);
    } catch (_) {}
    removeDir(record.id);
    throw err;
  }
  return { record: record, created: true };
}

function updateMeta(app, record, meta) {
  if (record.get("upload_status") === "uploaded") {
    throw new ApiError(409, "session is already uploaded");
  }
  const ownerClientId = record.get("client_session_id") || "";
  if (ownerClientId && ownerClientId !== meta.session_id) {
    throw new BadRequestError("meta.session_id does not match this session");
  }
  withSessionRoot(record.id, (root) => {
    writeTextAtomic(root, META_FILE, JSON.stringify(meta, null, 2));
    const upload = getUpload(record);
    markMetaEntry(upload, root);
    record.set("upload", upload);
  });
  applyMeta(record, meta);
  app.save(record);
  return record;
}

function appendBytes(root, name, bytes) {
  const file = root.openFile(name, 1 | 1024 | 64, 0o640); // O_WRONLY | O_APPEND | O_CREATE
  try {
    const written = file.write(bytes);
    if (written !== bytes.length) {
      throw new Error(`short write: ${written}/${bytes.length}`);
    }
    file.sync();
  } finally {
    file.close();
  }
}

function truncateFile(root, name, size) {
  const file = root.openFile(name, 1, 0); // O_WRONLY
  try {
    file.truncate(size);
    file.sync();
  } finally {
    file.close();
  }
}

function validateLog(root, name, entry) {
  const result = frames.validateFile(root, name, frames.STREAM_BY_FILE[name], entry.validated_length);

  entry.frames += result.frames;

  if (result.state === "corrupt") {
    truncateFile(root, name, result.validEnd);
    entry.length = result.validEnd;
    entry.validated_length = result.validEnd;
    entry.state = "corrupt";
    entry.error = result.error + " at byte " + result.corruptAt;
    entry.corrupt_at = result.corruptAt;
    entry.truncated = true;
  } else {
    entry.length = result.length;
    entry.validated_length = result.validEnd;
    entry.state = result.state;
    entry.pending_bytes = result.pending;
    entry.error = "";
    entry.corrupt_at = -1;
  }
  entry.updated = new Date().toISOString();
  return result.state;
}

function validateEvents(root, name, entry) {
  const result = frames.validateEvents(root, name, entry.validated_length);

  entry.lines += result.lines;

  if (result.state === "corrupt") {
    truncateFile(root, name, result.validEnd);
    entry.length = result.validEnd;
    entry.validated_length = result.validEnd;
    entry.state = "corrupt";
    entry.error = result.error + " at byte " + result.corruptAt;
    entry.corrupt_at = result.corruptAt;
    entry.truncated = true;
  } else {
    entry.length = result.length;
    entry.validated_length = result.validEnd;
    entry.state = result.state;
    entry.error = "";
    entry.corrupt_at = -1;
  }
  entry.updated = new Date().toISOString();
  return result.state;
}

function finalizeFile(root, name, entry) {
  const size = fileSize(root, name);
  if (size === 0) {
    entry.length = 0;
    entry.validated_length = 0;
    entry.state = "ok";
    return entry;
  }

  const isLog = LOG_FILES.indexOf(name) !== -1;
  const result = isLog
    ? frames.validateFile(root, name, frames.STREAM_BY_FILE[name], entry.validated_length)
    : frames.validateEvents(root, name, entry.validated_length);

  if (isLog) entry.frames += result.frames;
  else entry.lines += result.lines;

  if (result.state === "corrupt" || result.state === "partial") {
    truncateFile(root, name, result.validEnd);
    entry.length = result.validEnd;
    entry.validated_length = result.validEnd;
    entry.truncated = true;
    entry.state = result.state === "corrupt" ? "corrupt" : "ok";
    if (result.state === "corrupt") {
      entry.error = result.error + " at byte " + result.corruptAt;
      entry.corrupt_at = result.corruptAt;
    }
  } else {
    entry.length = size;
    entry.validated_length = result.validEnd;
    entry.state = "ok";
    entry.error = "";
    entry.corrupt_at = -1;
  }
  entry.updated = new Date().toISOString();
  return entry;
}

function complete(app, record) {
  return withSessionRoot(record.id, (root) => {
    const meta = readMetaFromRoot(root);
    if (!meta) {
      throw new BadRequestError("meta.json is missing");
    }
    validateMeta(meta);
    if (meta.state === "recording") {
      throw new ApiError(409, "recording is still in progress");
    }
    if (record.get("upload_status") === "uploaded") {
      return;
    }

    const upload = getUpload(record);
    const warnings = [];
    let validFrames = 0;

    for (let i = 0; i < FILE_NAMES.length; i++) {
      const name = FILE_NAMES[i];
      if (name === META_FILE) continue;
      if (fileSize(root, name) === 0) continue;

      const entry = fileEntry(upload, name);
      finalizeFile(root, name, entry);
      if (LOG_FILES.indexOf(name) !== -1) validFrames += entry.frames;
      if (entry.truncated) {
        warnings.push(name + ": kept the valid prefix and truncated the bad tail");
      }
    }

    if (validFrames === 0) {
      throw new ApiError(422, "recording has no valid frames");
    }

    markMetaEntry(upload, root);
    upload.completed = true;
    upload.completed_at = new Date().toISOString();
    record.set("upload", upload);
    record.set("upload_status", "uploaded");
    record.set("uploaded_at", dates.nowString());
    record.set("upload_error", warnings.join("; ").slice(0, 2000));
    app.save(record);
  });
}

function applyAppend(app, record, name, offset, bytes) {
  if (record.get("upload_status") === "uploaded") {
    throw new ApiError(409, "session is already uploaded");
  }
  if (name === META_FILE) {
    throw new BadRequestError("use the meta route for meta.json");
  }

  return withSessionRoot(record.id, (root) => {
    const current = fileSize(root, name);
    if (offset !== current) {
      throw new ApiError(409, "offset mismatch: expected " + current + ", got " + offset);
    }

    const upload = getUpload(record);
    const entry = fileEntry(upload, name);

    if (current + bytes.length > MAX_FILE_BYTES) {
      throw new ApiError(413, "recording file is too large (max " + MAX_FILE_BYTES + " bytes)");
    }
    if (name === EVENTS_FILE && current + bytes.length > MAX_EVENTS_BYTES) {
      throw new ApiError(413, "events file is too large (max " + MAX_EVENTS_BYTES + " bytes)");
    }
    if (totalBytes(root) + bytes.length > MAX_TOTAL_BYTES) {
      throw new ApiError(413, "recording is too large (max " + MAX_TOTAL_BYTES + " bytes)");
    }

    appendBytes(root, name, bytes);

    if (name === EVENTS_FILE) {
      validateEvents(root, name, entry);
    } else {
      validateLog(root, name, entry);
    }

    const uploadRecord = getUpload(record);
    uploadRecord.files[name] = entry;
    record.set("upload", uploadRecord);
    if (!record.get("upload_status") || record.get("upload_status") === "none") {
      record.set("upload_status", "pending");
    }
    app.save(record);

    return {
      file: name,
      offset: offset,
      written: bytes.length,
      length: entry.length,
      validated_length: entry.validated_length,
      frames: entry.frames,
      lines: entry.lines,
      state: entry.state,
      error: entry.error,
      truncated: entry.truncated,
    };
  });
}

module.exports = {
  META_FILE: META_FILE,
  EVENTS_FILE: EVENTS_FILE,
  LOG_FILES: LOG_FILES,
  FILE_NAMES: FILE_NAMES,
  MAX_CHUNK_BYTES: MAX_CHUNK_BYTES,
  MAX_META_BYTES: MAX_META_BYTES,
  requireUser: requireUser,
  sessionDir: sessionDir,
  removeDir: removeDir,
  readMeta: readMeta,
  validateMeta: validateMeta,
  applyMeta: applyMeta,
  emptyUpload: emptyUpload,
  getUpload: getUpload,
  fileEntry: fileEntry,
  serializeSession: serializeSession,
  manifest: manifest,
  getSession: getSession,
  createOrResume: createOrResume,
  updateMeta: updateMeta,
  appendBytes: appendBytes,
  truncateFile: truncateFile,
  validateLog: validateLog,
  validateEvents: validateEvents,
  finalizeFile: finalizeFile,
  complete: complete,
  applyAppend: applyAppend,
};
