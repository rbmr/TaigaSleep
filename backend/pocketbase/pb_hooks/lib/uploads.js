// Owner-only recording upload handlers.
//
// Uploading and analysing are separate: completing an upload only marks the
// session uploaded. Analysis requests live in the analysis routes.
//
// The route registration itself lives in main.pb.js. PocketBase executes route
// handlers outside the module that registered them, so keeping the thin route
// wrappers in the main bundle is what makes the module scope safe.

function recordingsModule() {
  return require(`${__hooks}/lib/recordings.js`);
}

function bodyMeta(e, recordings) {
  const body = e.requestInfo().body || {};
  const meta = body.meta === undefined ? body : body.meta;
  return recordings.validateMeta(meta);
}

function offsetFromRequest(e) {
  const raw = e.request.url.query().get("offset");
  if (raw === null || raw === undefined) {
    throw new BadRequestError("offset is required");
  }
  const text = String(raw).trim();
  if (!/^[0-9]+$/.test(text)) {
    throw new BadRequestError("offset must be a non-negative integer");
  }
  const offset = parseInt(text, 10);
  if (!Number.isSafeInteger(offset)) {
    throw new BadRequestError("offset is too large");
  }
  return offset;
}

function sessionId(e) {
  return e.request.pathValue("id");
}

function createSession(e) {
  const recordings = recordingsModule();
  recordings.requireUser(e);
  const meta = bodyMeta(e, recordings);

  let result = null;
  $app.runInTransaction((txApp) => {
    result = recordings.createOrResume(txApp, e.auth.id, meta);
  });

  const record = recordings.getSession($app, result.record.id, e.auth.id);
  return e.json(result.created ? 201 : 200, {
    session: recordings.serializeSession(record),
    upload: recordings.manifest(record),
  });
}

function getUpload(e) {
  const recordings = recordingsModule();
  recordings.requireUser(e);
  const record = recordings.getSession($app, sessionId(e), e.auth.id);
  return e.json(200, {
    session: recordings.serializeSession(record),
    upload: recordings.manifest(record),
  });
}

function putMeta(e) {
  const recordings = recordingsModule();
  recordings.requireUser(e);
  const meta = bodyMeta(e, recordings);

  const id = sessionId(e);
  $app.runInTransaction((txApp) => {
    const record = recordings.getSession(txApp, id, e.auth.id);
    recordings.updateMeta(txApp, record, meta);
  });

  const record = recordings.getSession($app, id, e.auth.id);
  return e.json(200, {
    session: recordings.serializeSession(record),
    upload: recordings.manifest(record),
  });
}

function putFile(e) {
  const recordings = recordingsModule();
  recordings.requireUser(e);
  const name = e.request.pathValue("file");
  if (recordings.FILE_NAMES.indexOf(name) === -1 || name === recordings.META_FILE) {
    throw new BadRequestError("unknown recording file");
  }
  const offset = offsetFromRequest(e);

  const bytes = toBytes(e.request.body, recordings.MAX_CHUNK_BYTES + 1);
  if (bytes.length === 0) {
    throw new BadRequestError("request body is empty");
  }
  if (bytes.length > recordings.MAX_CHUNK_BYTES) {
    throw new ApiError(
      413,
      "chunk is too large (max " + recordings.MAX_CHUNK_BYTES + " bytes)"
    );
  }

  let result = null;
  $app.runInTransaction((txApp) => {
    const record = recordings.getSession(txApp, sessionId(e), e.auth.id);
    result = recordings.applyAppend(txApp, record, name, offset, bytes);
  });

  return e.json(200, result);
}

function completeUpload(e) {
  const recordings = recordingsModule();
  recordings.requireUser(e);

  const id = sessionId(e);
  $app.runInTransaction((txApp) => {
    const record = recordings.getSession(txApp, id, e.auth.id);
    recordings.complete(txApp, record);
  });

  const record = recordings.getSession($app, id, e.auth.id);
  return e.json(200, {
    session: recordings.serializeSession(record),
    upload: recordings.manifest(record),
  });
}

module.exports = {
  createSession: createSession,
  getUpload: getUpload,
  putMeta: putMeta,
  putFile: putFile,
  completeUpload: completeUpload,
};
