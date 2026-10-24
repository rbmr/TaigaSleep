// Small date helpers shared by the PocketBase hooks.
//
// PocketBase stores date fields as "YYYY-MM-DD HH:MM:SS.sssZ", which sorts
// correctly as text. Keep the same format when comparing or writing raw SQL so
// that lexicographic comparisons stay valid.

function toPbDate(date) {
  return date.toISOString().replace("T", " ");
}

function nowString() {
  return toPbDate(new Date());
}

function afterSeconds(seconds) {
  return toPbDate(new Date(Date.now() + seconds * 1000));
}

module.exports = {
  toPbDate: toPbDate,
  nowString: nowString,
  afterSeconds: afterSeconds,
};
