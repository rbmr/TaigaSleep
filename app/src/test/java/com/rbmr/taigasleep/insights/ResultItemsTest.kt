package com.rbmr.taigasleep.insights

import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.ui.components.QualityBand
import com.rbmr.taigasleep.ui.components.qualityBand
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

  @Test
  fun applicableStagesFollowTheTimeline() {
    val threeClass =
        listOf(
            StageSegmentDto("wake", 2),
            StageSegmentDto("rem", 3),
            StageSegmentDto("nrem", 8),
            StageSegmentDto("unknown", 1),
        )
    assertEquals(listOf("wake", "rem", "nrem", "unknown"), applicableStages(threeClass))

    val fourClass =
        listOf(
            StageSegmentDto("wake", 2),
            StageSegmentDto("rem", 3),
            StageSegmentDto("light", 8),
            StageSegmentDto("deep", 4),
        )
    assertEquals(listOf("wake", "rem", "light", "deep"), applicableStages(fourClass))

    val noUnknownTime =
        listOf(
            StageSegmentDto("wake", 2),
            StageSegmentDto("light", 8),
            StageSegmentDto("unknown", 0),
        )
    assertEquals(listOf("wake", "light"), applicableStages(noUnknownTime))
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
