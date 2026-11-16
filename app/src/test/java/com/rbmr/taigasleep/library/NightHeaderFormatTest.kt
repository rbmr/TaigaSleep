package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.testFormats
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NightHeaderFormatTest {
  private val text =
      NightHeaderText(
          withEnd = "%1\$s · %2\$s–%3\$s · %4\$s",
          startOnly = "%1\$s · %2\$s",
      )
  private val formats =
      testFormats(
          timeFormat = TimeFormat.TWENTY_FOUR_HOUR,
          locale = Locale.US,
          zone = java.time.ZoneId.of("UTC"),
      )

  @Test
  fun aNightThatCrossesMidnightShowsBothClockTimesAndTheLength() {
    val start = Instant.parse("2026-11-09T23:12:00Z").toEpochMilli()
    val end = Instant.parse("2026-11-10T07:05:00Z").toEpochMilli()

    val line =
        formatNightHeader(
            tracker = "Polar H10",
            startUtcMs = start,
            endUtcMs = end,
            text = text,
            formats = formats,
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
            text = text,
            formats = formats,
        )

    assertEquals("Polar H10 · 23:12", line)
  }

  @Test
  fun theHeaderWritesThePhoneChosenClock() {
    val start = Instant.parse("2026-11-09T23:12:00Z").toEpochMilli()
    val end = Instant.parse("2026-11-10T07:05:00Z").toEpochMilli()

    val line =
        formatNightHeader(
            tracker = "Polar H10",
            startUtcMs = start,
            endUtcMs = end,
            text = text,
            formats = testFormats(timeFormat = TimeFormat.TWELVE_HOUR),
        )

    assertEquals("Polar H10 · 11:12 PM–7:05 AM · 7h 53m", line)
  }
}
