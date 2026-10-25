package com.rbmr.taigasleep.analysis

import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.ui.components.QualityBand
import com.rbmr.taigasleep.ui.components.qualityBand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricCatalogTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun supportComesFromTheServerMap() {
    val analysis =
        json.decodeFromString<AnalysisDto>(
            """
            {"id":"a1","status":"done",
             "metrics":{"total_sleep_s":5400.0,"hrv_rmssd_ms":null,"movement_index":null},
             "metric_support":{
               "total_sleep_s":{"status":"supported"},
               "hrv_rmssd_ms":{"status":"insufficient_data","reason":"need_at_least_120_rr_intervals"},
               "movement_index":{"status":"unsupported_device","reason":"no_acc_stream"},
               "time_in_deep_s":{"status":"unsupported_algorithm","reason":"algorithm_does_not_distinguish_deep"}
             }}
            """
        )
    val support = analysis.metric_support

    assertEquals(MetricSupportState.SUPPORTED, supportState("total_sleep_s", support))
    assertEquals(MetricSupportState.INSUFFICIENT_DATA, supportState("hrv_rmssd_ms", support))
    assertEquals(MetricSupportState.UNSUPPORTED_DEVICE, supportState("movement_index", support))
    assertEquals(MetricSupportState.UNSUPPORTED_ALGORITHM, supportState("time_in_deep_s", support))
    assertEquals(MetricSupportState.MISSING, supportState("hr_mean_bpm", support))
  }

  @Test
  fun valuesAreFormattedForTheGrid() {
    assertEquals("1h 30m", formatMetric(MetricValueKind.DURATION, JsonPrimitive(5400.0)))
    assertEquals("86%", formatMetric(MetricValueKind.PERCENT, JsonPrimitive(0.856)))
    assertEquals("52 bpm", formatMetric(MetricValueKind.BPM, JsonPrimitive(52.4)))
    assertEquals("38 ms", formatMetric(MetricValueKind.MILLISECONDS, JsonPrimitive(37.6)))
    assertEquals("12", formatMetric(MetricValueKind.COUNT, JsonPrimitive(12.0)))
    assertEquals("0.04 g", formatMetric(MetricValueKind.GRAMS, JsonPrimitive(0.043)))
    assertNull(formatMetric(MetricValueKind.BPM, JsonNull))
    assertNull(formatMetric(MetricValueKind.BPM, null))
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
  fun qualityBandsFollowTheDisplayThresholds() {
    assertEquals(QualityBand.EXCELLENT, qualityBand(92.0))
    assertEquals(QualityBand.GOOD, qualityBand(72.0))
    assertEquals(QualityBand.FAIR, qualityBand(45.0))
    assertEquals(QualityBand.POOR, qualityBand(12.0))
    assertEquals(QualityBand.NONE, qualityBand(null))
  }
}
