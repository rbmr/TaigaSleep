package com.rbmr.taigasleep.recording

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MINUTE_MS = 60_000L

/** One value of the night: heart rate in bpm or movement in milli-g, at wall-clock milliseconds. */
data class WakePoint(val atMs: Long, val value: Double)

/**
 * The whole night of heart rate as one counter per beat, for the sleeping level.
 *
 * Heart rate arrives as whole bpm, so a percentile of the night needs one counter per value rather
 * than the night's samples. A sample enters its counter once it is
 * [HeartRateWakeModel.sleepingLevelGapMinutes] old and never leaves, so the sleeping level is the
 * whole night that led to the moment being decided however long it ran.
 */
class HeartRateCounts {
  private val counts = IntArray(BPM_VALUES)
  private var total = 0

  /** How many samples have entered the counts. */
  val samples: Int
    get() = total

  /** Adds [bpm] to the counter of its nearest whole beat, values outside the range at its ends. */
  fun add(bpm: Double) {
    counts[bpm.roundToInt().coerceIn(0, BPM_VALUES - 1)]++
    total++
  }

  /** The value at rank ceil([percentile] of [samples]) of the ascending values, or null if empty. */
  fun percentile(percentile: Int): Double? {
    val wanted = ((percentile.toLong() * total + 99) / 100).coerceAtLeast(1)
    var seen = 0L
    for (bpm in counts.indices) {
      seen += counts[bpm]
      if (seen >= wanted) return bpm.toDouble()
    }
    return null
  }

  private companion object {
    /** Heart rates from 0 to 249 bpm, one counter per value. */
    const val BPM_VALUES = 250
  }
}

/**
 * The samples the decision looks at: the heart rate of the last
 * [HeartRateWakeModel.sleepingLevelGapMinutes] for the windows of the score, the [HeartRateCounts]
 * of the whole night behind the sleeping level, and movement over the last minute. All are bounded
 * by the caller, so a whole night of samples is never held in memory.
 */
data class WakeSeries(
    val hr: List<WakePoint> = emptyList(),
    val movement: List<WakePoint> = emptyList(),
    val sleeping: HeartRateCounts = HeartRateCounts(),
)

/**
 * The heart-rate features of one moment, each relative to the sleeping level of the night: how high
 * the mean heart rate of the last [HeartRateWakeModel.levelWindowMinutes] is, and how widely it
 * swung over that window and the last [HeartRateWakeModel.spreadWindowMinutes].
 */
data class HeartRateWakeFeatures(val level: Double, val spread2: Double, val spread5: Double)

/**
 * The parameters of the heart-rate wake score, in one place so a later version can fit them per
 * person from the analysis results.
 *
 * The score is a logistic of `levelWeight * level + spread2Weight * spread2 + spread5Weight *
 * spread5 + bias`: a heart rate that is high or restless compared with the sleeping level scores
 * high, and a smooth one at the sleeping level scores low. The alarm rings when the score holds at
 * or above [threshold] for [consecutiveEvaluations] evaluations in a row, [stepSeconds] apart.
 *
 * The sleeping level is the [sleepingPercentile]th percentile of every heart-rate sample from the
 * start of the recording up to [sleepingLevelGapMinutes] before the moment being decided, held as
 * [HeartRateCounts], so the night that led to that moment is what the features are compared with.
 */
@Suppress("LongParameterList")
data class HeartRateWakeModel(
    val levelWeight: Double = 1.86,
    val spread2Weight: Double = 4.58,
    val spread5Weight: Double = 6.54,
    val bias: Double = -3.36,
    val threshold: Double = 0.80,
    /** How long a sample waits before it enters the sleeping level, in minutes. */
    val sleepingLevelGapMinutes: Int = 30,
    /** How long the windows of [HeartRateWakeFeatures.level] and its two-minute spread are. */
    val levelWindowMinutes: Int = 2,
    /** How long the window of [HeartRateWakeFeatures.spread5] is. */
    val spreadWindowMinutes: Int = 5,
    /** The percentile of the night's heart rate that is the sleeping level. */
    val sleepingPercentile: Int = 10,
    /** How far apart two evaluations of the score are, in seconds. */
    val stepSeconds: Int = 30,
    /** How many evaluations in a row at or above [threshold] make the alarm ring. */
    val consecutiveEvaluations: Int = 2,
    /** The fewest samples the counts hold before the sleeping level is used. */
    val minimumSleepingSamples: Int = 600,
    /** The fewest samples the level and its two-minute spread are computed from. */
    val minimumLevelSamples: Int = 60,
    /** The fewest samples the five-minute spread is computed from. */
    val minimumSpreadSamples: Int = 150,
) {
  init {
    require(sleepingLevelGapMinutes > spreadWindowMinutes) {
      "the sleeping level has to stay clear of the windows it is compared with"
    }
    require(stepSeconds > 0 && consecutiveEvaluations > 0) {
      "the score needs a step between evaluations and a run of them to ring"
    }
  }

  val sleepingLevelGapMs: Long = sleepingLevelGapMinutes * MINUTE_MS
  val levelWindowMs: Long = levelWindowMinutes * MINUTE_MS
  val spreadWindowMs: Long = spreadWindowMinutes * MINUTE_MS
  val stepMs: Long = stepSeconds * 1_000L
}

/**
 * The wake-up decision, as a pure function of a window and the samples inside it.
 *
 * Inside the window the alarm fires at the first moment one of the chosen signals says the user is
 * waking: movement above [MOVEMENT_MG] for [SUSTAIN_MS], or a heart-rate wake score at or above its
 * threshold on [HeartRateWakeModel.consecutiveEvaluations] evaluations in a row, a
 * [HeartRateWakeModel.stepSeconds] apart. Without such a moment the alarm fires at the window end,
 * which [firesAt] answers directly.
 *
 * The score is a logistic function of the [HeartRateWakeFeatures] of the moment: how high the mean
 * heart rate of the last minutes is and how widely it swung, each compared with the sleeping level
 * of the night. An awake heart rate swings from minute to minute where a sleeping one stays smooth,
 * so someone who is already awake when the window opens still rings.
 *
 * This decides when to ring. It says nothing about sleep stages: a restless heart rate and a burst
 * of movement are signs of a light moment, not proof of one.
 */
object WakeDecision {
  /** Mean distance of the acceleration from 1 g, in milli-g, that counts as movement. */
  const val MOVEMENT_MG = 80.0

  /** How long movement above the threshold has to hold before it wakes the user. */
  const val SUSTAIN_MS = 60_000L

  /** The parameters of the heart-rate score; a later version may fit them per person. */
  val heartRateModel = HeartRateWakeModel()

  /** The first moment inside [bounds] a chosen signal says the user is waking, or null. */
  fun trigger(window: WakeWindow, bounds: WakeBounds, series: WakeSeries): Long? =
      listOfNotNull(
              if (window.heartRate) heartRateTrigger(bounds, series.hr, series.sleeping) else null,
              if (window.movement) movementTrigger(bounds, series.movement) else null,
          )
          .minOrNull()

  /** When the alarm rings: the first trigger inside the window, or the end of the window. */
  fun firesAt(window: WakeWindow, bounds: WakeBounds, series: WakeSeries): Long =
      trigger(window, bounds, series) ?: bounds.endUtcMs

  /**
   * The first evaluation inside [bounds] whose score held at or above the threshold for
   * [HeartRateWakeModel.consecutiveEvaluations] evaluations in a row. Evaluations sit one
   * [HeartRateWakeModel.stepMs] apart and look only at the samples before them.
   */
  fun heartRateTrigger(
      bounds: WakeBounds,
      hr: List<WakePoint>,
      sleeping: HeartRateCounts,
      model: HeartRateWakeModel = heartRateModel,
  ): Long? {
    var high = 0
    var at = bounds.startUtcMs
    while (at <= bounds.endUtcMs) {
      val features = features(hr, at, sleeping, model)
      if (features != null && score(features, model) >= model.threshold) {
        high++
        if (high >= model.consecutiveEvaluations) return at
      } else {
        high = 0
      }
      at += model.stepMs
    }
    return null
  }

  /**
   * The features of the score at [atMs], or null while there is not enough data: the counts need
   * [HeartRateWakeModel.minimumSleepingSamples] samples, the level and its short spread
   * [HeartRateWakeModel.minimumLevelSamples] in the level window, and the long spread
   * [HeartRateWakeModel.minimumSpreadSamples] in its own window.
   */
  fun features(
      hr: List<WakePoint>,
      atMs: Long,
      sleeping: HeartRateCounts,
      model: HeartRateWakeModel = heartRateModel,
  ): HeartRateWakeFeatures? {
    val sleepingLevel =
        if (sleeping.samples < model.minimumSleepingSamples) null
        else sleeping.percentile(model.sleepingPercentile)?.takeIf { it > 0.0 }
    val level = valuesBetween(hr, atMs - model.levelWindowMs, atMs)
    val spread = valuesBetween(hr, atMs - model.spreadWindowMs, atMs)
    if (sleepingLevel == null ||
        level.size < model.minimumLevelSamples ||
        spread.size < model.minimumSpreadSamples) {
      return null
    }
    return HeartRateWakeFeatures(
        level = level.average() / sleepingLevel,
        spread2 = stdDev(level) / sleepingLevel,
        spread5 = stdDev(spread) / sleepingLevel,
    )
  }

  /** The score of [features]: a logistic of their weighted sum with the model's bias. */
  fun score(features: HeartRateWakeFeatures, model: HeartRateWakeModel = heartRateModel): Double {
    val weighted =
        model.levelWeight * features.level +
            model.spread2Weight * features.spread2 +
            model.spread5Weight * features.spread5 +
            model.bias
    return 1.0 / (1.0 + exp(-weighted))
  }

  /** A run of movement samples above the threshold, one sample per second. */
  fun movementTrigger(bounds: WakeBounds, movement: List<WakePoint>): Long? {
    var runStart: Long? = null
    for (point in movement) {
      if (point.atMs < bounds.startUtcMs) continue
      if (point.atMs > bounds.endUtcMs) break
      if (point.value <= MOVEMENT_MG) {
        runStart = null
        continue
      }
      val start = runStart ?: point.atMs.also { runStart = it }
      if (point.atMs - start >= SUSTAIN_MS) return start + SUSTAIN_MS
    }
    return null
  }

  /** The heart rate of the samples at or after [fromMs] and before [toMs]. */
  private fun valuesBetween(hr: List<WakePoint>, fromMs: Long, toMs: Long): List<Double> =
      hr.filter { it.atMs >= fromMs && it.atMs < toMs }.map { it.value }

  /** The population standard deviation of [values]. */
  private fun stdDev(values: List<Double>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
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
