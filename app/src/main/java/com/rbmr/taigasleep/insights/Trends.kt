package com.rbmr.taigasleep.insights

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** The ranges the Trends view offers, in days, ending today. */
val TrendRanges = listOf(7, 14, 30)

/** The analysis items a trend is read from. An item that is absent is a night without a value. */
object TrendItems {
  const val TOTAL_SLEEP = "total_sleep_s"
  const val SLEEP_LATENCY = "sleep_latency_s"
  const val FINAL_AWAKENING = "final_awakening_s"
  const val DATA_QUALITY = "data_quality"
  const val SCORE = "sleep_quality"
  const val EFFICIENCY = "sleep_efficiency"
  const val HEART_RATE_MIN = "hr_min_bpm"
  const val HEART_RATE_MEAN = "hr_mean_bpm"
  const val HRV_RMSSD = "hrv_rmssd_ms"
}

/** A shorter night is drawn but does not contribute to an average. */
private const val MIN_AVERAGE_NIGHT_SECONDS = 3L * 3_600

/** A night with a lower data quality is drawn faintly and left out of the averages. */
private const val POOR_DATA_QUALITY = 50.0

private const val MINUTES_PER_DAY = 24 * 60
private const val MINUTES_PER_HALF_DAY = MINUTES_PER_DAY / 2

/**
 * One completed recording as the trends read it: the times of the recording and the items of the
 * analysis it shows on its own screen. The items are a plain map, so the computation needs neither
 * the backend nor the library.
 */
data class TrendRecording(
    val key: String,
    val localId: String?,
    val remoteId: String?,
    val analysisId: String?,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val items: Map<String, Double> = emptyMap(),
)

/** One night of the range, with the clock times and values its charts draw. */
data class TrendNight(
    val key: String,
    val localId: String?,
    val remoteId: String?,
    val analysisId: String?,
    /** The local date of the recording's end, which is the wake-up date. */
    val date: LocalDate,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** Recording start plus the result's sleep latency. */
    val onsetMinuteOfDay: Int?,
    /** Recording end minus the result's final awakening. */
    val finalAwakeningMinuteOfDay: Int?,
    val durationSeconds: Long,
    val dataQuality: Double?,
    /** Below the data-quality threshold: drawn at reduced opacity, never dropped. */
    val poorData: Boolean,
    val totalSleepSeconds: Double?,
    val score: Double?,
    val efficiency: Double?,
    val heartRateMinBpm: Double?,
    val heartRateMeanBpm: Double?,
    val hrvRmssdMs: Double?,
)

/** An average over the nights that have a value and are long enough with usable data. */
data class TrendAverage(val value: Double?, val nights: Int) {
  /** Fewer than two nights are no trend: the chart says more nights are needed instead. */
  val enough: Boolean
    get() = value != null && nights >= 2
}

/** A mean clock time and its circular spread, for bedtimes and wake times. */
data class ClockAverage(val meanMinuteOfDay: Int, val spreadMinutes: Int, val nights: Int) {
  val enough: Boolean
    get() = nights >= 2
}

/** The schedule averages; a missing time means no night in the range had one. */
data class ScheduleTrend(val bedtime: ClockAverage?, val wake: ClockAverage?) {
  val nights: Int
    get() = maxOf(bedtime?.nights ?: 0, wake?.nights ?: 0)

  val enough: Boolean
    get() = nights >= 2
}

/** Everything the trends screen draws, computed once for a range and a set of recordings. */
data class Trends(
    /** The calendar days of the range, oldest first, so a day without a night keeps its slot. */
    val days: List<LocalDate>,
    /** One entry per day: the night that ends that day, or null. */
    val nights: List<TrendNight?>,
    val schedule: ScheduleTrend,
    val totalSleep: TrendAverage,
    val score: TrendAverage,
    val efficiency: TrendAverage,
    val heartRateMin: TrendAverage,
    val heartRateMean: TrendAverage,
    val hrv: TrendAverage,
    /** The usual bedtime as a clock minute, for the dotted line; null when it is not set. */
    val usualBedtimeMinutes: Int?,
    /** The usual wake time as a clock minute, for the dotted line; null when it is not set. */
    val usualWakeMinutes: Int?,
    /** Wake time minus bedtime, across midnight, when both are set. */
    val intendedSleepMinutes: Int?,
)

/**
 * Assign every recording to the local date it ended, keep the range, and derive the values and
 * averages the charts show. Two recordings that end on one date become the longer one. Only nights
 * with a value, at least three hours of recording and a data quality of 50 or more are averaged;
 * poor-data nights are still kept in [Trends.nights] so the chart can draw them faintly.
 */
fun trendsFor(
    recordings: List<TrendRecording>,
    rangeDays: Int,
    today: LocalDate,
    zone: ZoneId,
    usualBedtime: LocalTime? = null,
    usualWakeTime: LocalTime? = null,
): Trends {
  val size = rangeDays.coerceAtLeast(1)
  val days = List(size) { index -> today.minusDays((size - 1 - index).toLong()) }
  val byDate = mutableMapOf<LocalDate, TrendNight>()
  for (recording in recordings) {
    if (recording.startUtcMs <= 0 || recording.endUtcMs <= recording.startUtcMs) continue
    val end = Instant.ofEpochMilli(recording.endUtcMs).atZone(zone)
    val date = end.toLocalDate()
    if (date !in days) continue
    val night = trendNight(recording, date, zone)
    val existing = byDate[date]
    if (existing == null || longer(night, existing)) byDate[date] = night
  }

  val qualifying = byDate.values.filter { night ->
    night.durationSeconds >= MIN_AVERAGE_NIGHT_SECONDS &&
        night.dataQuality?.let { it >= POOR_DATA_QUALITY } == true
  }
  val schedule =
      ScheduleTrend(
          bedtime = circularAverage(qualifying.map { it.startMinuteOfDay }),
          wake = circularAverage(qualifying.map { it.endMinuteOfDay }),
      )
  val usualBedtimeMinutes = usualBedtime?.let { it.hour * 60 + it.minute }
  val usualWakeMinutes = usualWakeTime?.let { it.hour * 60 + it.minute }
  return Trends(
      days = days,
      nights = days.map { byDate[it] },
      schedule = schedule,
      totalSleep = average(qualifying.map { it.totalSleepSeconds }),
      score = average(qualifying.map { it.score }),
      efficiency = average(qualifying.map { it.efficiency }),
      heartRateMin = average(qualifying.map { it.heartRateMinBpm }),
      heartRateMean = average(qualifying.map { it.heartRateMeanBpm }),
      hrv = average(qualifying.map { it.hrvRmssdMs }),
      usualBedtimeMinutes = usualBedtimeMinutes,
      usualWakeMinutes = usualWakeMinutes,
      intendedSleepMinutes = intendedSleepMinutes(usualBedtimeMinutes, usualWakeMinutes),
  )
}

private fun trendNight(
    recording: TrendRecording,
    date: LocalDate,
    zone: ZoneId,
): TrendNight {
  val startMinute = minuteOfDay(recording.startUtcMs, zone)
  val endMinute = minuteOfDay(recording.endUtcMs, zone)
  val quality = recording.value(TrendItems.DATA_QUALITY)
  val latency = recording.value(TrendItems.SLEEP_LATENCY)?.takeIf { it >= 0 }
  val finalAwakening = recording.value(TrendItems.FINAL_AWAKENING)?.takeIf { it >= 0 }
  return TrendNight(
      key = recording.key,
      localId = recording.localId,
      remoteId = recording.remoteId,
      analysisId = recording.analysisId,
      date = date,
      startMinuteOfDay = startMinute,
      endMinuteOfDay = endMinute,
      onsetMinuteOfDay =
          latency?.let { minuteOfDay(recording.startUtcMs + (it * 1000).roundToLong(), zone) },
      finalAwakeningMinuteOfDay =
          finalAwakening?.let {
            minuteOfDay(recording.endUtcMs - (it * 1000).roundToLong(), zone)
          },
      durationSeconds = (recording.endUtcMs - recording.startUtcMs) / 1000,
      dataQuality = quality,
      poorData = quality != null && quality < POOR_DATA_QUALITY,
      totalSleepSeconds = recording.value(TrendItems.TOTAL_SLEEP),
      score = recording.value(TrendItems.SCORE),
      efficiency = recording.value(TrendItems.EFFICIENCY),
      heartRateMinBpm = recording.value(TrendItems.HEART_RATE_MIN),
      heartRateMeanBpm = recording.value(TrendItems.HEART_RATE_MEAN),
      hrvRmssdMs = recording.value(TrendItems.HRV_RMSSD),
  )
}

private fun TrendRecording.value(id: String): Double? = items[id]?.takeIf { it.isFinite() }

private fun minuteOfDay(utcMs: Long, zone: ZoneId): Int =
    (Instant.ofEpochMilli(utcMs).atZone(zone).toLocalTime().toSecondOfDay() / 60)

/** The longest recording of a date wins; a tie keeps the one that ended later. */
private fun longer(candidate: TrendNight, existing: TrendNight): Boolean =
    when {
      candidate.durationSeconds != existing.durationSeconds ->
          candidate.durationSeconds > existing.durationSeconds
      candidate.key == existing.key -> false
      else -> candidate.key > existing.key
    }

/** Mean of the values that are present, or an empty average when no night has one. */
private fun average(values: List<Double?>): TrendAverage {
  val present = values.filterNotNull()
  return TrendAverage(
      value = if (present.isEmpty()) null else present.sum() / present.size,
      nights = present.size,
  )
}

/**
 * The circular mean of clock minutes and the standard circular deviation, in minutes. Midnight is
 * no boundary for the mean: 23:30 and 00:30 average to 00:00, with a spread of 30 minutes.
 */
internal fun circularAverage(minutes: List<Int>): ClockAverage? {
  if (minutes.isEmpty()) return null
  var sine = 0.0
  var cosine = 0.0
  for (minute in minutes) {
    val angle = 2.0 * PI * (minute % MINUTES_PER_DAY) / MINUTES_PER_DAY
    sine += sin(angle)
    cosine += cos(angle)
  }
  val count = minutes.size.toDouble()
  val resultant = hypot(sine, cosine) / count
  val angle = atan2(sine, cosine)
  val meanMinute =
      ((angle / (2.0 * PI) * MINUTES_PER_DAY).roundToInt() % MINUTES_PER_DAY + MINUTES_PER_DAY) %
          MINUTES_PER_DAY
  val spread =
      if (resultant >= 1.0) {
        0.0
      } else {
        sqrt(-2.0 * ln(resultant.coerceAtLeast(1e-9))) * MINUTES_PER_DAY / (2.0 * PI)
      }
  return ClockAverage(
      meanMinuteOfDay = meanMinute,
      spreadMinutes = spread.roundToInt(),
      nights = minutes.size,
  )
}

/**
 * Minutes since noon: an evening time comes before midnight and an early morning one after it, so
 * the schedule axis can cross midnight without special cases.
 */
fun nightMinuteOfDay(minuteOfDay: Int): Int =
    (minuteOfDay - MINUTES_PER_HALF_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

/** The clock minute of a night axis value, back in the usual day. */
fun clockMinuteOfNightMinute(nightMinute: Int): Int =
    (nightMinute + MINUTES_PER_HALF_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

/** Wake time minus bedtime, across midnight, or null when only one of the two is set. */
private fun intendedSleepMinutes(bedtime: Int?, wake: Int?): Int? {
  if (bedtime == null || wake == null) return null
  return (wake - bedtime + MINUTES_PER_DAY) % MINUTES_PER_DAY
}
