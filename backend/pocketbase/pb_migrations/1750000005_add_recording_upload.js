/// <reference path="../pb_data/types.d.ts" />

// Recording upload state. A session record still describes the night, while
// the append-only byte streams live under <pb_data>/recordings/<session id>/.
// The `upload` JSON field stores the per-file offsets and validation state; the
// files themselves are the source of truth for their lengths.
migrate((app) => {
  const collection = app.findCollectionByNameOrId("sessions");

  collection.fields.add(
    new TextField({
      name: "client_session_id",
      max: 40,
    })
  );
  collection.fields.add(
    new JSONField({
      name: "upload",
      maxSize: 65536,
    })
  );
  collection.fields.add(
    new DateField({
      name: "uploaded_at",
    })
  );
  collection.fields.add(
    new TextField({
      name: "upload_error",
      max: 2000,
    })
  );

  // Partial index so legacy sessions created before uploads existed (empty
  // client_session_id) do not collide with each other.
  collection.indexes = collection.indexes.concat([
    "CREATE UNIQUE INDEX idx_sessions_client_id ON sessions (owner, client_session_id) WHERE client_session_id != ''",
  ]);

  return app.save(collection);
}, (app) => {
  const collection = app.findCollectionByNameOrId("sessions");

  collection.indexes = collection.indexes.filter(
    (index) => index.indexOf("idx_sessions_client_id") === -1
  );
  collection.fields.removeByName("client_session_id");
  collection.fields.removeByName("upload");
  collection.fields.removeByName("uploaded_at");
  collection.fields.removeByName("upload_error");

  return app.save(collection);
});
