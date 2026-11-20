package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.Stamp

/**
 * The live side of an armed wake-up window: it keeps the samples the decision reads, reduces the
 * accelerometer to one movement value per second, and asks [WakeDecision] about movement at most
 * once a second and about the heart-rate score once per step.
 *
 * Every heart-rate sample the recording produces is fed in. A sample enters the sleeping level's
 * per-beat counts once it is half an hour old and never leaves; only the samples after that stay, for
 * the delay and the windows of the score, so the memory is half an hour of one sample per second plus
 * 250 counters however long the night runs.
 *
 * Samples arrive on the sensor's threads, so every change is taken under one short lock and the
 * trigger is handed out after it. The watch is spent once it rang: a recording that is resumed
 * after the alarm never rings a second time for the same window. A window moved while recording
 * ([retarget]) is a new window, so the watch can ring for it again, from the samples it already has.
 */
class WakeWatch(
    private var window: WakeWindow,
    private var bounds: WakeBounds,
    private val model: HeartRateWakeModel = WakeDecision.heartRateModel,
    /** Called once, from the sample thread, with the moment the alarm is due. */
    private val onTrigger: (Long) -> Unit,
) {
  private val lock = Any()
  private val hr = ArrayDeque<WakePoint>()
  private val sleeping = HeartRateCounts()
  private val movement = ArrayDeque<WakePoint>()
  private var hrStepAtMs = bounds.startUtcMs
  private var hrSteps = 0
  private var highScores = 0
  private var movementEvaluatedAtMs: Long? = null
  private var secondStartMs = Long.MIN_VALUE
  private var secondSumMg = 0.0
  private var secondSamples = 0
  private var fired = false

  /** The heart-rate points kept, bounded by [HeartRateWakeModel.sleepingLevelGapMs]. */
  internal val keptHrPoints: Int
    get() = synchronized(lock) { hr.size }

  /** The heart-rate samples in the sleeping level's counts. */
  internal val countedHrSamples: Int
    get() = synchronized(lock) { sleeping.samples }

  /** The steps of the heart-rate score decided so far, at most one per step of the model. */
  internal val decidedHrSteps: Int
    get() = synchronized(lock) { hrSteps }

  fun onHr(stamp: Stamp, sample: HrSample) {
    val trigger =
        synchronized(lock) {
          val at = stamp.phoneNs / NS_PER_MS
          hr.addLast(WakePoint(at, sample.hr.toDouble()))
          // The samples that are old enough move into the counts of the whole night, which is what
          // the sleeping level is; the rest wait for the delay and the windows of the score.
          while (hr.isNotEmpty() && hr.first().atMs <= at - model.sleepingLevelGapMs) {
            sleeping.add(hr.removeFirst().value)
          }
          if (window.heartRate) heartRateTrigger(at) else null
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
          if (window.movement) movementTrigger(at) else null
        }
    if (trigger != null) onTrigger(trigger)
  }

  /** Watches [window] from now on, with the samples kept so far; it may ring again. */
  fun retarget(window: WakeWindow, bounds: WakeBounds) {
    synchronized(lock) {
      this.window = window
      this.bounds = bounds
      hrStepAtMs = bounds.startUtcMs
      highScores = 0
      movementEvaluatedAtMs = null
      fired = false
    }
  }

  /**
   * The heart-rate score of the step the samples reached, once per step. Steps swallowed by a gap
   * in the samples are skipped rather than scored in a burst afterwards, and they end the run:
   * two evaluations a step apart did not happen across a gap.
   */
  private fun heartRateTrigger(atMs: Long): Long? {
    if (fired || atMs > bounds.endUtcMs || atMs < hrStepAtMs) return null
    while (hrStepAtMs + model.stepMs <= atMs) {
      hrStepAtMs += model.stepMs
      highScores = 0
    }
    val at = hrStepAtMs
    hrStepAtMs = at + model.stepMs
    hrSteps++
    val features = WakeDecision.features(hr, at, sleeping, model)
    val high = features != null && WakeDecision.score(features, model) >= model.threshold
    highScores = if (high) highScores + 1 else 0
    val ring = highScores >= model.consecutiveEvaluations
    if (ring) fired = true
    return if (ring) at else null
  }

  /** The movement of the seconds that ended, once a second at most. */
  private fun movementTrigger(atMs: Long): Long? {
    val last = movementEvaluatedAtMs
    val due = atMs <= bounds.endUtcMs && (last == null || atMs - last >= EVALUATE_MS)
    if (fired || !due) return null
    movementEvaluatedAtMs = atMs
    val trigger = WakeDecision.movementTrigger(bounds, movement)
    if (trigger != null) fired = true
    return trigger
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

  private companion object {
    const val NS_PER_MS = 1_000_000L
    const val SECOND_MS = 1_000L
    const val EVALUATE_MS = 1_000L
  }
}
