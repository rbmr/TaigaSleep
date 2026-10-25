// Route handlers for analysis requests, results and worker downloads.
//
// The thin route wrappers live in main.pb.js so their module scope survives
// PocketBase's route registration. Keep the actual work here.

const analyses = require(`${__hooks}/lib/analyses.js`);
const algorithms = require(`${__hooks}/lib/algorithms.js`);
const jobs = require(`${__hooks}/lib/jobs.js`);
const recordings = require(`${__hooks}/lib/recordings.js`);
const sessionView = require(`${__hooks}/lib/session_view.js`);
const workerAccess = require(`${__hooks}/lib/worker_access.js`);

function requireUser(e) {
  recordings.requireUser(e);
}

function requestAnalyses(e) {
  requireUser(e);
  const sessionId = e.request.pathValue("id");
  const body = e.requestInfo().body || {};
  const items = Array.isArray(body.models)
    ? body.models
    : body.model
      ? [body]
      : null;

  let result = null;
  $app.runInTransaction((txApp) => {
    const session = recordings.getSession(txApp, sessionId, e.auth.id);
    if (String(session.get("upload_status")) !== "uploaded") {
      throw new ApiError(409, "session is not uploaded");
    }
    if (String(session.get("state")) === "recording") {
      throw new ApiError(409, "recording is still in progress");
    }
    result = analyses.requestAnalyses(txApp, session, items);
  });

  return e.json(200, {
    analyses: result.analyses.map((record) => analyses.serializeAnalysis(record, false)),
    jobs: result.jobs.map((record) => jobs.serializeJob(record)),
  });
}

function listSessions(e) {
  requireUser(e);
  return e.json(200, sessionView.list($app, e.auth.id, e.requestInfo().query || {}));
}

function getSession(e) {
  requireUser(e);
  return e.json(200, sessionView.get($app, e.request.pathValue("id"), e.auth.id));
}

function getAnalysis(e) {
  requireUser(e);
  return e.json(200, sessionView.analysis($app, e.request.pathValue("id"), e.auth.id));
}

function retryJob(e) {
  requireUser(e);
  let result = null;
  $app.runInTransaction((txApp) => {
    result = sessionView.retry(txApp, e.request.pathValue("id"), e.auth.id);
  });
  return e.json(200, result);
}

function listAlgorithms(e) {
  requireUser(e);
  return e.json(200, { algorithms: algorithms.list($app) });
}

function reportCapabilities(e) {
  const body = e.requestInfo().body || {};
  let result = null;
  $app.runInTransaction((txApp) => {
    const auth = txApp.findRecordById("workers", e.auth.id);
    result = algorithms.report(txApp, auth, body);
  });
  return e.json(200, Object.assign({ ok: true }, result));
}

function workerManifest(e) {
  return e.json(200, workerAccess.manifest($app, e, e.request.pathValue("id")));
}

function workerFile(e) {
  const found = workerAccess.file($app, e, e.request.pathValue("id"), e.request.pathValue("file"));
  return e.fileFS($os.dirFS(found.dir), found.name);
}

module.exports = {
  getAnalysis: getAnalysis,
  getSession: getSession,
  listAlgorithms: listAlgorithms,
  listSessions: listSessions,
  reportCapabilities: reportCapabilities,
  requestAnalyses: requestAnalyses,
  retryJob: retryJob,
  workerFile: workerFile,
  workerManifest: workerManifest,
};
