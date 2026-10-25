// Job queue helpers.
//
// All writes are done through a transaction app instance provided by
// $app.runInTransaction(). PocketBase serializes write transactions, so the
// select + conditional update below cannot hand the same job to two workers.

const dates = require(`${__hooks}/lib/dates.js`);
const analyses = require(`${__hooks}/lib/analyses.js`);
const jsonFields = require(`${__hooks}/lib/json_fields.js`);

function serializeJob(record) {
  return {
    id: record.id,
    session: record.get("session"),
    analysis: record.get("analysis") || "",
    kind: record.get("kind"),
    model: record.get("model"),
    model_version: record.get("model_version") || "",
    params_hash: record.get("params_hash") || "",
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

function claim(txApp, worker, leaseSeconds, algorithms) {
  const now = dates.nowString();
  const leaseUntil = dates.afterSeconds(leaseSeconds);
  const params = { now: now };

  let filter = "status = 'queued' AND available_at <= {:now}";
  const cleaned = (algorithms || []).filter(
    (id) => typeof id === "string" && /^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)
  );
  if (cleaned.length) {
    const placeholders = cleaned.map((id, index) => {
      params["m" + index] = id;
      return "{:m" + index + "}";
    });
    filter += " AND model IN (" + placeholders.join(",") + ")";
  }

  const rows = arrayOf(new DynamicModel({ id: "" }));
  txApp
    .db()
    .newQuery(
      "SELECT id FROM jobs WHERE " +
        filter +
        " ORDER BY available_at ASC, created ASC LIMIT 1"
    )
    .bind(params)
    .all(rows);

  if (!rows.length) {
    return null;
  }

  const id = rows[0].id;
  const result = txApp
    .db()
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

  const jobRecord = txApp.findRecordById("jobs", id);
  const job = serializeJob(jobRecord);
  if (job.analysis) {
    try {
      const analysis = txApp.findRecordById("analyses", job.analysis);
      job.params = jsonFields.read(analysis, "params", {});
      job.model_version = analysis.get("model_version");
      analyses.markRunning(txApp, jobRecord);
    } catch (_) {
      // The result route will fail with a clear missing-analysis error.
    }
  }
  return job;
}

function requeueExpired(txApp) {
  const now = dates.nowString();

  // Keep the analysis status in sync with the job that is being requeued.
  txApp
    .db()
    .newQuery(
      "UPDATE analyses SET status = 'queued', error = 'lease expired', updated = {:now} " +
        "WHERE status = 'running' AND id IN (" +
        "SELECT analysis FROM jobs WHERE status = 'running' " +
        "AND lease_until != '' AND lease_until < {:now})"
    )
    .bind({ now: now })
    .execute();

  const result = txApp
    .db()
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
