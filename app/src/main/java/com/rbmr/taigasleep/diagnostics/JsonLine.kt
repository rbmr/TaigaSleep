package com.rbmr.taigasleep.diagnostics

/**
 * Minimal JSON encoder for diagnostics lines. Hand written so it is pure JVM (unit-testable) and so
 * a line always starts with the same fixed prefix, which [LineMeta] relies on when it scans the
 * previous run's files.
 */
object JsonLine {
  private const val MAX_STRING = 8192
  private const val MAX_DEPTH = 6
  private val RESERVED = setOf("t_ms", "elapsed_ms", "pid", "type")

  fun build(
      tMs: Long,
      elapsedMs: Long,
      pid: Int,
      type: String,
      fields: Map<String, Any?> = emptyMap(),
  ): String {
    val sb = StringBuilder(128)
    sb.append("{\"t_ms\":").append(tMs)
    sb.append(",\"elapsed_ms\":").append(elapsedMs)
    sb.append(",\"pid\":").append(pid)
    sb.append(",\"type\":")
    quote(sb, type)
    for ((key, value) in fields) {
      // A caller field must never shadow the fixed header keys.
      sb.append(',')
      quote(sb, if (key in RESERVED) "f_$key" else key)
      sb.append(':')
      value(sb, value, 0)
    }
    sb.append('}')
    return sb.toString()
  }

  private fun value(sb: StringBuilder, v: Any?, depth: Int) {
    when {
      v == null -> sb.append("null")
      v is Boolean -> sb.append(v)
      v is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v)
      v is Float -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v)
      v is Number -> sb.append(v.toLong())
      v is CharSequence -> quote(sb, v)
      v is Enum<*> -> quote(sb, v.name)
      depth >= MAX_DEPTH -> quote(sb, v.toString())
      v is Map<*, *> -> {
        sb.append('{')
        var first = true
        for ((key, inner) in v) {
          if (!first) sb.append(',')
          first = false
          quote(sb, key.toString())
          sb.append(':')
          value(sb, inner, depth + 1)
        }
        sb.append('}')
      }
      v is Iterable<*> -> {
        sb.append('[')
        var first = true
        for (inner in v) {
          if (!first) sb.append(',')
          first = false
          value(sb, inner, depth + 1)
        }
        sb.append(']')
      }
      v is Array<*> -> value(sb, v.asList(), depth)
      v is IntArray -> value(sb, v.asList(), depth)
      v is LongArray -> value(sb, v.asList(), depth)
      else -> quote(sb, v.toString())
    }
  }

  private fun quote(sb: StringBuilder, s: CharSequence) {
    sb.append('"')
    val limit = minOf(s.length, MAX_STRING)
    var i = 0
    while (i < limit) {
      val c = s[i]
      when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c == '\r' -> sb.append("\\r")
        c == '\t' -> sb.append("\\t")
        c < ' ' || c == '\u2028' || c == '\u2029' -> sb.append(String.format("\\u%04x", c.code))
        Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
          sb.append(c).append(s[i + 1])
          i++
        }
        // Lone surrogates are not valid UTF-8; escape them so the line stays decodable.
        Character.isSurrogate(c) -> sb.append(String.format("\\u%04x", c.code))
        else -> sb.append(c)
      }
      i++
    }
    if (s.length > MAX_STRING) sb.append("...[truncated ").append(s.length - MAX_STRING).append(']')
    sb.append('"')
  }
}

/** The fixed header of a diagnostics line, parsed without a JSON library. */
data class LineMeta(val tMs: Long, val elapsedMs: Long, val pid: Int, val type: String) {
  companion object {
    private val HEADER =
        Regex("""^\{"t_ms":(-?\d+),"elapsed_ms":(-?\d+),"pid":(-?\d+),"type":"([^"\\]*)"""")

    fun parse(line: String): LineMeta? {
      val m = HEADER.find(line) ?: return null
      return LineMeta(
          m.groupValues[1].toLong(),
          m.groupValues[2].toLong(),
          m.groupValues[3].toInt(),
          m.groupValues[4],
      )
    }
  }
}
