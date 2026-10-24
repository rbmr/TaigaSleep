/// <reference path="../pb_data/types.d.ts" />

// Durable analysis queue. Claiming is done by the worker route in
// pb_hooks/main.pb.js inside a database transaction; the unique index keeps a
// session/model/kind triple from ever being queued twice.
migrate((app) => {
  const collection = new Collection({
    id: "pbc_2409499253",
    name: "jobs",
    type: "base",
    system: false,
    listRule: "session.owner = @request.auth.id",
    viewRule: "session.owner = @request.auth.id",
    createRule: '@request.auth.id != "" && session.owner = @request.auth.id',
    updateRule: "session.owner = @request.auth.id",
    deleteRule: "session.owner = @request.auth.id",
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
        name: "kind",
        type: "select",
        required: true,
        values: ["analysis"],
        maxSelect: 1,
      },
      {
        name: "model",
        type: "text",
        required: true,
        max: 100,
      },
      {
        name: "status",
        type: "select",
        required: true,
        values: ["queued", "running", "done", "failed"],
        maxSelect: 1,
      },
      {
        name: "attempts",
        type: "number",
        min: 0,
      },
      {
        name: "available_at",
        type: "date",
        required: true,
      },
      {
        name: "lease_until",
        type: "date",
      },
      {
        name: "worker",
        type: "text",
        max: 120,
      },
      {
        name: "error",
        type: "text",
        max: 4000,
      },
      {
        name: "result",
        type: "json",
        maxSize: 1048576,
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
      "CREATE UNIQUE INDEX idx_jobs_idempotent ON jobs (session, model, kind)",
      "CREATE INDEX idx_jobs_claim ON jobs (status, available_at)",
      "CREATE INDEX idx_jobs_lease ON jobs (status, lease_until)",
    ],
  });

  return app.save(collection);
}, (app) => {
  return app.delete(app.findCollectionByNameOrId("jobs"));
});
