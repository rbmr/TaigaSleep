// PocketBase JSON fields come back as raw bytes in the JSVM. These helpers
// normalize reads and keep writes consistent across hooks.

function read(record, name, fallback) {
  let value = record.get(name);
  if (Array.isArray(value)) {
    try {
      value = JSON.parse(toString(value));
    } catch (_) {
      return fallback;
    }
  } else if (typeof value === "string") {
    if (value === "") return fallback;
    try {
      value = JSON.parse(value);
    } catch (_) {
      return fallback;
    }
  }
  return value === null || value === undefined ? fallback : value;
}

function write(record, name, value) {
  record.set(name, value === undefined ? null : value);
}

module.exports = {
  read: read,
  write: write,
};
