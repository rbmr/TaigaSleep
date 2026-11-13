package com.rbmr.taigasleep.insights

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
  @Test
  fun aDurationBelowOneMinuteIsShownInSeconds() {
    assertEquals("1s", formatDuration(1_000))
    assertEquals("56s", formatDuration(56_000))
    assertEquals("59s", formatDuration(59_000))
  }

  @Test
  fun noDurationAtAllIsZeroSeconds() {
    assertEquals("0s", formatDuration(0))
    assertEquals("0s", formatDuration(-1))
  }

  @Test
  fun aDurationOfAMinuteOrMoreIsShownInMinutesAndHours() {
    assertEquals("1m", formatDuration(60_000))
    assertEquals("1m", formatDuration(61_000))
    assertEquals("1h", formatDuration(3_600_000))
    assertEquals("1h 5m", formatDuration(3_900_000))
    assertEquals("7h 26m", formatDuration(26_760_000))
    assertEquals("8h 30m", formatDuration(30_600_000))
    assertEquals("45m", formatDuration(2_700_000))
  }
}
