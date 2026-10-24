package com.rbmr.taigasleep.recording

/** Formatting for live values. Shared by the active screen and the notification. */
object RecordingFormat {
  /** `h:mm:ss`, for a session duration or an elapsed timer. */
  fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
  }

  /** A compact duration for prose: `<1 min`, `12 min`, `1 h 02 min`. */
  fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
      s < 60 -> "<1 min"
      s < 3600 -> "${s / 60} min"
      else -> "%d h %02d min".format(s / 3600, (s % 3600) / 60)
    }
  }

  fun age(ms: Long): String =
      if (ms < 1000) "$ms ms" else if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"
}
