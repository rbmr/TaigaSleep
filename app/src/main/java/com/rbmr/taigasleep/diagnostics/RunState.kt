package com.rbmr.taigasleep.diagnostics

import java.io.File
import java.io.RandomAccessFile

/** What the previous process run left behind, derived from its diagnostics file(s). */
data class PreviousRun(
    val file: String? = null,
    val pid: Int? = null,
    val lastEventMs: Long? = null,
    val lastElapsedMs: Long? = null,
    val lastType: String? = null,
    val lastHeartbeatMs: Long? = null,
    // The last heartbeat's key fields: the best guess for what the phone looked like at the end.
    val lastState: Map<String, String> = emptyMap(),
) {
  /** Whole seconds between the previous run's last heartbeat and [nowMs], or null if none. */
  fun secondsSinceLastHeartbeat(nowMs: Long): Long? = lastHeartbeatMs?.let { (nowMs - it) / 1000 }

  /** The device clock-since-boot went backwards, so the phone rebooted since that line. */
  fun rebooted(nowElapsedMs: Long): Boolean? = lastElapsedMs?.let { nowElapsedMs < it }

  companion object {
    private const val TAIL_BYTES = 128 * 1024
    private const val MAX_FILES = 3

    /**
     * Reads the tails of the newest files in [dir]. Call before the new run creates its own file.
     * The last line of any file may be cut off by a kill; unparsable lines are skipped.
     */
    fun scan(dir: File): PreviousRun {
      var result = PreviousRun()
      for (file in DiagnosticsStore.list(dir).asReversed().take(MAX_FILES)) {
        val lines = tailLines(file)
        val metas = lines.mapNotNull { LineMeta.parse(it) }
        if (result.file == null) {
          val last = metas.lastOrNull() ?: continue
          result =
              PreviousRun(file.name, last.pid, last.tMs, last.elapsedMs, last.type, null)
        }
        val heartbeat = metas.lastOrNull { it.type == "diag_heartbeat" }
        if (heartbeat != null) {
          val line = lines.last { LineMeta.parse(it)?.type == "diag_heartbeat" }
          return result.copy(lastHeartbeatMs = heartbeat.tMs, lastState = fieldsOf(line, STATE_KEYS))
        }
      }
      return result
    }

    private val STATE_KEYS =
        listOf(
            "uptime_s", "bucket", "whitelisted", "background_restricted", "power_save", "doze",
            "interactive", "locked", "plugged", "charging", "battery_pct", "importance",
        )

    /** Pulls top-level `"key":value` pairs out of a heartbeat line without a JSON parser. */
    fun fieldsOf(line: String, keys: List<String>): Map<String, String> {
      val found = linkedMapOf<String, String>()
      for (key in keys) {
        val m = Regex(""""$key":("(?:[^"\\]|\\.)*"|[^,}\[{]+)""").find(line) ?: continue
        found[key] = m.groupValues[1].trim('"')
      }
      return found
    }

    private fun tailLines(file: File): List<String> =
        try {
          RandomAccessFile(file, "r").use { raf ->
            val start = maxOf(0L, raf.length() - TAIL_BYTES)
            raf.seek(start)
            val buf = ByteArray((raf.length() - start).toInt())
            raf.readFully(buf)
            val lines = String(buf, Charsets.UTF_8).split('\n')
            // When we started mid-file the first piece is a partial line.
            if (start > 0) lines.drop(1) else lines
          }
        } catch (_: Exception) {
          emptyList()
        }
  }
}

/**
 * One tiny file holding `state=running|clean`, rewritten on state changes. At the next start
 * "running" means the previous run never reached a clean finish.
 */
class RunMarker(private val file: File) {
  fun read(): String? =
      try {
        file.readText().trim().substringAfter("state=").substringBefore(' ').ifEmpty { null }
      } catch (_: Exception) {
        null
      }

  fun write(state: String, pid: Int, tMs: Long) {
    try {
      file.parentFile?.mkdirs()
      val tmp = File(file.path + ".tmp")
      tmp.writeText("state=$state pid=$pid t_ms=$tMs\n")
      if (!tmp.renameTo(file)) file.writeText(tmp.readText())
    } catch (_: Exception) {}
  }
}

/** Remembers which exit-info entries were already logged, persisted one `timestamp:pid` per line. */
class ExitInfoDedupe(private val file: File, private val max: Int = 500) {
  private val seen: LinkedHashSet<String> by lazy {
    val set = LinkedHashSet<String>()
    try {
      file.forEachLine { if (it.isNotBlank()) set.add(it.trim()) }
    } catch (_: Exception) {}
    set
  }

  /** True the first time a key is seen; false for every repeat, across process restarts. */
  fun claim(timestamp: Long, pid: Int): Boolean = seen.add("$timestamp:$pid")

  fun save() {
    try {
      file.parentFile?.mkdirs()
      file.writeText(seen.toList().takeLast(max).joinToString("\n", postfix = "\n"))
    } catch (_: Exception) {}
  }
}
