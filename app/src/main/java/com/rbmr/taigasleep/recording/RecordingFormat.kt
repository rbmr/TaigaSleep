package com.rbmr.taigasleep.recording

/** Formatting for live values. Shared by the active screen and the notification. */
object RecordingFormat {
  /** `h:mm:ss`, for a recording duration or an elapsed timer. */
  fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
  }

  fun age(ms: Long): String =
      if (ms < 1000) "$ms ms" else if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"

  /** A measured stream rate in whole Hz, as the screen and the notification show it. */
  fun rateHz(rateHz: Double): Long = Math.round(rateHz)

  /** One stream with its measured rate, for example `ECG 130 Hz`. */
  fun rate(label: String, hz: Double): String = "$label ${rateHz(hz)} Hz"
}
