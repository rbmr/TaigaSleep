package com.rbmr.taigasleep.library

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingDateTest {
  private val zone = ZoneId.of("Europe/Amsterdam")

  private fun ms(month: Int, day: Int, hour: Int, minute: Int): Long =
      LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

  @Test
  fun `a recording started in the evening is dated by that day`() {
    Locale.setDefault(Locale.ENGLISH)
    assertEquals("Sat 7 Nov", formatRecordingDate(ms(11, 7, 23, 50), zone))
  }

  @Test
  fun `a recording started after midnight belongs to the evening before`() {
    Locale.setDefault(Locale.ENGLISH)
    assertEquals("Fri 13 Nov", formatRecordingDate(ms(11, 14, 0, 33), zone))
    assertEquals("Fri 6 Nov", formatRecordingDate(ms(11, 7, 2, 5), zone))
  }

  @Test
  fun `no start time has no date`() {
    assertEquals("-", formatRecordingDate(0, zone))
  }
}
