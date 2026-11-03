package com.rbmr.taigasleep.analysis

import androidx.annotation.StringRes
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.remote.MetricSupportDto
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

enum class MetricValueKind {
  DURATION,
  PERCENT,
  BPM,
  MILLISECONDS,
  COUNT,
  GRAMS,
}

enum class MetricSupportState {
  SUPPORTED,
  UNSUPPORTED_DEVICE,
  INSUFFICIENT_DATA,
  UNSUPPORTED_ALGORITHM,
  MISSING,
}

data class MetricSpec(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val explanationRes: Int,
    val kind: MetricValueKind,
)

/**
 * The catalog is deliberately fixed: every metric can be explained. The night detail offers only
 * the metrics that belong to its section, so the same figure is never shown twice.
 */
val MetricCatalog: List<MetricSpec> =
    listOf(
        MetricSpec("total_sleep_s", R.string.metric_total_sleep, R.string.metric_total_sleep_explanation, MetricValueKind.DURATION),
        MetricSpec("sleep_latency_s", R.string.metric_sleep_latency, R.string.metric_sleep_latency_explanation, MetricValueKind.DURATION),
        MetricSpec("sleep_efficiency", R.string.metric_sleep_efficiency, R.string.metric_sleep_efficiency_explanation, MetricValueKind.PERCENT),
        MetricSpec("awakenings", R.string.metric_awakenings, R.string.metric_awakenings_explanation, MetricValueKind.COUNT),
        MetricSpec("waso_s", R.string.metric_waso, R.string.metric_waso_explanation, MetricValueKind.DURATION),
        MetricSpec("final_awakening_s", R.string.metric_final_awakening, R.string.metric_final_awakening_explanation, MetricValueKind.DURATION),
        MetricSpec("hr_mean_bpm", R.string.metric_hr_mean, R.string.metric_hr_mean_explanation, MetricValueKind.BPM),
        MetricSpec("hr_min_bpm", R.string.metric_hr_min, R.string.metric_hr_min_explanation, MetricValueKind.BPM),
        MetricSpec("hr_max_bpm", R.string.metric_hr_max, R.string.metric_hr_max_explanation, MetricValueKind.BPM),
        MetricSpec("hrv_rmssd_ms", R.string.metric_hrv_rmssd, R.string.metric_hrv_rmssd_explanation, MetricValueKind.MILLISECONDS),
        MetricSpec("hrv_sdnn_ms", R.string.metric_hrv_sdnn, R.string.metric_hrv_sdnn_explanation, MetricValueKind.MILLISECONDS),
        MetricSpec("hrv_rr_count", R.string.metric_hrv_count, R.string.metric_hrv_count_explanation, MetricValueKind.COUNT),
        MetricSpec("movement_index", R.string.metric_movement_index, R.string.metric_movement_index_explanation, MetricValueKind.PERCENT),
        MetricSpec("movement_mean_g", R.string.metric_movement_mean, R.string.metric_movement_mean_explanation, MetricValueKind.GRAMS),
        MetricSpec("movement_epochs", R.string.metric_movement_epochs, R.string.metric_movement_epochs_explanation, MetricValueKind.COUNT),
        MetricSpec("scored_duration_s", R.string.metric_scored_duration, R.string.metric_scored_duration_explanation, MetricValueKind.DURATION),
        MetricSpec("unscored_s", R.string.metric_unscored, R.string.metric_unscored_explanation, MetricValueKind.DURATION),
        MetricSpec("total_duration_s", R.string.metric_total_duration, R.string.metric_total_duration_explanation, MetricValueKind.DURATION),
    )

/** The sleep figures of the night-detail sleep block. */
val SleepMetricKeys =
    listOf(
        "total_duration_s",
        "sleep_efficiency",
        "total_sleep_s",
        "sleep_latency_s",
        "awakenings",
        "waso_s",
    )

/** The heart-rate figures. HRV sample count is a supporting detail, not a headline figure. */
val HeartRateMetricKeys =
    listOf("hr_mean_bpm", "hr_min_bpm", "hr_max_bpm", "hrv_rmssd_ms", "hrv_sdnn_ms")

/** Data-quality figures. Coverage stays part of the data-quality score. */
val DataQualityMetricKeys = listOf("scored_duration_s", "unscored_s")

/** The movement figures that belong to none of the three sections. */
val MovementMetricKeys = listOf("movement_index", "movement_mean_g", "movement_epochs")

fun metricSpec(key: String): MetricSpec? = MetricCatalog.firstOrNull { it.key == key }

data class MetricExplanation(val body: String, val source: String?)

/** Split a metric explanation into its main text and its trailing Source note. */
fun splitMetricExplanation(text: String): MetricExplanation {
  val index = text.indexOf("Source:")
  if (index < 0) return MetricExplanation(text.trim(), null)
  return MetricExplanation(
      body = text.substring(0, index).trimEnd(),
      source = text.substring(index).trim(),
  )
}

/** The raw numeric value of one metric, or null when it is absent or not a number. */
fun metricNumber(metrics: kotlinx.serialization.json.JsonObject?, key: String): Double? =
    metrics?.get(key).numberOrNull()

fun supportState(
    key: String,
    support: Map<String, MetricSupportDto>?,
): MetricSupportState {
  val entry = support?.get(key) ?: return MetricSupportState.MISSING
  return when (entry.status) {
    "supported" -> MetricSupportState.SUPPORTED
    "unsupported_device" -> MetricSupportState.UNSUPPORTED_DEVICE
    "insufficient_data" -> MetricSupportState.INSUFFICIENT_DATA
    "unsupported_algorithm" -> MetricSupportState.UNSUPPORTED_ALGORITHM
    else -> MetricSupportState.MISSING
  }
}

fun formatMetric(kind: MetricValueKind, value: JsonElement?): String? {
  val number = value.numberOrNull() ?: return null
  return when (kind) {
    MetricValueKind.DURATION -> formatSeconds(number)
    MetricValueKind.PERCENT -> "${(number * 100).roundToInt()}%"
    MetricValueKind.BPM -> "${number.roundToInt()} bpm"
    MetricValueKind.MILLISECONDS -> "${number.roundToInt()} ms"
    MetricValueKind.COUNT -> number.roundToInt().toString()
    MetricValueKind.GRAMS -> String.format(Locale.US, "%.2f g", number)
  }
}

fun formatSeconds(seconds: Double): String {
  if (!seconds.isFinite() || seconds < 0) return "—"
  val total = seconds.roundToInt()
  val hours = total / 3600
  val minutes = (total % 3600) / 60
  val remainder = total % 60
  return when {
    hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
    hours > 0 -> "${hours}h"
    minutes > 0 -> "${minutes}m"
    else -> "${remainder}s"
  }
}

private fun JsonElement?.numberOrNull(): Double? =
    when (this) {
      null,
      is JsonNull -> null
      is JsonPrimitive -> doubleOrNull
      else -> null
    }

/** A stage's seconds from the compact RLE timeline. */
fun stageSeconds(segments: List<com.rbmr.taigasleep.remote.StageSegmentDto>, epochSeconds: Int): Map<String, Long> {
  val result = linkedMapOf<String, Long>()
  for (segment in segments) {
    if (segment.count <= 0) continue
    result[segment.stage] = (result[segment.stage] ?: 0L) + segment.count.toLong() * epochSeconds
  }
  return result
}

fun stageLabelRes(stage: String): Int =
    when (stage) {
      "wake" -> R.string.night_stage_wake
      "rem" -> R.string.night_stage_rem
      "nrem" -> R.string.night_stage_nrem
      "light" -> R.string.night_stage_light
      "deep" -> R.string.night_stage_deep
      "sleep" -> R.string.night_stage_sleep
      else -> R.string.night_stage_unknown
    }

/** The order used by the sleep stages chart and the breakdown. */
val StageOrder = listOf("wake", "rem", "nrem", "light", "deep", "sleep", "unknown")

/**
 * Stages that this result can actually display. Algorithm-limited stages are omitted instead of
 * being shown with a "not supported by this algorithm" row. When the support map is unavailable
 * (older cached results), fall back to the stages present in the timeline.
 */
fun applicableStages(
    segments: List<com.rbmr.taigasleep.remote.StageSegmentDto>,
    support: Map<String, MetricSupportDto>?,
): List<String> {
  val present = segments.filter { it.count > 0 }.map { it.stage }.toSet()
  val stages =
      if (support.isNullOrEmpty()) {
        StageOrder.filter { it != "unknown" && it in present }
      } else {
        StageOrder.filter { stage ->
          stage != "unknown" &&
              supportState("time_in_${stage}_s", support) != MetricSupportState.UNSUPPORTED_ALGORITHM
        }
      }
  return if ("unknown" in present) stages + "unknown" else stages
}
