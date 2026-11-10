package com.rbmr.taigasleep.insights

import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.ui.components.QualityBand
import com.rbmr.taigasleep.ui.components.qualityBand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultItemsTest {
  @Test
  fun typedNumbersChooseTheirFormatFromTheirUnit() {
    assertEquals("1h 30m", formatItemValue(MetricValueKind.DURATION, 5400.0))
    assertEquals("86%", formatItemValue(MetricValueKind.PERCENT, 85.6))
    assertEquals("52 bpm", formatItemValue(MetricValueKind.BPM, 52.4))
    assertEquals("38 ms", formatItemValue(MetricValueKind.MILLISECONDS, 37.6))
    assertEquals("12", formatItemValue(MetricValueKind.COUNT, 12.0))
    assertEquals("0.04 g", formatItemValue(MetricValueKind.GRAMS, 0.043))
    assertNull(formatItemValue(MetricValueKind.BPM, null))
  }

  @Test
  fun aTypedNumberBecomesADisplaySpecWithItsOwnText() {
    val item =
        ResultItemDto(
            kind = "number",
            id = "hr_mean_bpm",
            label = "Average heart rate",
            group = "heart",
            unit = "bpm",
            value = 58.4,
            explanation = "Mean of all valid samples.",
            sources = listOf("hr"),
        )

    val spec = item.metricSpec()!!

    assertEquals("Average heart rate", spec.label)
    assertEquals("Mean of all valid samples.", spec.explanation)
    assertEquals("HR", spec.source)
    assertEquals(MetricValueKind.BPM, spec.kind)
    assertEquals("58 bpm", formatItemValue(spec.kind, spec.itemValue))
  }

  @Test
  fun seriesScoresAndUnknownUnitsAreNotNumberSpecs() {
    assertNull(ResultItemDto(kind = "series", id = "hr", unit = "bpm").metricSpec())
    assertNull(ResultItemDto(kind = "score", id = "s", unit = "%").metricSpec())
    assertNull(ResultItemDto(kind = "number", id = "x", unit = "parsec").metricSpec())
  }

  @Test
  fun aRecordingLengthFromAnOlderResultIsIgnored() {
    val item =
        ResultItemDto(
            kind = "number",
            id = "total_duration_s",
            label = "Recording length",
            group = "quality",
            unit = "s",
            value = 3600.0,
            explanation = "Length of the analyzed recording.",
        )

    assertNull(item.metricSpec())
  }

  @Test
  fun stageSecondsUsesEpochCounts() {
    val seconds =
        stageSeconds(
            listOf(
                StageSegmentDto("wake", 2),
                StageSegmentDto("light", 4),
                StageSegmentDto("deep", 1),
            ),
            epochSeconds = 30,
        )

    assertEquals(60L, seconds["wake"])
    assertEquals(120L, seconds["light"])
    assertEquals(30L, seconds["deep"])
  }

  private fun capabilities(vararg stages: String): JsonObject =
      buildJsonObject {
        putJsonArray("supported_stages") { stages.forEach { add(it) } }
      }

  @Test
  fun aSupportedStageStaysVisibleWithoutTime() {
    val segments =
        listOf(
            StageSegmentDto("wake", 2),
            StageSegmentDto("rem", 3),
            StageSegmentDto("light", 8),
        )

    assertEquals(
        listOf("wake", "rem", "light", "deep"),
        applicableStages(segments, capabilities("wake", "rem", "light", "deep", "unknown")),
    )
  }

  @Test
  fun anAlgorithmThatCannotSplitLightAndDeepNeverListsThem() {
    val segments = listOf(StageSegmentDto("wake", 2), StageSegmentDto("nrem", 8))

    val stages = applicableStages(segments, capabilities("wake", "rem", "nrem", "unknown"))

    assertEquals(listOf("wake", "rem", "nrem"), stages)
    assertNull(stages.firstOrNull { it == "light" || it == "deep" })
  }

  @Test
  fun unknownAppearsOnlyWhenTheTimelineContainsIt() {
    val caps = capabilities("wake", "rem", "light", "deep", "unknown")
    val scored =
        listOf(
            StageSegmentDto("wake", 2),
            StageSegmentDto("light", 8),
        )

    assertEquals(listOf("wake", "rem", "light", "deep"), applicableStages(scored, caps))
    assertEquals(
        listOf("wake", "rem", "light", "deep", "unknown"),
        applicableStages(scored + StageSegmentDto("unknown", 1), caps),
    )
  }

  @Test
  fun aResultWithoutSupportedStagesFallsBackToTheTimeline() {
    val segments =
        listOf(
            StageSegmentDto("rem", 1),
            StageSegmentDto("nrem", 4),
            StageSegmentDto("unknown", 1),
        )

    assertEquals(listOf("rem", "nrem", "unknown"), applicableStages(segments, null))
    assertEquals(
        listOf("rem", "nrem", "unknown"),
        applicableStages(segments, JsonObject(emptyMap())),
    )
  }

  @Test
  fun qualityBandsFollowTheDisplayThresholds() {
    assertEquals(QualityBand.EXCELLENT, qualityBand(92.0))
    assertEquals(QualityBand.GOOD, qualityBand(72.0))
    assertEquals(QualityBand.FAIR, qualityBand(45.0))
    assertEquals(QualityBand.POOR, qualityBand(12.0))
    assertEquals(QualityBand.NONE, qualityBand(null))
  }
}
