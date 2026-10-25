// Scoped worker access to one recording.
//
// A worker may only download the recording for a job it currently holds. This
// is checked for the manifest and for every file request.

const dates = require(`${__hooks}/lib/dates.js`);
const recordings = require(`${__hooks}/lib/recordings.js`);

function queryValue(e, name) {
  const value = e.request.url.query().get(name);
  return value === null || value === undefined ? "" : String(value).trim();
}

function findSession(app, sessionId) {
  try {
    return app.findRecordById("sessions", sessionId);
  } catch (_) {
    throw new NotFoundError("session not found");
  }
}

function assertJob(app, sessionId, jobId, worker) {
  if (!jobId) {
    throw new BadRequestError("job is required");
  }
  if (!worker) {
    throw new BadRequestError("worker is required");
  }
  let job;
  try {
    job = app.findRecordById("jobs", jobId);
  } catch (_) {
    throw new NotFoundError("job not found");
  }
  if (String(job.get("worker")) !== worker) {
    throw new ForbiddenError("job is leased by another worker");
  }
  if (String(job.get("session")) !== sessionId) {
    throw new ForbiddenError("job belongs to another session");
  }
  if (String(job.get("status")) !== "running") {
    throw new ForbiddenError("job is not running");
  }
  const lease = String(job.get("lease_until") || "");
  if (!lease || lease < dates.nowString()) {
    throw new ForbiddenError("job lease has expired");
  }
  return job;
}

function manifest(app, e, sessionId) {
  const jobId = queryValue(e, "job");
  const worker = queryValue(e, "worker");
  assertJob(app, sessionId, jobId, worker);
  const session = findSession(app, sessionId);
  return {
    job: jobId,
    worker: worker,
    upload: recordings.manifest(session),
  };
}

function file(app, e, sessionId, name) {
  if (recordings.FILE_NAMES.indexOf(name) === -1) {
    throw new BadRequestError("unknown recording file");
  }
  const jobId = queryValue(e, "job");
  const worker = queryValue(e, "worker");
  assertJob(app, sessionId, jobId, worker);
  findSession(app, sessionId);
  return {
    dir: recordings.sessionDir(sessionId),
    name: name,
  };
}

module.exports = {
  file: file,
  manifest: manifest,
};
