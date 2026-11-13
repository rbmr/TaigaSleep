package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.insights.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One line under the night's title: the tracker, the clock times of the local recording and
 * how long it ran. [lineFormat] and [startOnlyFormat] are resources with positional
 * placeholders. A recording without a usable end has no end time and no length to show.
 */
fun formatNightHeader(
    tracker: String,
    startUtcMs: Long,
    endUtcMs: Long?,
    lineFormat: String,
    startOnlyFormat: String,
    locale: Locale = Locale.getDefault(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
  val start = clockTime(startUtcMs, locale, zone)
  val end = endUtcMs?.takeIf { startUtcMs > 0 && it > startUtcMs }
  return if (end == null) {
    String.format(locale, startOnlyFormat, tracker, start)
  } else {
    String.format(
        locale,
        lineFormat,
        tracker,
        start,
        clockTime(end, locale, zone),
        formatDuration(end - startUtcMs),
    )
  }
}

private fun clockTime(ms: Long, locale: Locale, zone: TimeZone): String =
    if (ms <= 0) "-"
    else SimpleDateFormat("HH:mm", locale).apply { timeZone = zone }.format(Date(ms))
