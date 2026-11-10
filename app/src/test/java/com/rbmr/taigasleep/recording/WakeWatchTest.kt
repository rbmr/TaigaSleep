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
 * The live side of the window: the samples a recording produces, reduced and fed to the decision
 * once a second, ring once and only inside the window.
 */
class WakeWatchTest {
  private val start = Instant.parse("2026-11-10T06:30:00Z").toEpochMilli()
  private val end = start + 30 * MINUTE
  private val bounds = WakeBounds(start, end)
  private val fired = CopyOnWriteArrayList<Long>()

  @Test
  fun `a heart rate rise inside the window rings once`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 7 * 60, heartRate = true))
    hr(watch, start - 30 * MINUTE, start + 5 * MINUTE - 1_000, 60)
    hr(watch, start + 5 * MINUTE, start + 10 * MINUTE, 72)

    assertEquals(listOf(start + 6 * MINUTE), fired)
  }

  @Test
  fun `a rise before the window is not a rise inside it`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 7 * 60, heartRate = true))
    hr(watch, start - 30 * MINUTE, start - 10 * MINUTE - 1_000, 60)
    hr(watch, start - 10 * MINUTE, start - 1_000, 80)
    hr(watch, start, end, 60)

    assertTrue("nothing may ring: $fired", fired.isEmpty())
  }

  @Test
  fun `movement that keeps up for a minute rings once`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 7 * 60, heartRate = false, movement = true))
    acc(watch, start, start + 2 * MINUTE, mg = 1_010)
    acc(watch, start + 2 * MINUTE, start + 5 * MINUTE, mg = 1_500)

    assertEquals(listOf(start + 3 * MINUTE), fired)
  }

  @Test
  fun `a window without a chosen signal never rings`() {
    val watch = watch(WakeWindow(6 * 60 + 30, 7 * 60, heartRate = false, movement = false))
    hr(watch, start - 30 * MINUTE, start + 5 * MINUTE - 1_000, 60)
    hr(watch, start + 5 * MINUTE, start + 10 * MINUTE, 90)
    acc(watch, start, start + 5 * MINUTE, mg = 1_500)

    assertTrue("nothing may ring: $fired", fired.isEmpty())
  }

  private fun watch(window: WakeWindow) = WakeWatch(window, bounds) { fired += it }

  /** One heart rate sample a second from [fromMs] up to [toMs]. */
  private fun hr(watch: WakeWatch, fromMs: Long, toMs: Long, bpm: Int) {
    generateSequence(fromMs) { it + 1_000 }.takeWhile { it <= toMs }.forEach {
      watch.onHr(Stamp(it * NS_PER_MS, it * NS_PER_MS), sample(bpm))
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
    const val NS_PER_MS = 1_000_000L
    const val SAMPLES = 36
    const val BATCH_MS = SAMPLES * 20L
  }
}
