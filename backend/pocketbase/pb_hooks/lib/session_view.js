// Session list/detail views and analysis retries.

const dates = require(`${__hooks}/lib/dates.js`);
const recordings = require(`${__hooks}/lib/recordings.js`);
const analyses = require(`${__hooks}/lib/analyses.js`);
const jobs = require(`${__hooks}/lib/jobs.js`);

const STATES = ["recording", "stopped", "forced_stop", "interrupted"];
const UPLOAD_STATUSES = ["none", "pending", "uploaded", "failed"];
const SORTS = [
  "started_at",
  "-started_at",
  "created",
  "-created",
  "updated",
  "-updated",
  "duration_s",
  "-duration_s",
];

function analysesFor(app, sessionId) {
  return app.findRecordsByFilter(
    "analyses",
    "session = {:session}",
    "created",
    0,
    0,
    { session: sessionId }
  );
}

function jobsFor(app, sessionId) {
  return app.findRecordsByFilter(
    "jobs",
    "session = {:session}",
    "created",
    0,
    0,
    { session: sessionId }
  );
}

function summary(app, record) {
  const rows = analysesFor(app, record.id);
  return {
    session: recordings.serializeSession(record),
    analyses: rows.map((row) => analyses.serializeAnalysis(row, true)),
    analysis_summary: analyses.analysisSummary(rows),
  };
}

function detail(app, record) {
  const analysisRows = analysesFor(app, record.id);
  const jobRows = jobsFor(app, record.id);
  return {
    session: recordings.serializeSession(record),
    upload: recordings.manifest(record),
    analyses: analysisRows.map((row) => analyses.serializeAnalysis(row, false)),
    jobs: jobRows.map((row) => jobs.serializeJob(row)),
    analysis_summary: analyses.analysisSummary(analysisRows),
  };
}

function get(app, sessionId, owner) {
  const record = recordings.getSession(app, sessionId, owner);
  return detail(app, record);
}

function positiveInt(value, fallback, maximum) {
  const parsed = parseInt(value, 10);
  if (!isFinite(parsed) || parsed < 1) return fallback;
  return Math.min(parsed, maximum);
}

function pbDate(value, name) {
  const date = new Date(String(value));
  if (isNaN(date.getTime())) {
    throw new BadRequestError(name + " is not a valid date");
  }
  return dates.toPbDate(date);
}

function list(app, owner, query) {
  const page = positiveInt(query.page, 1, 1000000);
  const perPage = positiveInt(query.perPage, 30, 100);
  const offset = (page - 1) * perPage;

  const where = ["owner = {:owner}"];
  const params = { owner: owner };

  if (query.state) {
    if (STATES.indexOf(query.state) === -1) {
      throw new BadRequestError("invalid state filter");
    }
    where.push("state = {:state}");
    params.state = query.state;
  }
  if (query.upload_status) {
    if (UPLOAD_STATUSES.indexOf(query.upload_status) === -1) {
      throw new BadRequestError("invalid upload_status filter");
    }
    where.push("upload_status = {:upload_status}");
    params.upload_status = query.upload_status;
  }
  if (query.from) {
    where.push("started_at >= {:from}");
    params.from = pbDate(query.from, "from");
  }
  if (query.to) {
    where.push("started_at <= {:to}");
    params.to = pbDate(query.to, "to");
  }

  const sort = String(query.sort || "-started_at");
  if (SORTS.indexOf(sort) === -1) {
    throw new BadRequestError("invalid sort");
  }

  // Fetch one extra row to know whether another page exists without a second
  // count query. PocketBase owns filtering, sorting and pagination here.
  const records = app.findRecordsByFilter(
    "sessions",
    where.join(" && "),
    sort,
    perPage + 1,
    offset,
    params
  );
  const hasMore = records.length > perPage;
  const pageRecords = hasMore ? records.slice(0, perPage) : records;

  return {
    page: page,
    perPage: perPage,
    hasMore: hasMore,
    items: pageRecords.map((record) => summary(app, record)),
  };
}

function ownedJob(app, jobId, owner) {
  let job;
  try {
    job = app.findRecordById("jobs", jobId);
  } catch (_) {
    throw new NotFoundError("job not found");
  }
  try {
    const session = app.findRecordById("sessions", job.get("session"));
    if (session.get("owner") !== owner) {
      throw new NotFoundError("job not found");
    }
  } catch (_) {
    throw new NotFoundError("job not found");
  }
  return job;
}

function retry(app, jobId, owner) {
  const job = ownedJob(app, jobId, owner);
  analyses.retry(app, job);
  let analysis = null;
  try {
    analysis = app.findRecordById("analyses", job.get("analysis"));
  } catch (_) {
    // The retry still happened; the analysis is missing.
  }
  return {
    job: jobs.serializeJob(job),
    analysis: analysis ? analyses.serializeAnalysis(analysis, false) : null,
  };
}

function analysis(app, analysisId, owner) {
  let record;
  try {
    record = app.findRecordById("analyses", analysisId);
  } catch (_) {
    throw new NotFoundError("analysis not found");
  }
  try {
    const session = app.findRecordById("sessions", record.get("session"));
    if (session.get("owner") !== owner) {
      throw new NotFoundError("analysis not found");
    }
  } catch (_) {
    throw new NotFoundError("analysis not found");
  }
  return analyses.serializeAnalysis(record, false);
}

module.exports = {
  analysis: analysis,
  detail: detail,
  get: get,
  list: list,
  retry: retry,
  summary: summary,
};
