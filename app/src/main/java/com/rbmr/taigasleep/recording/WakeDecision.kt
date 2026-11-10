package com.rbmr.taigasleep.recording

import kotlin.math.abs
import kotlin.math.sqrt

/** One value of the night: heart rate in bpm or movement in milli-g, at wall-clock milliseconds. */
data class WakePoint(val atMs: Long, val value: Double)

/**
 * The samples the decision looks at: heart rate over the baseline period before the moment being
 * decided, and movement over the last minute. Both are bounded by the caller, so a whole night is
 * never held in memory.
 */
data class WakeSeries(
    val hr: List<WakePoint> = emptyList(),
    val movement: List<WakePoint> = emptyList(),
)

/**
 * The wake-up decision, as a pure function of a window and the samples inside it.
 *
 * Inside the window the alarm fires at the first moment one of the chosen signals has held for
 * [SUSTAIN_MS]: movement above [MOVEMENT_MG], or heart rate above the median of the previous
 * [BASELINE_MS] by more than [HR_RISE]. Without such a moment the alarm fires at the window end,
 * which [firesAt] answers directly.
 *
 * This decides when to ring. It says nothing about sleep stages: a rise in heart rate and a burst
 * of movement are signs of a light moment, not proof of one.
 */
object WakeDecision {
  /** Mean distance of the acceleration from 1 g, in milli-g, that counts as movement. */
  const val MOVEMENT_MG = 80.0

  /** The rise above the median heart rate of the previous 30 minutes that counts as waking up. */
  const val HR_RISE = 0.10

  /** How long a signal has to hold before it wakes the user. */
  const val SUSTAIN_MS = 60_000L

  /** How far back the heart rate baseline reaches. */
  const val BASELINE_MS = 30 * 60_000L

  /** The first moment inside [bounds] a chosen signal has held for [SUSTAIN_MS], or null. */
  fun trigger(window: WakeWindow, bounds: WakeBounds, series: WakeSeries): Long? =
      listOfNotNull(
              if (window.heartRate) heartRateTrigger(bounds, series.hr) else null,
              if (window.movement) movementTrigger(bounds, series.movement) else null,
          )
          .minOrNull()

  /** When the alarm rings: the first trigger inside the window, or the end of the window. */
  fun firesAt(window: WakeWindow, bounds: WakeBounds, series: WakeSeries): Long =
      trigger(window, bounds, series) ?: bounds.endUtcMs

  /**
   * A run of heart rate above its own baseline. The baseline of a run is the median of the samples
   * in the [BASELINE_MS] before the run began, so a night that drifts slowly never counts as a rise.
   */
  private fun heartRateTrigger(bounds: WakeBounds, hr: List<WakePoint>): Long? {
    var runStart: Long? = null
    var threshold = 0.0
    for (point in hr) {
      if (point.atMs < bounds.startUtcMs) continue
      if (point.atMs > bounds.endUtcMs) return null
      val start = runStart
      if (start != null && point.value > threshold) {
        if (point.atMs - start >= SUSTAIN_MS) return start + SUSTAIN_MS
        continue
      }
      val baseline = median(hr, point.atMs)
      if (baseline != null && point.value > baseline * (1 + HR_RISE)) {
        runStart = point.atMs
        threshold = baseline * (1 + HR_RISE)
      } else {
        runStart = null
      }
    }
    return null
  }

  /** A run of movement samples above the threshold, one sample per second. */
  private fun movementTrigger(bounds: WakeBounds, movement: List<WakePoint>): Long? {
    var runStart: Long? = null
    for (point in movement) {
      if (point.atMs < bounds.startUtcMs) continue
      if (point.atMs > bounds.endUtcMs) return null
      if (point.value <= MOVEMENT_MG) {
        runStart = null
        continue
      }
      val start = runStart ?: point.atMs.also { runStart = it }
      if (point.atMs - start >= SUSTAIN_MS) return start + SUSTAIN_MS
    }
    return null
  }

  /** The median heart rate in the [BASELINE_MS] before [atMs]; null without a sample there. */
  private fun median(hr: List<WakePoint>, atMs: Long): Double? {
    val values =
        hr.filter { it.atMs >= atMs - BASELINE_MS && it.atMs < atMs }.map { it.value }.sorted()
    if (values.isEmpty()) return null
    val middle = values.size / 2
    return if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2 else values[middle]
  }
}

/** Movement as the decision reads it: how far the acceleration of one ACC batch is from 1 g. */
object WakeMovement {
  /** The acceleration at rest, in milli-g, which a movement sample is measured against. */
  const val GRAVITY_MG = 1_000.0

  /** The mean distance of the samples of one ACC batch from 1 g, in milli-g. */
  fun deviation(xyzMg: IntArray): Double {
    var sum = 0.0
    var samples = 0
    var i = 0
    while (i + 2 < xyzMg.size) {
      val x = xyzMg[i].toDouble()
      val y = xyzMg[i + 1].toDouble()
      val z = xyzMg[i + 2].toDouble()
      sum += abs(sqrt(x * x + y * y + z * z) - GRAVITY_MG)
      samples++
      i += 3
    }
    return if (samples == 0) 0.0 else sum / samples
  }
}
