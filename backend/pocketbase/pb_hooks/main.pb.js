/// <reference path="../pb_data/types.d.ts" />

// Taiga Sleep API hooks.
//
// Everything here is deliberately small: a health endpoint, an auth check, the
// worker claim/complete/requeue routes and one cron job. Job claiming is the
// only place that needs a database transaction.

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

    let claimed = null;
    $app.runInTransaction((txApp) => {
      claimed = jobs.claim(txApp, worker, leaseSeconds);
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

      record.set("status", status);
      record.set("lease_until", "");
      record.set("error", error);
      record.set("result", body.result === undefined ? null : body.result);
      txApp.save(record);

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

// Fill in the queue fields for jobs created through the public API. Users may
// create a job for their own session, but they cannot pre-set its state.
// A deleted session must not leave recording bytes behind. PocketBase owns the
// files, so this is the only place that removes the directory.
onRecordAfterDeleteSuccess((e) => {
  const recordings = require(`${__hooks}/lib/recordings.js`);
  recordings.removeDir(e.record.id);
  e.next();
}, "sessions");

onRecordCreateRequest((e) => {
  const dates = require(`${__hooks}/lib/dates.js`);

  e.record.set("status", "queued");
  e.record.set("attempts", 0);
  e.record.set("available_at", dates.nowString());
  e.record.set("worker", "");
  e.record.set("lease_until", "");
  e.record.set("error", "");

  // Make creation idempotent on (session, model, kind). The unique index in the
  // migration is the race-safe backstop; this branch gives a friendly response.
  try {
    const existing = e.app.findFirstRecordByFilter(
      "jobs",
      "session = {:session} && model = {:model} && kind = {:kind}",
      {
        session: e.record.get("session"),
        model: e.record.get("model"),
        kind: e.record.get("kind"),
      }
    );
    return e.json(200, existing);
  } catch (_) {
    // No duplicate: continue with the normal create flow.
  }

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



