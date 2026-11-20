package com.rbmr.taigasleep.recording

import java.time.Instant
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-up decision on synthetic series: a restless heart rate or a stretch of movement inside
 * the window rings, anything else waits for the end of the window.
 */
class WakeDecisionTest {
  private val start = Instant.parse("2026-11-10T06:30:00Z").toEpochMilli()
  private val end = start + 30 * MINUTE
  private val nightStart = start - 6 * HOUR
  private val bounds = WakeBounds(start, end)
  private val hrOnly = WakeWindow(startMinute = 6 * 60 + 30, lengthMinutes = 30, heartRate = true)
  private val movementOnly =
      WakeWindow(startMinute = 6 * 60 + 30, lengthMinutes = 30, heartRate = false, movement = true)
  private val both = WakeWindow(startMinute = 6 * 60 + 30, lengthMinutes = 30, true, true)

  @Test
  fun `a smooth sleeping heart rate never rings`() {
    val hr = hrSeries(nightStart, end + 10 * MINUTE, 50.0)

    assertNull(WakeDecision.trigger(hrOnly, bounds, series(hr = hr)))
    assertEquals(end, WakeDecision.firesAt(hrOnly, bounds, series(hr = hr)))
  }

  @Test
  fun `a restless heart rate at the sleeping level rings in the window`() {
    val hr = hrSeries(nightStart, start, 50.0) + hrSeries(start, end, ::restless)

    assertEquals(start + 2 * MINUTE, WakeDecision.trigger(hrOnly, bounds, series(hr = hr)))
  }

  @Test
  fun `a raised steady level below the threshold never rings`() {
    // 120 is 2.4 times the sleeping level; with the model's weights that scores below 0.80.
    val hr =
        hrSeries(nightStart, start - 15 * MINUTE, 50.0) +
            hrSeries(start - 15 * MINUTE, start - 5 * MINUTE) {
              50.0 + 70.0 * (it - (start - 15 * MINUTE)) / (10 * MINUTE).toDouble()
            } +
            hrSeries(start - 5 * MINUTE, end, 120.0)

    assertNull(WakeDecision.trigger(hrOnly, bounds, series(hr = hr)))
  }

  @Test
  fun `a raised steady level above the threshold rings`() {
    // 130 is 2.6 times the sleeping level, enough to score above 0.80 on two evaluations in a row.
    val hr =
        hrSeries(nightStart, start - 15 * MINUTE, 50.0) +
            hrSeries(start - 15 * MINUTE, start - 5 * MINUTE) {
              50.0 + 80.0 * (it - (start - 15 * MINUTE)) / (10 * MINUTE).toDouble()
            } +
            hrSeries(start - 5 * MINUTE, end, 130.0)

    assertEquals(start + 30 * SECOND, WakeDecision.trigger(hrOnly, bounds, series(hr = hr)))
  }

  @Test
  fun `restlessness that ended before the window does not ring`() {
    val hr =
        hrSeries(nightStart, start - 20 * MINUTE, 50.0) +
            hrSeries(start - 20 * MINUTE, start - 10 * MINUTE, ::restless) +
            hrSeries(start - 10 * MINUTE, end + 10 * MINUTE, 50.0)

    assertNull(WakeDecision.trigger(hrOnly, bounds, series(hr = hr)))
    assertEquals(end, WakeDecision.firesAt(hrOnly, bounds, series(hr = hr)))
  }

  @Test
  fun `restlessness that is there when the window opens rings inside it only`() {
    val hr =
        hrSeries(nightStart, start - 10 * MINUTE, 50.0) +
            hrSeries(start - 10 * MINUTE, end, ::restless)

    val trigger = WakeDecision.trigger(hrOnly, bounds, series(hr = hr))

    assertEquals(start + 30 * SECOND, trigger)
    assertTrue("the alarm must not ring before the window: $trigger", trigger!! >= start)
  }

  @Test
  fun `restlessness that starts after the window never rings`() {
    val hr = hrSeries(nightStart, end, 50.0) + hrSeries(end, end + 10 * MINUTE, ::restless)

    assertNull(WakeDecision.trigger(hrOnly, bounds, series(hr = hr)))
  }

  @Test
  fun `too little heart rate gives no trigger`() {
    val short = WakeBounds(start, start + 10 * MINUTE)
    val window = WakeWindow(startMinute = 6 * 60 + 30, lengthMinutes = 10, heartRate = true)
    val hr = hrSeries(start - 5 * MINUTE, start, 50.0) + hrSeries(start, short.endUtcMs, ::restless)

    assertNull(WakeDecision.trigger(window, short, series(hr = hr, atMs = short.endUtcMs)))
    assertEquals(
        short.endUtcMs,
        WakeDecision.firesAt(window, short, series(hr = hr, atMs = short.endUtcMs)),
    )
  }

  @Test
  fun `the features are the level and spreads relative to the sleeping level`() {
    val at = start + 30 * MINUTE
    // 900 samples at 45 and 8 100 at 55 put the tenth percentile at 45.
    val sleeping =
        HeartRateCounts().apply {
          repeat(900) { add(45.0) }
          repeat(8_100) { add(55.0) }
        }
    // Two quiet minutes then three swinging ones: level 45, spread2 5, spread5 sqrt(15).
    val close =
        hrSeries(at - 5 * MINUTE, at - 3 * MINUTE, 45.0) +
            hrSeries(at - 3 * MINUTE, at, ::swinging)

    val features = WakeDecision.features(close, at, sleeping)!!

    assertEquals(1.0, features.level, 1e-9)
    assertEquals(5.0 / 45.0, features.spread2, 1e-9)
    assertEquals(sqrt(15.0) / 45.0, features.spread5, 1e-9)
  }

  @Test
  fun `the score of fixed features is the logistic of their weighted sum`() {
    // level 1.2, spread2 0.2 and spread5 0.1 give 1.86 * 1.2 + 4.58 * 0.2 + 6.54 * 0.1 - 3.36.
    assertEquals(0.608735, WakeDecision.score(HeartRateWakeFeatures(1.2, 0.2, 0.1)), 1e-6)
  }

  @Test
  fun `movement sustained for a minute rings a minute after it starts`() {
    val movement =
        movementSeries(start, start + 5 * MINUTE - 1_000, 20.0) +
            movementSeries(start + 5 * MINUTE, start + 10 * MINUTE, 300.0)

    assertEquals(
        start + 6 * MINUTE,
        WakeDecision.trigger(movementOnly, bounds, series(movement = movement)),
    )
  }

  @Test
  fun `movement below the threshold never rings`() {
    val movement = movementSeries(start, end, WakeDecision.MOVEMENT_MG - 1)

    assertNull(WakeDecision.trigger(movementOnly, bounds, series(movement = movement)))
  }

  @Test
  fun `a signal the user did not choose never rings`() {
    val hr = hrSeries(nightStart, start, 50.0) + hrSeries(start, end, ::restless)

    assertNull(WakeDecision.trigger(movementOnly, bounds, series(hr = hr)))
    assertNull(
        WakeDecision.trigger(
            WakeWindow(6 * 60 + 30, 30, false, false),
            bounds,
            series(hr = hr),
        )
    )
  }

  @Test
  fun `the earlier of two chosen signals rings first`() {
    val hr =
        hrSeries(nightStart, start + 5 * MINUTE, 50.0) +
            hrSeries(start + 5 * MINUTE, end, ::restless)
    val movement =
        movementSeries(start, start + 2 * MINUTE - 1_000, 10.0) +
            movementSeries(start + 2 * MINUTE, start + 5 * MINUTE, 300.0)

    assertEquals(
        start + 3 * MINUTE,
        WakeDecision.trigger(both, bounds, series(hr = hr, movement = movement)),
    )
  }

  @Test
  fun `the end of the window is the fallback when nothing triggers`() {
    val quiet =
        series(hr = hrSeries(start, end, 55.0), movement = movementSeries(start, end, 5.0))

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

  /** A heart rate that swings 22 beats around its mean from second to second. */
  private fun restless(atMs: Long): Double = if ((atMs / 1_000) % 2 == 0L) 28.0 else 72.0

  /** A heart rate that swings 5 beats around its mean from second to second. */
  private fun swinging(atMs: Long): Double = if ((atMs / 1_000) % 2 == 0L) 40.0 else 50.0

  /**
   * The series the decision reads, with the sleeping level the night built up half an hour before
   * [atMs]: the counts hold every sample of [hr] older than that.
   */
  private fun series(
      hr: List<WakePoint> = emptyList(),
      movement: List<WakePoint> = emptyList(),
      atMs: Long = end,
  ): WakeSeries =
      WakeSeries(
          hr = hr,
          movement = movement,
          sleeping =
              HeartRateCounts().apply {
                hr.filter { it.atMs < atMs - 30 * MINUTE }.forEach { add(it.value) }
              },
      )

  private fun hrSeries(fromMs: Long, toMs: Long, bpm: Double): List<WakePoint> =
      hrSeries(fromMs, toMs) { bpm }

  /** One heart rate sample a second from [fromMs] up to [toMs]. */
  private fun hrSeries(fromMs: Long, toMs: Long, bpm: (Long) -> Double): List<WakePoint> =
      generateSequence(fromMs) { it + 1_000 }
          .takeWhile { it < toMs }
          .map { WakePoint(it, bpm(it)) }
          .toList()

  private fun movementSeries(fromMs: Long, toMs: Long, mg: Double): List<WakePoint> =
      generateSequence(fromMs) { it + 1_000 }
          .takeWhile { it <= toMs }
          .map { WakePoint(it, mg) }
          .toList()

  private fun resting(samples: Int): IntArray = IntArray(samples * 3) { if (it % 3 == 2) 1_000 else 0 }

  private fun moving(samples: Int, mg: Int): IntArray = IntArray(samples * 3) { if (it % 3 == 2) mg else 0 }

  private companion object {
    const val SECOND = 1_000L
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
  }
}
