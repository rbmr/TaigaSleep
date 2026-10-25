// Worker capability reporting and the public algorithm list.

const dates = require(`${__hooks}/lib/dates.js`);
const jsonFields = require(`${__hooks}/lib/json_fields.js`);

const MODEL_RE = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const MAX_ALGORITHMS = 100;
const MAX_CAPABILITIES_BYTES = 262144;

function validateAlgorithm(item) {
  if (!item || typeof item !== "object" || Array.isArray(item)) {
    throw new BadRequestError("each algorithm must be an object");
  }
  if (!MODEL_RE.test(String(item.id || ""))) {
    throw new BadRequestError("algorithm id is invalid");
  }
  if (typeof item.version !== "string" || !item.version) {
    throw new BadRequestError("algorithm version is required");
  }
  if (typeof item.name !== "string" || !item.name) {
    throw new BadRequestError("algorithm name is required");
  }
  if (typeof item.license !== "string" || !item.license) {
    throw new BadRequestError("algorithm license is required");
  }
  return item;
}

function report(app, authRecord, body) {
  const worker = toString(body.worker || authRecord.get("name") || authRecord.id)
    .trim()
    .slice(0, 120);
  const version = toString(body.version || "").trim().slice(0, 100);
  const algorithms = body.algorithms;
  if (!Array.isArray(algorithms)) {
    throw new BadRequestError("algorithms must be an array");
  }
  if (algorithms.length > MAX_ALGORITHMS) {
    throw new BadRequestError("too many algorithms");
  }
  for (const item of algorithms) {
    validateAlgorithm(item);
  }
  if (JSON.stringify(algorithms).length > MAX_CAPABILITIES_BYTES) {
    throw new BadRequestError("algorithm capabilities are too large");
  }

  authRecord.set("name", worker);
  authRecord.set("version", version);
  authRecord.set("algorithms", algorithms);
  authRecord.set("last_seen", dates.nowString());
  app.save(authRecord);
  return { worker: worker, version: version, count: algorithms.length };
}

function list(app) {
  const byKey = {};
  const workers = app.findRecordsByFilter("workers", "", "", 0, 0);
  for (const worker of workers) {
    const items = jsonFields.read(worker, "algorithms", []);
    if (!Array.isArray(items)) continue;
    const workerName = worker.get("name") || worker.id;
    for (const item of items) {
      if (!item || !item.id || !item.version) continue;
      const key = String(item.id) + "\u0000" + String(item.version);
      if (!byKey[key]) {
        byKey[key] = { info: item, workers: [] };
      }
      if (byKey[key].workers.indexOf(workerName) === -1) {
        byKey[key].workers.push(workerName);
      }
    }
  }

  return Object.keys(byKey)
    .sort()
    .map((key) => {
      const info = Object.assign({}, byKey[key].info);
      info.workers = byKey[key].workers;
      info.worker_count = byKey[key].workers.length;
      return info;
    });
}

module.exports = {
  MAX_ALGORITHMS: MAX_ALGORITHMS,
  list: list,
  report: report,
};
