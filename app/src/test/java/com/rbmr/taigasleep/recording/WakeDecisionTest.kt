package com.rbmr.taigasleep.recording

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-up decision on synthetic series: a rise of heart rate or a stretch of movement inside the
 * window rings a minute after it starts, anything else waits for the end of the window.
 */
class WakeDecisionTest {
  private val start = Instant.parse("2026-11-10T06:30:00Z").toEpochMilli()
  private val end = start + 30 * MINUTE
  private val bounds = WakeBounds(start, end)
  private val hrOnly = WakeWindow(startMinute = 6 * 60 + 30, endMinute = 7 * 60, heartRate = true)
  private val movementOnly =
      WakeWindow(startMinute = 6 * 60 + 30, endMinute = 7 * 60, heartRate = false, movement = true)
  private val both = WakeWindow(startMinute = 6 * 60 + 30, endMinute = 7 * 60, true, true)

  @Test
  fun `a heart rate rise sustained for a minute rings a minute after it starts`() {
    val hr = flat(start, start + 10 * MINUTE - 1_000, 60.0) + flat(start + 10 * MINUTE, start + 15 * MINUTE, 70.0)

    assertEquals(start + 11 * MINUTE, WakeDecision.trigger(hrOnly, bounds, WakeSeries(hr = hr)))
  }

  @Test
  fun `a rise below the threshold never rings`() {
    val hr = flat(start, start + 10 * MINUTE - 1_000, 60.0) + flat(start + 10 * MINUTE, start + 15 * MINUTE, 63.0)

    assertNull(WakeDecision.trigger(hrOnly, bounds, WakeSeries(hr = hr)))
    assertEquals(end, WakeDecision.firesAt(hrOnly, bounds, WakeSeries(hr = hr)))
  }

  @Test
  fun `movement sustained for a minute rings a minute after it starts`() {
    val movement =
        flat(start, start + 5 * MINUTE - 1_000, 20.0) + flat(start + 5 * MINUTE, start + 10 * MINUTE, 300.0)

    assertEquals(
        start + 6 * MINUTE,
        WakeDecision.trigger(movementOnly, bounds, WakeSeries(movement = movement)),
    )
  }

  @Test
  fun `movement below the threshold never rings`() {
    val movement = flat(start, end, WakeDecision.MOVEMENT_MG - 1)

    assertNull(WakeDecision.trigger(movementOnly, bounds, WakeSeries(movement = movement)))
  }

  @Test
  fun `a signal the user did not choose never rings`() {
    val hr = flat(start, start + 10 * MINUTE - 1_000, 60.0) + flat(start + 10 * MINUTE, start + 15 * MINUTE, 90.0)

    assertNull(WakeDecision.trigger(movementOnly, bounds, WakeSeries(hr = hr)))
    assertNull(WakeDecision.trigger(WakeWindow(6 * 60 + 30, 7 * 60, false, false), bounds, WakeSeries(hr = hr)))
  }

  @Test
  fun `the earlier of two chosen signals rings first`() {
    val hr = flat(start, start + 10 * MINUTE - 1_000, 60.0) + flat(start + 10 * MINUTE, start + 15 * MINUTE, 90.0)
    val movement =
        flat(start, start + 2 * MINUTE - 1_000, 10.0) + flat(start + 2 * MINUTE, start + 5 * MINUTE, 300.0)

    assertEquals(
        start + 3 * MINUTE,
        WakeDecision.trigger(both, bounds, WakeSeries(hr = hr, movement = movement)),
    )
  }

  @Test
  fun `a rise that ended before the window does not ring`() {
    val hr =
        flat(start - 30 * MINUTE, start - 11 * MINUTE, 60.0) +
            flat(start - 10 * MINUTE, start - 5 * MINUTE, 80.0) +
            flat(start - 5 * MINUTE, start - 1_000, 60.0) +
            flat(start, end, 60.0)

    assertNull(WakeDecision.trigger(hrOnly, bounds, WakeSeries(hr = hr)))
    assertEquals(end, WakeDecision.firesAt(hrOnly, bounds, WakeSeries(hr = hr)))
  }

  @Test
  fun `a rise that is still there when the window opens rings inside it only`() {
    val hr = flat(start - 30 * MINUTE, start - 10 * MINUTE - 1_000, 60.0) + flat(start - 10 * MINUTE, end, 80.0)

    val trigger = WakeDecision.trigger(hrOnly, bounds, WakeSeries(hr = hr))

    assertEquals(start + MINUTE, trigger)
    assertTrue("the alarm must not ring before the window: $trigger", trigger!! >= start)
  }

  @Test
  fun `a dip in the middle starts the run again`() {
    val hr =
        flat(start - 30 * MINUTE, start + MINUTE - 1_000, 60.0) +
            flat(start + MINUTE, start + 5 * MINUTE, 70.0).map {
              if (it.atMs == start + MINUTE + 30_000) it.copy(value = 60.0) else it
            }

    assertEquals(start + 2 * MINUTE + 31_000, WakeDecision.trigger(hrOnly, bounds, WakeSeries(hr = hr)))
  }

  @Test
  fun `a night that drifts by a few beats never rings`() {
    val hr = flat(start, end) { 50.0 + it / (6 * MINUTE.toDouble()) }

    assertNull(WakeDecision.trigger(hrOnly, bounds, WakeSeries(hr = hr)))
  }

  @Test
  fun `the end of the window is the fallback when nothing triggers`() {
    val quiet = WakeSeries(hr = flat(start, end, 55.0), movement = flat(start, end, 5.0))

    assertEquals(end, WakeDecision.firesAt(both, bounds, quiet))
  }

  @Test
  fun `movement is how far the acceleration is from one g`() {
    assertEquals(0.0, WakeMovement.deviation(resting(36)), 0.001)
    assertEquals(400.0, WakeMovement.deviation(moving(1, 1400)), 0.001)
    assertEquals(0.0, WakeMovement.deviation(IntArray(0)), 0.001)

    val half = resting(18) + moving(18, 1400)
    assertEquals(200.0, WakeMovement.deviation(half), 0.001)
  }

  private fun flat(fromMs: Long, toMs: Long, mg: Double): List<WakePoint> = flat(fromMs, toMs) { mg }

  private fun flat(fromMs: Long, toMs: Long, value: (Long) -> Double): List<WakePoint> =
      generateSequence(fromMs) { it + 1_000 }
          .takeWhile { it <= toMs }
          .map { WakePoint(it, value(it - fromMs)) }
          .toList()

  private fun resting(samples: Int): IntArray = IntArray(samples * 3) { if (it % 3 == 2) 1_000 else 0 }

  private fun moving(samples: Int, mg: Int): IntArray = IntArray(samples * 3) { if (it % 3 == 2) mg else 0 }

  private companion object {
    const val MINUTE = 60_000L
  }
}
