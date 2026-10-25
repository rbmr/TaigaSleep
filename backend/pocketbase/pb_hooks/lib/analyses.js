// Analysis request, result and retry helpers.
//
// An analysis is one (session, model, model_version, params_hash) result. A job
// is the queue entry for exactly one analysis. The API writes the result into
// the analysis row; the job only carries queue state and a small runtime
// summary.

const dates = require(`${__hooks}/lib/dates.js`);
const jsonFields = require(`${__hooks}/lib/json_fields.js`);

const MODEL_RE = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const MAX_MODELS = 8;
const MAX_PARAMS_BYTES = 16384;
const MAX_ERROR = 4000;

function paramsHash(params) {
  // The app and worker exchange this value; the API's hash is authoritative.
  return $security.sha256(JSON.stringify(params || {})).slice(0, 16);
}

function findAnalysis(app, sessionId, model, version, hash) {
  try {
    return app.findFirstRecordByFilter(
      "analyses",
      "session = {:session} && model = {:model} && model_version = {:version} && params_hash = {:hash}",
      { session: sessionId, model: model, version: version, hash: hash }
    );
  } catch (_) {
    return null;
  }
}

function findJob(app, analysisId) {
  try {
    return app.findFirstRecordByFilter("jobs", "analysis = {:analysis}", {
      analysis: analysisId,
    });
  } catch (_) {
    return null;
  }
}

function createAnalysis(app, data) {
  const collection = app.findCollectionByNameOrId("analyses");
  const record = new Record(collection);
  record.set("session", data.session);
  record.set("model", data.model);
  record.set("model_version", data.model_version);
  record.set("params", data.params || {});
  record.set("params_hash", data.params_hash);
  record.set("status", "queued");
  record.set("error", "");
  app.save(record);
  return record;
}

function createJob(app, analysis) {
  const collection = app.findCollectionByNameOrId("jobs");
  const job = new Record(collection);
  job.set("session", analysis.get("session"));
  job.set("analysis", analysis.id);
  job.set("kind", "analysis");
  job.set("model", analysis.get("model"));
  job.set("model_version", analysis.get("model_version"));
  job.set("params_hash", analysis.get("params_hash"));
  job.set("status", "queued");
  job.set("attempts", 0);
  job.set("available_at", dates.nowString());
  job.set("worker", "");
  job.set("lease_until", "");
  job.set("error", "");
  app.save(job);
  return job;
}

function requestAnalyses(app, session, items) {
  if (!Array.isArray(items) || items.length === 0) {
    throw new BadRequestError("models must be a non-empty array");
  }
  if (items.length > MAX_MODELS) {
    throw new BadRequestError("at most " + MAX_MODELS + " models can be requested at once");
  }

  const analyses = [];
  const jobs = [];
  const seen = {};

  for (const item of items) {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      throw new BadRequestError("each model entry must be an object");
    }
    const model = String(item.model || "").trim();
    if (!MODEL_RE.test(model)) {
      throw new BadRequestError("model id is invalid");
    }
    const version = String(item.version || "").trim();
    if (!version) {
      throw new BadRequestError("version is required; read it from GET /api/taiga/algorithms");
    }
    if (version.length > 100) {
      throw new BadRequestError("model version is too long");
    }

    const params = item.params === undefined ? {} : item.params;
    if (!params || typeof params !== "object" || Array.isArray(params)) {
      throw new BadRequestError("params must be a JSON object");
    }
    if (JSON.stringify(params).length > MAX_PARAMS_BYTES) {
      throw new BadRequestError("params are too large");
    }
    const hash = paramsHash(params);
    const key = model + "\u0000" + version + "\u0000" + hash;
    if (seen[key]) continue;
    seen[key] = true;

    const sessionId = session.id;
    let analysis = findAnalysis(app, sessionId, model, version, hash);
    if (!analysis) {
      analysis = createAnalysis(app, {
        session: sessionId,
        model: model,
        model_version: version,
        params: params,
        params_hash: hash,
      });
    }
    let job = findJob(app, analysis.id);
    if (!job) {
      job = createJob(app, analysis);
    }
    analyses.push(analysis);
    jobs.push(job);
  }

  return { analyses: analyses, jobs: jobs };
}

function serializeAnalysis(record, summary) {
  const stages = jsonFields.read(record, "stages", null);
  const metrics = jsonFields.read(record, "metrics", null);
  const quality = jsonFields.read(record, "quality", null);
  const result = {
    id: record.id,
    session: record.get("session"),
    model: record.get("model"),
    model_version: record.get("model_version"),
    params_hash: record.get("params_hash"),
    status: record.get("status"),
    error: record.get("error") || "",
    created: record.get("created") || "",
    updated: record.get("updated") || "",
  };

  if (summary) {
    result.metrics = metrics;
    result.quality = quality;
    result.stages = stages
      ? {
          epoch_seconds: stages.epoch_seconds,
          start_unix_ms: stages.start_unix_ms,
          count: stages.count,
          segments: stages.segments,
        }
      : null;
    return result;
  }

  return Object.assign(result, {
    params: jsonFields.read(record, "params", {}),
    contract_version: record.get("contract_version") || 0,
    stages: stages,
    metrics: metrics,
    metric_support: jsonFields.read(record, "metric_support", null),
    quality: quality,
    capabilities: jsonFields.read(record, "capabilities", null),
    provenance: jsonFields.read(record, "provenance", null),
    extras: jsonFields.read(record, "extras", null),
    warnings: jsonFields.read(record, "warnings", []),
  });
}

function analysisSummary(records) {
  const summary = { total: records.length, queued: 0, running: 0, done: 0, failed: 0 };
  for (const record of records) {
    const status = String(record.get("status"));
    if (summary[status] !== undefined) summary[status] += 1;
  }
  return summary;
}

function validateResult(result) {
  if (!result || typeof result !== "object" || Array.isArray(result)) {
    throw new BadRequestError("analysis result must be an object");
  }
  if (typeof result.model !== "string" || typeof result.model_version !== "string") {
    throw new BadRequestError("analysis result is missing model identity");
  }
  if (!Number.isInteger(result.contract_version)) {
    throw new BadRequestError("analysis result is missing contract_version");
  }
  const stages = result.stages;
  if (!stages || !Number.isInteger(stages.count) || !Array.isArray(stages.segments)) {
    throw new BadRequestError("analysis result has no stage timeline");
  }
  let covered = 0;
  for (const segment of stages.segments) {
    if (!segment || !Number.isInteger(segment.count) || segment.count < 1) {
      throw new BadRequestError("analysis result has an invalid stage segment");
    }
    covered += segment.count;
  }
  if (covered !== stages.count) {
    throw new BadRequestError("stage segments do not cover every epoch");
  }
  for (const name of ["metrics", "quality", "capabilities", "provenance"]) {
    if (!result[name] || typeof result[name] !== "object" || Array.isArray(result[name])) {
      throw new BadRequestError("analysis result is missing " + name);
    }
  }
  return result;
}

function markRunning(app, job) {
  const analysisId = job.get("analysis");
  if (!analysisId) return;
  try {
    const analysis = app.findRecordById("analyses", analysisId);
    analysis.set("status", "running");
    analysis.set("error", "");
    app.save(analysis);
  } catch (_) {
    // The job can still run; the result route will fail clearly if the analysis
    // is gone.
  }
}

function complete(app, job, result) {
  let analysis = null;
  try {
    analysis = app.findRecordById("analyses", job.get("analysis"));
  } catch (_) {
    return { ok: false, error: "analysis row is missing" };
  }

  try {
    validateResult(result);
  } catch (err) {
    fail(app, job, String(err && err.message ? err.message : err), false);
    return { ok: false, error: String(err) };
  }

  if (result.model !== analysis.get("model")) {
    fail(app, job, "worker reported a different model", false);
    return { ok: false, error: "model mismatch" };
  }
  const analysisVersion = String(analysis.get("model_version") || "");
  if (analysisVersion === "latest" || analysisVersion === "unversioned") {
    analysis.set("model_version", result.model_version);
  } else if (result.model_version !== analysisVersion) {
    fail(app, job, "worker reported a different model version", false);
    return { ok: false, error: "model version mismatch" };
  }
  if (result.params_hash && result.params_hash !== analysis.get("params_hash")) {
    fail(app, job, "worker reported a different params hash", false);
    return { ok: false, error: "params hash mismatch" };
  }

  analysis.set("status", "done");
  analysis.set("error", "");
  analysis.set("contract_version", result.contract_version);
  for (const name of [
    "stages",
    "metrics",
    "metric_support",
    "quality",
    "capabilities",
    "provenance",
    "extras",
    "warnings",
  ]) {
    analysis.set(name, result[name] === undefined ? null : result[name]);
  }
  app.save(analysis);

  job.set("status", "done");
  job.set("lease_until", "");
  job.set("error", "");

  const provenance = result.provenance || {};
  job.set("result", {
    analysis: analysis.id,
    runtime_s: provenance.runtime_s === undefined ? null : provenance.runtime_s,
    peak_rss_mb: provenance.peak_rss_mb === undefined ? null : provenance.peak_rss_mb,
  });
  app.save(job);
  return { ok: true, analysis: analysis };
}

function fail(app, job, rawError, retryable) {
  const message = String(rawError || "analysis failed").slice(0, MAX_ERROR);
  const attempts = parseInt(job.get("attempts") || 0, 10);
  const maxAttempts = Math.max(
    1,
    parseInt($os.getenv("TAIGA_JOB_MAX_ATTEMPTS") || "3", 10) || 3
  );
  const shouldRetry = !!retryable && attempts < maxAttempts;

  let analysis = null;
  try {
    analysis = app.findRecordById("analyses", job.get("analysis"));
  } catch (_) {
    // Keep the job failure even if the analysis disappeared.
  }

  if (shouldRetry) {
    const delay = Math.min(300, Math.max(30, Math.pow(2, Math.max(0, attempts - 1)) * 30));
    job.set("status", "queued");
    job.set("available_at", dates.afterSeconds(delay));
    job.set("worker", "");
    job.set("lease_until", "");
    job.set("error", message);
    if (analysis) {
      analysis.set("status", "queued");
      analysis.set("error", message);
      app.save(analysis);
    }
  } else {
    job.set("status", "failed");
    job.set("lease_until", "");
    job.set("error", message);
    if (analysis) {
      analysis.set("status", "failed");
      analysis.set("error", message);
      app.save(analysis);
    }
  }
  app.save(job);
  return { ok: false, error: message, retryable: shouldRetry };
}

function retry(app, job) {
  if (String(job.get("status")) !== "failed") {
    throw new ApiError(409, "only failed jobs can be retried");
  }
  job.set("status", "queued");
  job.set("available_at", dates.nowString());
  job.set("worker", "");
  job.set("lease_until", "");
  job.set("error", "");
  app.save(job);

  try {
    const analysis = app.findRecordById("analyses", job.get("analysis"));
    analysis.set("status", "queued");
    analysis.set("error", "");
    app.save(analysis);
  } catch (_) {
    // The job still retries; the result route will report a missing analysis.
  }
  return job;
}

module.exports = {
  MAX_MODELS: MAX_MODELS,
  MAX_PARAMS_BYTES: MAX_PARAMS_BYTES,
  analysisSummary: analysisSummary,
  complete: complete,
  createAnalysis: createAnalysis,
  fail: fail,
  findAnalysis: findAnalysis,
  findJob: findJob,
  markRunning: markRunning,
  paramsHash: paramsHash,
  requestAnalyses: requestAnalyses,
  retry: retry,
  serializeAnalysis: serializeAnalysis,
  validateResult: validateResult,
};
