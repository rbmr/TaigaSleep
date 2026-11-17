package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.insights.Formats

/**
 * One line under the night's title: the tracker, the clock times of the local recording and
 * how long it ran. [text] holds the resources with positional placeholders. A recording without a
 * usable end has no end time and no length to show.
 */
fun formatNightHeader(
    tracker: String,
    startUtcMs: Long,
    endUtcMs: Long?,
    text: NightHeaderText,
    formats: Formats,
): String {
  val start = formats.clockTime(startUtcMs)
  val end = endUtcMs?.takeIf { startUtcMs > 0 && it > startUtcMs }
  return if (end == null) {
    String.format(formats.locale, text.startOnly, tracker, start)
  } else {
    String.format(
        formats.locale,
        text.withEnd,
        tracker,
        start,
        formats.clockTime(end),
        formats.duration(end - startUtcMs),
    )
  }
}
