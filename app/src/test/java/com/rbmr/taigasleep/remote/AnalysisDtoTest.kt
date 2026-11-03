package com.rbmr.taigasleep.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisDtoTest {
  private val json =
      Json {
        ignoreUnknownKeys = true
        explicitNulls = false
      }

  @Test
  fun anOldResultWithoutASleepScoreOrHrSeriesStillLoads() {
    val analysis =
        json.decodeFromString<AnalysisDto>(
            """
            {
              "id": "a1",
              "status": "done",
              "quality": {"score": 88.0, "components": {}, "notes": []},
              "stages": {"epoch_seconds": 30, "count": 1, "segments": [{"stage": "light", "count": 1}]},
              "metrics": {"hr_mean_bpm": 60.0}
            }
            """
        )

    assertNull(analysis.sleep_quality)
    assertNull(analysis.hr)
    assertEquals(88.0, analysis.quality?.score ?: 0.0, 0.001)
  }

  @Test
  fun aNewResultKeepsTheSleepScoreAndHrGaps() {
    val analysis =
        json.decodeFromString<AnalysisDto>(
            """
            {
              "id": "a1",
              "status": "done",
              "sleep_quality": {
                "score": 79.0,
                "components_version": 1,
                "components": {},
                "notes": []
              },
              "hr": {
                "epoch_seconds": 30,
                "start_unix_ms": 1790000000000,
                "count": 3,
                "mean_bpm": [60.0, null, 58.0],
                "sample_count": [30, 0, 30]
              }
            }
            """
        )

    assertEquals(79.0, analysis.sleep_quality?.score ?: 0.0, 0.001)
    assertEquals(listOf(60.0, null, 58.0), analysis.hr?.mean_bpm)
    assertEquals(3, analysis.hr?.count)
  }
}
