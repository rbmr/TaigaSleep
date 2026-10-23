package com.rbmr.taigasleep.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.InputStream

/** Turns `ApplicationExitInfo` history into log fields; the main evidence of why we were killed. */
object ExitInfoReader {
  private const val MAX_ENTRIES = 30
  private const val TRACE_BYTES = 4096

  /** Entries not logged before, oldest first. Claims them in [dedupe]; caller must `save()`. */
  fun newEntries(context: Context, dedupe: ExitInfoDedupe): List<Map<String, Any?>> {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val all = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_ENTRIES)
    return all
        .asReversed()
        .filter { dedupe.claim(it.timestamp, it.pid) }
        .map { fields(it) }
  }

  fun fields(info: ApplicationExitInfo): Map<String, Any?> {
    val fields =
        linkedMapOf<String, Any?>(
            "event_ms" to info.timestamp,
            "exit_pid" to info.pid,
            "process_name" to info.processName,
            "reason" to Names.exitReason(info.reason),
            "reason_code" to info.reason,
            "status" to info.status,
            "importance" to Names.importance(info.importance),
            "importance_code" to info.importance,
            "description" to info.description,
            "pss_kb" to info.pss,
            "rss_kb" to info.rss,
        )
    if (Names.hasTrace(info.reason)) {
      fields["trace"] = DeviceState.safe { readTrace(info.traceInputStream) }
    }
    return fields
  }

  /** First ~4 KB of the trace; native tombstones are binary, so keep printable ASCII only. */
  private fun readTrace(stream: InputStream?): String? {
    if (stream == null) return null
    stream.use {
      val buf = ByteArray(TRACE_BYTES)
      val n = it.readNBytes(buf, 0, TRACE_BYTES)
      return buildString {
        for (i in 0 until n) {
          val c = buf[i].toInt().toChar()
          if (c == '\n' || c == '\t' || c in ' '..'~') append(c)
        }
      }
    }
  }
}
