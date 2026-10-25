/// <reference path="../pb_data/types.d.ts" />

// One analysis row per (session, model, model_version, params_hash). Jobs
// reference an analysis, so a session can hold several algorithms' results at
// once and a retry never duplicates a result.
migrate((app) => {
  const sessions = app.findCollectionByNameOrId("sessions");
  const jobs = app.findCollectionByNameOrId("jobs");
  const workers = app.findCollectionByNameOrId("workers");

  const analysesCollectionId = "pbc_1904815773";
  const analyses = new Collection({
    id: analysesCollectionId,
    name: "analyses",
    type: "base",
    system: false,
    listRule: "session.owner = @request.auth.id",
    viewRule: "session.owner = @request.auth.id",
    createRule: null,
    updateRule: null,
    deleteRule: null,
    fields: [
      {
        name: "session",
        type: "relation",
        required: true,
        collectionId: "pbc_3660498186",
        cascadeDelete: true,
        minSelect: 1,
        maxSelect: 1,
      },
      {
        name: "model",
        type: "text",
        required: true,
        max: 100,
      },
      {
        name: "model_version",
        type: "text",
        required: true,
        max: 100,
      },
      {
        name: "params",
        type: "json",
        maxSize: 16384,
      },
      {
        name: "params_hash",
        type: "text",
        required: true,
        max: 32,
      },
      {
        name: "status",
        type: "select",
        required: true,
        values: ["queued", "running", "done", "failed"],
        maxSelect: 1,
      },
      {
        name: "contract_version",
        type: "number",
        min: 0,
      },
      {
        name: "stages",
        type: "json",
        maxSize: 1048576,
      },
      {
        name: "metrics",
        type: "json",
        maxSize: 1048576,
      },
      {
        name: "metric_support",
        type: "json",
        maxSize: 1048576,
      },
      {
        name: "quality",
        type: "json",
        maxSize: 262144,
      },
      {
        name: "capabilities",
        type: "json",
        maxSize: 65536,
      },
      {
        name: "provenance",
        type: "json",
        maxSize: 262144,
      },
      {
        name: "extras",
        type: "json",
        maxSize: 1048576,
      },
      {
        name: "warnings",
        type: "json",
        maxSize: 65536,
      },
      {
        name: "error",
        type: "text",
        max: 4000,
      },
      {
        name: "created",
        type: "autodate",
        onCreate: true,
        onUpdate: false,
      },
      {
        name: "updated",
        type: "autodate",
        onCreate: true,
        onUpdate: true,
      },
    ],
    indexes: [
      "CREATE UNIQUE INDEX idx_analyses_key ON analyses (session, model, model_version, params_hash)",
      "CREATE INDEX idx_analyses_session_status ON analyses (session, status)",
      "CREATE INDEX idx_analyses_status_updated ON analyses (status, updated)",
    ],
  });
  app.save(analyses);

  jobs.fields.add(
    new RelationField({
      name: "analysis",
      collectionId: analysesCollectionId,
      cascadeDelete: true,
      minSelect: 0,
      maxSelect: 1,
    })
  );
  jobs.fields.add(new TextField({ name: "model_version", max: 100 }));
  jobs.fields.add(new TextField({ name: "params_hash", max: 32 }));
  jobs.indexes = jobs.indexes.filter((index) => index.indexOf("idx_jobs_idempotent") === -1);
  jobs.indexes = jobs.indexes.concat([
    "CREATE UNIQUE INDEX idx_jobs_analysis ON jobs (analysis) WHERE analysis != ''",
  ]);
  unmarshal(
    {
      updateRule: null,
      deleteRule: null,
    },
    jobs
  );
  app.save(jobs);

  workers.fields.add(new TextField({ name: "version", max: 100 }));
  workers.fields.add(new JSONField({ name: "algorithms", maxSize: 262144 }));
  workers.fields.add(new DateField({ name: "last_seen" }));
  app.save(workers);

  // Sessions no longer hold a single analysis result. Results live in
  // analyses, one row per algorithm/version/params.
  for (const name of ["analysis", "stages", "metrics", "quality", "error"]) {
    sessions.fields.removeByName(name);
  }
  app.save(sessions);

  // Legacy jobs predate analyses. Keep them visible but do not let a worker
  // claim an analysis it cannot reproduce; the user can request it again.
  const legacyJobs = app.findRecordsByFilter("jobs", "", "", 0, 0);
  for (const job of legacyJobs) {
    if (job.get("analysis")) continue;
    const analysis = new Record(analyses);
    analysis.set("session", job.get("session"));
    analysis.set("model", job.get("model") || "unknown");
    analysis.set("model_version", "legacy");
    analysis.set("params", {});
    analysis.set("params_hash", "legacy-" + job.id);
    analysis.set("status", "failed");
    analysis.set("contract_version", 0);
    analysis.set("error", "legacy job: request analysis again");
    app.save(analysis);

    job.set("analysis", analysis.id);
    job.set("model_version", "legacy");
    job.set("params_hash", "legacy-" + job.id);
    job.set("status", "failed");
    job.set("worker", "");
    job.set("lease_until", "");
    job.set("error", "legacy job: request analysis again");
    app.save(job);
  }

  return null;
}, (app) => {
  const sessions = app.findCollectionByNameOrId("sessions");
  const jobs = app.findCollectionByNameOrId("jobs");
  const workers = app.findCollectionByNameOrId("workers");

  jobs.indexes = jobs.indexes.filter((index) => index.indexOf("idx_jobs_analysis") === -1);
  jobs.indexes = jobs.indexes.concat([
    "CREATE UNIQUE INDEX idx_jobs_idempotent ON jobs (session, model, kind)",
  ]);
  jobs.fields.removeByName("analysis");
  jobs.fields.removeByName("model_version");
  jobs.fields.removeByName("params_hash");
  unmarshal(
    {
      updateRule: "session.owner = @request.auth.id",
      deleteRule: "session.owner = @request.auth.id",
    },
    jobs
  );
  app.save(jobs);

  workers.fields.removeByName("version");
  workers.fields.removeByName("algorithms");
  workers.fields.removeByName("last_seen");
  app.save(workers);

  sessions.fields.add(
    new SelectField({
      name: "analysis",
      values: ["none", "queued", "running", "done", "failed"],
      maxSelect: 1,
    })
  );
  sessions.fields.add(new JSONField({ name: "stages", maxSize: 1048576 }));
  sessions.fields.add(new JSONField({ name: "metrics", maxSize: 1048576 }));
  sessions.fields.add(new NumberField({ name: "quality", min: 0, max: 1 }));
  sessions.fields.add(new TextField({ name: "error", max: 2000 }));
  app.save(sessions);

  try {
    app.delete(app.findCollectionByNameOrId("analyses"));
  } catch (_) {
    // Already gone.
  }
  return null;
});
