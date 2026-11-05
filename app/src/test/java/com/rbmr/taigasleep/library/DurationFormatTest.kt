package com.rbmr.taigasleep.library

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
  fun noDurationAtAllIsShownAsADash() {
    assertEquals("—", formatDuration(0))
    assertEquals("—", formatDuration(-1))
  }

  @Test
  fun aDurationOfAMinuteOrMoreIsShownInMinutesAndHours() {
    assertEquals("1m", formatDuration(60_000))
    assertEquals("1m", formatDuration(61_000))
    assertEquals("1h", formatDuration(3_600_000))
    assertEquals("1h 5m", formatDuration(3_900_000))
  }
}
