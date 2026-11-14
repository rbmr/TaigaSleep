package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AlgorithmNeedDto
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.recording.store.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisChoiceTest {
  private val ecg =
      RecordingConfig(
          hr = true,
          ecg = true,
          acc = true,
          ecgRateHz = 130,
          accRateHz = 50,
      )

  @Test
  fun manualChoicesCanAskForSeveralRuns() {
    val choices =
        manualChoices(
            ecg,
            listOf(wav2sleep(version = "1.1.0"), sleepEcg(version = "1.0.0")),
            ids = listOf("wav2sleep", "sleep-ecg"),
        )

    assertEquals(
        listOf(StagerChoice("wav2sleep", "1.1.0"), StagerChoice("sleep-ecg", "1.0.0")),
        choices,
    )
  }

  private fun baseline(version: String) =
      AlgorithmDto(
          id = "baseline",
          version = version,
          needs = listOf(AlgorithmNeedDto("hr", required = true)),
      )

  private fun sleepEcg(version: String) =
      AlgorithmDto(
          id = "sleep-ecg",
          version = version,
          needs = listOf(AlgorithmNeedDto("hr", required = true)),
      )

  private fun wav2sleep(version: String) =
      AlgorithmDto(
          id = "wav2sleep",
          version = version,
          needs = listOf(AlgorithmNeedDto("ecg", required = true, min_rate_hz = 100.0)),
      )
}
