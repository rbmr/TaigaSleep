/// <reference path="../pb_data/types.d.ts" />

// One row per overnight recording. The app owns these records directly through
// the PocketBase SDK; the worker only reads the session referenced by a job.
migrate((app) => {
  const collection = new Collection({
    id: "pbc_3660498186",
    name: "sessions",
    type: "base",
    system: false,
    listRule: "owner = @request.auth.id",
    viewRule: "owner = @request.auth.id",
    createRule: '@request.auth.id != "" && owner = @request.auth.id',
    updateRule: "owner = @request.auth.id",
    deleteRule: "owner = @request.auth.id",
    fields: [
      {
        name: "owner",
        type: "relation",
        required: true,
        collectionId: "_pb_users_auth_",
        cascadeDelete: true,
        minSelect: 1,
        maxSelect: 1,
      },
      {
        name: "device",
        type: "text",
        max: 120,
      },
      {
        name: "started_at",
        type: "date",
        required: true,
      },
      {
        name: "ended_at",
        type: "date",
      },
      {
        name: "duration_s",
        type: "number",
        min: 0,
      },
      {
        name: "state",
        type: "select",
        required: true,
        values: ["recording", "stopped", "forced_stop", "interrupted"],
        maxSelect: 1,
      },
      {
        name: "upload_status",
        type: "select",
        values: ["none", "pending", "uploaded", "failed"],
        maxSelect: 1,
      },
      {
        name: "analysis",
        type: "select",
        values: ["none", "queued", "running", "done", "failed"],
        maxSelect: 1,
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
        name: "quality",
        type: "number",
        min: 0,
        max: 1,
      },
      {
        name: "error",
        type: "text",
        max: 2000,
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
      "CREATE INDEX idx_sessions_owner_started ON sessions (owner, started_at)",
      "CREATE INDEX idx_sessions_upload_status ON sessions (upload_status)",
    ],
  });

  return app.save(collection);
}, (app) => {
  return app.delete(app.findCollectionByNameOrId("sessions"));
});
