// Frame-log validation for docs/recording-format.md.
//
// Validation reads one file at a time through the session root. PocketBase
// holds it as a Go byte slice, so a 25 MB log is not copied into a JavaScript
// array. Only complete frames are accepted.

const HEADER_SIZE = 8;
const MAX_PAYLOAD = 1 << 20;
const MAX_EVENT_LINE = 8 * 1024;
const MAGIC = [0x54, 0x53, 0x4c, 0x47]; // "TSLG"
const VERSION = 1;

const STREAM_BY_FILE = {
  "hr.log": 1,
  "ecg.log": 2,
  "acc.log": 3,
};

const FILE_BY_STREAM = {
  1: "hr.log",
  2: "ecg.log",
  3: "acc.log",
};

const CRC_TABLE = (() => {
  const table = new Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes, start, end) {
  let c = 0xffffffff;
  for (let i = start; i < end; i++) {
    c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function u32le(bytes, at) {
  return (
    (bytes[at] |
      (bytes[at + 1] << 8) |
      (bytes[at + 2] << 16) |
      (bytes[at + 3] << 24)) >>>
    0
  );
}

function headerOk(bytes, expectedStream) {
  return (
    bytes.length >= HEADER_SIZE &&
    bytes[0] === MAGIC[0] &&
    bytes[1] === MAGIC[1] &&
    bytes[2] === MAGIC[2] &&
    bytes[3] === MAGIC[3] &&
    bytes[4] === VERSION &&
    bytes[5] === expectedStream &&
    bytes[6] === 0 &&
    bytes[7] === 0
  );
}

/**
 * Validate complete frames from `fromOffset` to the end of the file.
 *
 * `fromOffset` must be the end of the last valid frame (or 0). The returned
 * `validEnd` is the end of the last valid frame; anything after it is either an
 * incomplete final frame (`state: "partial"`) or a bad frame
 * (`state: "corrupt"`). A corrupt frame is never accepted.
 */
function validateFile(root, name, expectedStream, fromOffset) {
  const data = root.readFile(name);
  const length = data.length;

  if (fromOffset < 0 || fromOffset > length) {
    throw new Error("invalid validation offset");
  }

  // The header must be valid even when we resume in the middle of the file.
  if (length > 0 && fromOffset > 0 && !headerOk(data.slice(0, HEADER_SIZE), expectedStream)) {
    return {
      validEnd: 0,
      frames: 0,
      state: "corrupt",
      error: "invalid frame-log header",
      corruptAt: 0,
      pending: 0,
      length: length,
    };
  }

  let at = fromOffset;
  let headerDone = fromOffset > 0;
  let validEnd = fromOffset;
  let frames = 0;

  while (at < length) {
    if (!headerDone) {
      if (length - at < HEADER_SIZE) break;
      if (!headerOk(data.slice(at, at + HEADER_SIZE), expectedStream)) {
        return {
          validEnd: validEnd,
          frames: frames,
          state: "corrupt",
          error: "invalid frame-log header",
          corruptAt: at,
          pending: length - at,
          length: length,
        };
      }
      at += HEADER_SIZE;
      headerDone = true;
      validEnd = at;
      continue;
    }

    if (length - at < 4) break;
    const payloadLength = u32le(data, at);
    if (payloadLength > MAX_PAYLOAD) {
      return {
        validEnd: validEnd,
        frames: frames,
        state: "corrupt",
        error: `frame payload too large (${payloadLength} bytes)`,
        corruptAt: at,
        pending: length - at,
        length: length,
      };
    }

    const frameSize = 4 + payloadLength + 4;
    if (length - at < frameSize) break;

    const expected = u32le(data, at + 4 + payloadLength);
    const actual = crc32(data, at + 4, at + 4 + payloadLength);
    if (expected !== actual) {
      return {
        validEnd: validEnd,
        frames: frames,
        state: "corrupt",
        error: "frame CRC mismatch",
        corruptAt: at,
        pending: length - at,
        length: length,
      };
    }

    at += frameSize;
    frames++;
    validEnd = at;
  }

  return {
    validEnd: validEnd,
    frames: frames,
    state: validEnd < length ? "partial" : "ok",
    error: "",
    corruptAt: -1,
    pending: length - validEnd,
    length: length,
  };
}

/** Validate complete events.jsonl lines; returns the offset after the last valid newline. */
function validateEvents(root, name, fromOffset) {
  const data = root.readFile(name);
  const length = data.length;

  if (fromOffset > length) {
    throw new Error("invalid validation offset");
  }
  if (fromOffset === length) {
    return { validEnd: fromOffset, lines: 0, state: "ok", error: "", corruptAt: -1, length: length };
  }

  let validEnd = fromOffset;
  let lines = 0;
  let lineStart = fromOffset;

  for (let i = fromOffset; i < length; i++) {
    if (i - lineStart > MAX_EVENT_LINE) {
      return {
        validEnd: validEnd,
        lines: lines,
        state: "corrupt",
        error: "event line is too long",
        corruptAt: lineStart,
        length: length,
      };
    }
    if (data[i] !== 0x0a) continue; // '\n'
    const text = String.fromCharCode.apply(null, data.slice(lineStart, i));
    if (text.trim() !== "") {
      let event = null;
      try {
        event = JSON.parse(text);
      } catch (_) {
        return {
          validEnd: validEnd,
          lines: lines,
          state: "corrupt",
          error: "invalid JSON event line",
          corruptAt: lineStart,
          length: length,
        };
      }
      if (
        !event ||
        typeof event !== "object" ||
        typeof event.type !== "string" ||
        typeof event.phone_ns !== "number" ||
        typeof event.elapsed_ns !== "number"
      ) {
        return {
          validEnd: validEnd,
          lines: lines,
          state: "corrupt",
          error: "event line is missing required fields",
          corruptAt: lineStart,
          length: length,
        };
      }
      lines++;
    }
    validEnd = i + 1;
    lineStart = i + 1;
  }

  return {
    validEnd: validEnd,
    lines: lines,
    state: validEnd < length ? "partial" : "ok",
    error: "",
    corruptAt: -1,
    length: length,
  };
}

module.exports = {
  HEADER_SIZE: HEADER_SIZE,
  MAX_PAYLOAD: MAX_PAYLOAD,
  STREAM_BY_FILE: STREAM_BY_FILE,
  FILE_BY_STREAM: FILE_BY_STREAM,
  crc32: crc32,
  validateFile: validateFile,
  validateEvents: validateEvents,
};
