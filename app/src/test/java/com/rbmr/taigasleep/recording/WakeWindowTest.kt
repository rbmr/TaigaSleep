package com.rbmr.taigasleep.recording

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A wake-up window is two local times of day; the night it belongs to is resolved when the recording
 * starts, so the same window can mean tonight or tomorrow morning.
 */
class WakeWindowTest {
  private val zone = ZoneId.of("Europe/Berlin")

  private fun at(text: String): Long = Instant.parse(text).toEpochMilli()

  @Test
  fun `a window later tonight is resolved to the coming morning`() {
    // 22:00 on 10 November in Berlin (UTC+1), window 06:30-07:00.
    val bounds = WakeWindow(6 * 60 + 30, 30).bounds(at("2026-11-10T21:00:00Z"), zone)

    assertEquals(at("2026-11-11T05:30:00Z"), bounds.startUtcMs)
    assertEquals(at("2026-11-11T06:00:00Z"), bounds.endUtcMs)
  }

  @Test
  fun `a window that is open now keeps its start in the past`() {
    // 06:45, inside 06:30-07:00: the window is the one already open.
    val bounds = WakeWindow(6 * 60 + 30, 30).bounds(at("2026-11-10T05:45:00Z"), zone)

    assertEquals(at("2026-11-10T05:30:00Z"), bounds.startUtcMs)
    assertEquals(at("2026-11-10T06:00:00Z"), bounds.endUtcMs)
  }

  @Test
  fun `a window that is over is the next one`() {
    // 08:00, after 06:30-07:00: the next window is tomorrow's.
    val bounds = WakeWindow(6 * 60 + 30, 30).bounds(at("2026-11-10T07:00:00Z"), zone)

    assertEquals(at("2026-11-11T05:30:00Z"), bounds.startUtcMs)
    assertEquals(at("2026-11-11T06:00:00Z"), bounds.endUtcMs)
  }

  @Test
  fun `a window that crosses midnight ends on the next day`() {
    val window = WakeWindow(23 * 60, 8 * 60)

    // 22:00: the window opens in an hour and ends at 07:00 tomorrow.
    val tonight = window.bounds(at("2026-11-10T21:00:00Z"), zone)
    assertEquals(at("2026-11-10T22:00:00Z"), tonight.startUtcMs)
    assertEquals(at("2026-11-11T06:00:00Z"), tonight.endUtcMs)

    // 02:00: the window opened last night and is the one running.
    val running = window.bounds(at("2026-11-11T01:00:00Z"), zone)
    assertEquals(at("2026-11-10T22:00:00Z"), running.startUtcMs)
    assertEquals(at("2026-11-11T06:00:00Z"), running.endUtcMs)
  }

  @Test
  fun `a window needs a time of day and a length`() {
    assertThrows(IllegalArgumentException::class.java) { WakeWindow(6 * 60, 0) }
    assertThrows(IllegalArgumentException::class.java) { WakeWindow(-1, 60) }
    assertThrows(IllegalArgumentException::class.java) { WakeWindow(24 * 60, 30) }
  }

  @Test
  fun `a window saved with an end time becomes a start and a length`() {
    val window = WakeWindow.between(6 * 60 + 30, 7 * 60)
    assertEquals(WakeWindow(6 * 60 + 30, 30), window)
    assertEquals(7 * 60, window!!.endMinute)
    assertEquals(WakeWindow(23 * 60, 8 * 60), WakeWindow.between(23 * 60, 7 * 60))
    assertNull(WakeWindow.between(6 * 60, 6 * 60))
  }
}
