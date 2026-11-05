package com.rbmr.taigasleep.insights

import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How a typed number is shown. The item's unit selects it; an unknown unit is not guessed. */
enum class MetricValueKind {
  DURATION,
  PERCENT,
  BPM,
  MILLISECONDS,
  COUNT,
  GRAMS,
}

/**
 * A number item in the form the grid and its explanation sheet need. Everything shown comes from
 * the worker's item: there is no local catalog of metric keys or strings.
 */
data class MetricSpec(
    val key: String,
    val kind: MetricValueKind,
    val label: String,
    val explanation: String,
    val source: String?,
    val itemValue: Double?,
)

/** A number item as a display spec. Series and scores have their own components. */
fun ResultItemDto.metricSpec(): MetricSpec? {
  if (kind != "number") return null
  val valueKind =
      when (unit) {
        "s" -> MetricValueKind.DURATION
        "%" -> MetricValueKind.PERCENT
        "bpm" -> MetricValueKind.BPM
        "ms" -> MetricValueKind.MILLISECONDS
        "count" -> MetricValueKind.COUNT
        "g" -> MetricValueKind.GRAMS
        else -> return null
      }
  return MetricSpec(
      key = id,
      kind = valueKind,
      label = label,
      explanation = explanation,
      source = sources.joinToString(", ") { it.uppercase() }.takeIf { it.isNotBlank() },
      itemValue = value,
  )
}

/** Format a typed item's value. Items carry display units, not the fractions raw keys used. */
fun formatItemValue(kind: MetricValueKind, value: Double?): String? {
  val number = value ?: return null
  return when (kind) {
    MetricValueKind.DURATION -> formatSeconds(number)
    MetricValueKind.PERCENT -> "${number.roundToInt()}%"
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

/** A stage's seconds from the compact RLE timeline. */
fun stageSeconds(segments: List<StageSegmentDto>, epochSeconds: Int): Map<String, Long> {
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
 * Stages to show for a result: every stage the algorithm supports, in display order, plus
 * `unknown` only when the timeline actually contains unscored epochs. A supported stage with no
 * time stays visible as 0s / 0%, and a stage the algorithm cannot produce is not shown. When the
 * result carries no list (for example an older cached payload), fall back to the timeline.
 */
fun applicableStages(segments: List<StageSegmentDto>, capabilities: JsonObject?): List<String> {
  val present = segments.filter { it.count > 0 }.map { it.stage }.toSet()
  val supported = capabilities.supportedStages()
  val stages =
      if (supported.isNullOrEmpty()) {
        StageOrder.filter { it != "unknown" && it in present }
      } else {
        StageOrder.filter { it != "unknown" && it in supported }
      }
  return if ("unknown" in present) stages + "unknown" else stages
}

private fun JsonObject?.supportedStages(): List<String>? {
  val array = this?.get("supported_stages") as? JsonArray ?: return null
  return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.takeIf { it.isNotEmpty() }
}
