package com.rbmr.taigasleep.library

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import com.rbmr.taigasleep.insights.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class NightHeaderFormatTest {
  private val zone = TimeZone.getTimeZone("UTC")
  private val locale = Locale.US
  private val lineFormat = "%1\$s · %2\$s–%3\$s · %4\$s"
  private val startOnlyFormat = "%1\$s · %2\$s"

  @Test
  fun aNightThatCrossesMidnightShowsBothClockTimesAndTheLength() {
    val start = Instant.parse("2026-11-09T23:12:00Z").toEpochMilli()
    val end = Instant.parse("2026-11-10T07:05:00Z").toEpochMilli()

    val line =
        formatNightHeader(
            tracker = "Polar H10",
            startUtcMs = start,
            endUtcMs = end,
            lineFormat = lineFormat,
            startOnlyFormat = startOnlyFormat,
            locale = locale,
            zone = zone,
        )

    assertEquals("Polar H10 · 23:12–07:05 · 7h 53m", line)
  }

  @Test
  fun aNightWithoutAnEndOmitsTheEndAndTheLength() {
    val start = Instant.parse("2026-11-09T23:12:00Z").toEpochMilli()

    val line =
        formatNightHeader(
            tracker = "Polar H10",
            startUtcMs = start,
            endUtcMs = null,
            lineFormat = lineFormat,
            startOnlyFormat = startOnlyFormat,
            locale = locale,
            zone = zone,
        )

    assertEquals("Polar H10 · 23:12", line)
  }

  @Test
  fun aLengthDropsTheEmptyPartAndKeepsTheSecondsOfAShortStage() {
    assertEquals("7h 52m", formatDuration(7 * 3_600_000L + 52 * 60_000L))
    assertEquals("2h", formatDuration(2 * 3_600_000L))
    assertEquals("45m", formatDuration(45 * 60_000L))
    assertEquals("30s", formatDuration(30_000L))
  }
}
