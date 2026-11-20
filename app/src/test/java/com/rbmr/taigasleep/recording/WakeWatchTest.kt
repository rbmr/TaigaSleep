package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.Stamp
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live side of the window: the samples a recording produces, reduced and fed to the decision,
 * ring once and only inside the window, and the heart rate is kept to half an hour plus the night's
 * counts.
 */
class WakeWatchTest {
  private val start = Instant.parse("2026-11-10T06:30:00Z").toEpochMilli()
  private val end = start + 30 * MINUTE
  private val nightStart = start - 6 * HOUR
  private val bounds = WakeBounds(start, end)
  private val fired = CopyOnWriteArrayList<Long>()

  @Test
  fun `a restless heart rate inside the window rings once`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 30, heartRate = true))
    heartRate(watch, nightStart, start) { 50 }
    heartRate(watch, start, start + 10 * MINUTE) { restless(it) }

    assertEquals(listOf(start + 2 * MINUTE), fired)
  }

  @Test
  fun `restlessness that ended before the window does not ring`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 30, heartRate = true))
    heartRate(watch, nightStart, start - 20 * MINUTE) { 50 }
    heartRate(watch, start - 20 * MINUTE, start - 10 * MINUTE) { restless(it) }
    heartRate(watch, start - 10 * MINUTE, end) { 50 }

    assertTrue("nothing may ring: $fired", fired.isEmpty())
  }

  @Test
  fun `a window without a chosen signal never rings`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 30, heartRate = false, movement = false))
    heartRate(watch, nightStart, start) { 50 }
    heartRate(watch, start, start + 10 * MINUTE) { restless(it) }
    acc(watch, start, start + 5 * MINUTE, mg = 1_500)

    assertTrue("nothing may ring: $fired", fired.isEmpty())
  }

  @Test
  fun `movement that keeps up for a minute rings once`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 30, heartRate = false, movement = true))
    acc(watch, start, start + 2 * MINUTE, mg = 1_010)
    acc(watch, start + 2 * MINUTE, start + 5 * MINUTE, mg = 1_500)

    assertEquals(listOf(start + 3 * MINUTE), fired)
  }

  @Test
  fun `the watch keeps the last half hour of heart rate`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 30, heartRate = true))

    // One sample a minute for five hours: only the samples of the delay stay.
    heartRate(watch, start - 5 * HOUR, start + 1_000, stepMs = MINUTE) { 60 }

    assertEquals(30, watch.keptHrPoints)
  }

  @Test
  fun `every sample of the night enters the counts once it is old enough`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 30, heartRate = true))

    // One sample a minute for five hours: the 271 that are at least half an hour old are counted,
    // however early in the night they came.
    heartRate(watch, start - 5 * HOUR, start + 1_000, stepMs = MINUTE) { 60 }

    assertEquals(271, watch.countedHrSamples)
  }

  @Test
  fun `the score is decided once per step and not once per sample`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 30, heartRate = true))

    // Ten minutes of one sample a second are twenty steps of thirty seconds.
    heartRate(watch, start, start + 10 * MINUTE) { 60 }

    assertEquals(20, watch.decidedHrSteps)
  }

  private fun watch(window: WakeWindow) = WakeWatch(window, bounds) { fired += it }

  /** A heart rate that swings 22 beats around its mean from second to second. */
  private fun restless(atMs: Long): Int = if ((atMs / 1_000) % 2 == 0L) 28 else 72

  /** One heart rate sample every [stepMs] from [fromMs] up to [toMs]. */
  private fun heartRate(
      watch: WakeWatch,
      fromMs: Long,
      toMs: Long,
      stepMs: Long = 1_000,
      bpm: (Long) -> Int,
  ) {
    generateSequence(fromMs) { it + stepMs }.takeWhile { it < toMs }.forEach {
      watch.onHr(Stamp(it * NS_PER_MS, it * NS_PER_MS), sample(bpm(it)))
    }
  }

  /** Accelerometer batches at 50 Hz, the way the strap delivers them, from [fromMs] to [toMs]. */
  private fun acc(watch: WakeWatch, fromMs: Long, toMs: Long, mg: Int) {
    generateSequence(fromMs) { it + BATCH_MS }.takeWhile { it < toMs }.forEach { at ->
      watch.onAcc(
          Stamp(at * NS_PER_MS, at * NS_PER_MS),
          AccBatch(LongArray(SAMPLES) { it.toLong() }, IntArray(SAMPLES * 3) { if (it % 3 == 2) mg else 0 }),
      )
    }
  }

  private fun sample(bpm: Int) =
      HrSample(bpm, contactSupported = true, contact = true, rrAvailable = true, rrMs = intArrayOf(600))

  private companion object {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val NS_PER_MS = 1_000_000L
    const val SAMPLES = 36
    const val BATCH_MS = SAMPLES * 20L
  }
}
