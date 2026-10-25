/// <reference path="../pb_data/types.d.ts" />

// Taiga Sleep API hooks.
//
// Routes are thin wrappers around lib/*; the handlers keep their module scope
// because PocketBase executes route actions outside the registering module.
// Job claiming, analysis creation and result storage are transactional.

routerAdd("GET", "/api/taiga/health", (e) => {
  return e.json(200, {
    status: "ok",
    service: "taiga-sleep",
    time: new Date().toISOString(),
  });
});

routerAdd(
  "GET",
  "/api/taiga/auth-check",
  (e) => {
    return e.json(200, {
      ok: true,
      id: e.auth.id,
      collection: e.auth.collection().name,
    });
  },
  $apis.requireAuth()
);

// Recording upload routes (issue #10). These are owner-only and deliberately
// separate from the analysis request routes. Keep the route wrappers in this
// bundle so their module scope survives; the handlers live in lib/uploads.js.
routerAdd(
  "POST",
  "/api/taiga/sessions",
  (e) => require(`${__hooks}/lib/uploads.js`).createSession(e),
  $apis.requireAuth()
);

routerAdd(
  "GET",
  "/api/taiga/sessions/{id}/upload",
  (e) => require(`${__hooks}/lib/uploads.js`).getUpload(e),
  $apis.requireAuth()
);

routerAdd(
  "PUT",
  "/api/taiga/sessions/{id}/upload/meta",
  (e) => require(`${__hooks}/lib/uploads.js`).putMeta(e),
  $apis.requireAuth()
);

routerAdd(
  "PUT",
  "/api/taiga/sessions/{id}/upload/file/{file}",
  (e) => require(`${__hooks}/lib/uploads.js`).putFile(e),
  $apis.requireAuth()
);

routerAdd(
  "POST",
  "/api/taiga/sessions/{id}/upload/complete",
  (e) => require(`${__hooks}/lib/uploads.js`).completeUpload(e),
  $apis.requireAuth()
);

// Analysis request, result views, retries and algorithm discovery.
routerAdd(
  "POST",
  "/api/taiga/sessions/{id}/analysis",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).requestAnalyses(e),
  $apis.requireAuth()
);

routerAdd(
  "GET",
  "/api/taiga/sessions",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).listSessions(e),
  $apis.requireAuth()
);

routerAdd(
  "GET",
  "/api/taiga/sessions/{id}",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).getSession(e),
  $apis.requireAuth()
);

routerAdd(
  "GET",
  "/api/taiga/analyses/{id}",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).getAnalysis(e),
  $apis.requireAuth()
);

routerAdd(
  "POST",
  "/api/taiga/jobs/{id}/retry",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).retryJob(e),
  $apis.requireAuth()
);

routerAdd(
  "GET",
  "/api/taiga/algorithms",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).listAlgorithms(e),
  $apis.requireAuth()
);

routerAdd(
  "POST",
  "/api/taiga/worker/capabilities",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).reportCapabilities(e),
  $apis.requireAuth("workers")
);

routerAdd(
  "GET",
  "/api/taiga/worker/sessions/{id}/manifest",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).workerManifest(e),
  $apis.requireAuth("workers")
);

routerAdd(
  "GET",
  "/api/taiga/worker/sessions/{id}/files/{file}",
  (e) => require(`${__hooks}/lib/analysis_routes.js`).workerFile(e),
  $apis.requireAuth("workers")
);

routerAdd(
  "POST",
  "/api/taiga/worker/jobs/claim",
  (e) => {
    const jobs = require(`${__hooks}/lib/jobs.js`);
    const body = e.requestInfo().body || {};

    const worker = toString(body.worker || "").trim();
    if (!worker) {
      throw new BadRequestError("worker is required");
    }
    if (worker.length > 120) {
      throw new BadRequestError("worker is too long");
    }

    let leaseSeconds = parseInt(body.lease_seconds, 10);
    if (isNaN(leaseSeconds)) {
      leaseSeconds = parseInt($os.getenv("TAIGA_JOB_LEASE_S") || "1800", 10);
    }
    if (isNaN(leaseSeconds) || leaseSeconds < 1) {
      leaseSeconds = 1800;
    }
    if (leaseSeconds > 86400) {
      leaseSeconds = 86400;
    }

    const algorithms = Array.isArray(body.algorithms)
      ? body.algorithms.map((item) => toString(item)).slice(0, 100)
      : [];

    let claimed = null;
    $app.runInTransaction((txApp) => {
      claimed = jobs.claim(txApp, worker, leaseSeconds, algorithms);
    });

    return e.json(200, { job: claimed });
  },
  $apis.requireAuth("workers")
);

routerAdd(
  "POST",
  "/api/taiga/worker/jobs/{id}/result",
  (e) => {
    const jobs = require(`${__hooks}/lib/jobs.js`);
    const analyses = require(`${__hooks}/lib/analyses.js`);
    const body = e.requestInfo().body || {};

    const worker = toString(body.worker || "").trim();
    if (!worker) {
      throw new BadRequestError("worker is required");
    }

    const status = toString(body.status || "").trim();
    if (status !== "done" && status !== "failed") {
      throw new BadRequestError("status must be 'done' or 'failed'");
    }

    const error = toString(body.error || "").slice(0, 4000);
    const retryable = body.retryable === true;
    const result = body.result === undefined ? null : body.result;

    let resultJob = null;
    $app.runInTransaction((txApp) => {
      let record;
      try {
        record = txApp.findRecordById("jobs", e.request.pathValue("id"));
      } catch (_) {
        throw new NotFoundError("job not found");
      }

      if (toString(record.get("worker")) !== worker) {
        throw new ForbiddenError("job is leased by another worker");
      }

      const current = toString(record.get("status"));
      if (current === "done" || current === "failed") {
        // A retried completion request after a dropped response must not fail.
        resultJob = jobs.serializeJob(record);
        return;
      }
      if (current !== "running") {
        throw new BadRequestError("job is not running");
      }

      if (status === "done") {
        const analysisResult = result && result.analysis ? result.analysis : null;
        if (!analysisResult) {
          analyses.fail(txApp, record, "worker returned no analysis result", false);
        } else {
          analyses.complete(txApp, record, analysisResult);
        }
      } else {
        analyses.fail(txApp, record, error, retryable);
      }

      resultJob = jobs.serializeJob(record);
    });

    return e.json(200, { job: resultJob });
  },
  $apis.requireAuth("workers")
);

// The cron job does this automatically every minute; this route exists so an
// operator (or the integration tests) can trigger the same operation without
// waiting for the next tick.
routerAdd(
  "POST",
  "/api/taiga/worker/jobs/requeue",
  (e) => {
    const jobs = require(`${__hooks}/lib/jobs.js`);
    let requeued = 0;
    $app.runInTransaction((txApp) => {
      requeued = jobs.requeueExpired(txApp);
    });

    return e.json(200, { requeued: requeued });
  },
  $apis.requireAuth("workers")
);

cronAdd("taiga_requeue_expired_jobs", "* * * * *", () => {
  const jobs = require(`${__hooks}/lib/jobs.js`);
  let requeued = 0;
  $app.runInTransaction((txApp) => {
    requeued = jobs.requeueExpired(txApp);
  });

  if (requeued > 0) {
    console.log("taiga: requeued " + requeued + " expired job(s)");
  }
});

// A deleted session must not leave recording bytes behind. PocketBase owns the
// files, so this is the only place that removes the directory.
onRecordAfterDeleteSuccess((e) => {
  const recordings = require(`${__hooks}/lib/recordings.js`);
  recordings.removeDir(e.record.id);
  e.next();
}, "sessions");

// Direct job creation is kept for tooling and tests. The app uses
// POST /api/taiga/sessions/{id}/analysis, which creates the analysis and job
// together. Here we fill in a matching analysis and force the queue state.
onRecordCreateRequest((e) => {
  const dates = require(`${__hooks}/lib/dates.js`);
  const analyses = require(`${__hooks}/lib/analyses.js`);

  const session = e.record.get("session");
  const model = toString(e.record.get("model") || "").trim();
  if (!model) {
    throw new BadRequestError("model is required");
  }
  const version = toString(e.record.get("model_version") || "").trim() || "unversioned";
  const hash = analyses.paramsHash({});

  let analysis = analyses.findAnalysis(e.app, session, model, version, hash);
  if (!analysis) {
    analysis = analyses.createAnalysis(e.app, {
      session: session,
      model: model,
      model_version: version,
      params: {},
      params_hash: hash,
    });
  }

  const existing = analyses.findJob(e.app, analysis.id);
  if (existing) {
    return e.json(200, existing);
  }

  e.record.set("analysis", analysis.id);
  e.record.set("model_version", version);
  e.record.set("params_hash", hash);
  e.record.set("status", "queued");
  e.record.set("attempts", 0);
  e.record.set("available_at", dates.nowString());
  e.record.set("worker", "");
  e.record.set("lease_until", "");
  e.record.set("error", "");

  e.next();
}, "jobs");

// Optional configuration of PocketBase's own settings. Everything is a no-op
// when the variables are unset, so the defaults in the migration remain.
onBootstrap((e) => {
  e.next();

  const appName = $os.getenv("TAIGA_APP_NAME");
  const appURL = $os.getenv("TAIGA_APP_URL");
  if (!appName && !appURL) {
    return;
  }

  const settings = e.app.settings();
  let changed = false;

  if (appName && settings.meta.appName !== appName) {
    settings.meta.appName = appName;
    changed = true;
  }
  if (appURL && settings.meta.appURL !== appURL) {
    settings.meta.appURL = appURL;
    changed = true;
  }

  if (changed) {
    e.app.save(settings);
  }
});



