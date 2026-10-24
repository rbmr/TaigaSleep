// Job queue helpers.
//
// All writes are done through a transaction app instance provided by
// $app.runInTransaction(). PocketBase serializes write transactions, so the
// select + conditional update below cannot hand the same job to two workers.

const dates = require(`${__hooks}/lib/dates.js`);

function serializeJob(record) {
  return {
    id: record.id,
    session: record.get("session"),
    kind: record.get("kind"),
    model: record.get("model"),
    status: record.get("status"),
    attempts: record.get("attempts"),
    available_at: record.get("available_at"),
    lease_until: record.get("lease_until"),
    worker: record.get("worker"),
    error: record.get("error"),
    result: record.get("result"),
    created: record.get("created"),
    updated: record.get("updated"),
  };
}

function claim(txApp, worker, leaseSeconds) {
  const now = dates.nowString();
  const leaseUntil = dates.afterSeconds(leaseSeconds);

  const rows = arrayOf(new DynamicModel({ id: "" }));
  txApp.db()
    .newQuery(
      "SELECT id FROM jobs " +
        "WHERE status = 'queued' AND available_at <= {:now} " +
        "ORDER BY available_at ASC, created ASC LIMIT 1"
    )
    .bind({ now: now })
    .all(rows);

  if (!rows.length) {
    return null;
  }

  const id = rows[0].id;
  const result = txApp.db()
    .newQuery(
      "UPDATE jobs SET status = 'running', attempts = attempts + 1, " +
        "lease_until = {:lease}, worker = {:worker}, error = '', updated = {:now} " +
        "WHERE id = {:id} AND status = 'queued'"
    )
    .bind({ lease: leaseUntil, worker: worker, id: id, now: now })
    .execute();

  // The conditional update is the real guard: if another transaction won the
  // race, no row is changed and this worker gets no job.
  if (result.rowsAffected() !== 1) {
    return null;
  }

  return serializeJob(txApp.findRecordById("jobs", id));
}

function requeueExpired(txApp) {
  const now = dates.nowString();
  const result = txApp.db()
    .newQuery(
      "UPDATE jobs SET status = 'queued', worker = '', lease_until = '', " +
        "available_at = {:now}, error = 'lease expired', updated = {:now} " +
        "WHERE status = 'running' AND lease_until != '' AND lease_until < {:now}"
    )
    .bind({ now: now })
    .execute();

  return result.rowsAffected();
}

module.exports = {
  claim: claim,
  requeueExpired: requeueExpired,
  serializeJob: serializeJob,
};
