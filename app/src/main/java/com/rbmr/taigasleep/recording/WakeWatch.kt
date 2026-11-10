package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.Stamp

/**
 * The live side of an armed wake-up window: it keeps the samples the decision reads, reduces the
 * accelerometer to one movement value per second, and asks [WakeDecision] at most once a second.
 *
 * Samples arrive on the sensor's threads, so every change is taken under one short lock and the
 * trigger is handed out after it. The watch is spent once it rang: a recording that is resumed
 * after the alarm never rings a second time for the same window.
 */
class WakeWatch(
    private val window: WakeWindow,
    private val bounds: WakeBounds,
    /** Called once, from the sample thread, with the moment the alarm is due. */
    private val onTrigger: (Long) -> Unit,
) {
  private val lock = Any()
  private val hr = ArrayDeque<WakePoint>()
  private val movement = ArrayDeque<WakePoint>()
  private var evaluatedAtMs: Long? = null
  private var secondStartMs = Long.MIN_VALUE
  private var secondSumMg = 0.0
  private var secondSamples = 0
  private var fired = false

  fun onHr(stamp: Stamp, sample: HrSample) {
    val trigger =
        synchronized(lock) {
          val at = stamp.phoneNs / NS_PER_MS
          // The baseline reaches back before the window opens, so the run that wakes the user is
          // compared with the night that led to it.
          if (at < bounds.startUtcMs - WakeDecision.BASELINE_MS) return
          hr.addLast(WakePoint(at, sample.hr.toDouble()))
          dropBefore(hr, at - WakeDecision.BASELINE_MS - WakeDecision.SUSTAIN_MS)
          evaluate(at)
        }
    if (trigger != null) onTrigger(trigger)
  }

  fun onAcc(stamp: Stamp, batch: AccBatch) {
    if (batch.timestampsNs.isEmpty()) return
    val trigger =
        synchronized(lock) {
          val at = stamp.phoneNs / NS_PER_MS
          if (at < bounds.startUtcMs) return
          val second = at - at % SECOND_MS
          if (second != secondStartMs) {
            // The second that just ended is complete; the decision reads whole seconds only.
            closeSecond()
            secondStartMs = second
          }
          secondSumMg += WakeMovement.deviation(batch.xyzMg) * batch.timestampsNs.size
          secondSamples += batch.timestampsNs.size
          evaluate(at)
        }
    if (trigger != null) onTrigger(trigger)
  }

  /** Adds the second that ended to the series the decision reads, and forgets what is too old. */
  private fun closeSecond() {
    if (secondSamples == 0) return
    val at = secondStartMs
    movement.addLast(WakePoint(at, secondSumMg / secondSamples))
    dropBefore(movement, at - 2 * WakeDecision.SUSTAIN_MS)
    secondSumMg = 0.0
    secondSamples = 0
  }

  private fun dropBefore(points: ArrayDeque<WakePoint>, atMs: Long) {
    while (points.isNotEmpty() && points.first().atMs < atMs) points.removeFirst()
  }

  /** The trigger when it is due now; the lock is held, so the decision sees one consistent series. */
  private fun evaluate(atMs: Long): Long? {
    if (fired || atMs > bounds.endUtcMs) return null
    val last = evaluatedAtMs
    if (last != null && atMs - last < EVALUATE_MS) return null
    evaluatedAtMs = atMs
    val series = WakeSeries(hr.toList(), movement.toList())
    val trigger = WakeDecision.trigger(window, bounds, series) ?: return null
    fired = true
    return trigger
  }

  private companion object {
    const val NS_PER_MS = 1_000_000L
    const val SECOND_MS = 1_000L
    const val EVALUATE_MS = 1_000L
  }
}
