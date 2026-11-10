package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AlgorithmNeedDto
import com.rbmr.taigasleep.recording.store.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagerSelectionTest {
  private val fullConfig =
      RecordingConfig(
          hr = true,
          ecg = true,
          acc = true,
          ecgRateHz = 130,
          accRateHz = 50,
      )

  @Test
  fun anEmptyAlgorithmListFallsBackToBaseline() {
    assertEquals(StagerChoice("baseline", "1.2.0"), selectStager(fullConfig, emptyList()))
  }

  @Test
  fun theBestCompatibleStagerWins() {
    val choice =
        selectStager(
            fullConfig,
            listOf(
                baseline(version = "1.0.0"),
                sleepEcg(version = "1.2.0"),
                wav2sleep(version = "2.0.0"),
            ),
        )

    assertEquals(StagerChoice("wav2sleep", "2.0.0"), choice)
  }

  @Test
  fun wav2sleepIsPreferredOverSleepEcg() {
    val choice =
        selectStager(
            fullConfig,
            listOf(wav2sleep(version = "9.9.9"), sleepEcg(version = "1.0.0")),
        )

    assertEquals(StagerChoice("wav2sleep", "9.9.9"), choice)
  }

  @Test
  fun anHrOnlyRecordingUsesSleepEcgInsteadOfTheBaselineFallback() {
    val noEcg = fullConfig.copy(ecg = false)
    val choice =
        selectStager(
            noEcg,
            listOf(
                wav2sleep(version = "2.0.0"),
                sleepEcg(version = "1.2.0"),
                baseline(version = "1.0.0"),
            ),
        )

    assertEquals(StagerChoice("sleep-ecg", "1.2.0"), choice)
  }

  @Test
  fun aCustomPreferenceOrderWins() {
    val choice =
        selectStager(
            fullConfig,
            listOf(baseline(version = "1.0.0"), sleepEcg(version = "1.2.0")),
            preference = listOf("baseline", "wav2sleep"),
        )

    assertEquals(StagerChoice("baseline", "1.0.0"), choice)
  }

  @Test
  fun manualSelectionKeepsOrderAndSkipsIncompatibleIds() {
    val choices =
        selectStagers(
            fullConfig.copy(ecg = false),
            listOf(
                baseline(version = "1.0.0"),
                sleepEcg(version = "1.2.0"),
                wav2sleep(version = "2.0.0"),
            ),
            ids = listOf("wav2sleep", "sleep-ecg", "baseline", "missing"),
        )

    assertEquals(
        listOf(StagerChoice("sleep-ecg", "1.2.0"), StagerChoice("baseline", "1.0.0")),
        choices,
    )
  }

  @Test
  fun manualSelectionUsesTheLatestVersion() {
    val choices =
        selectStagers(
            fullConfig,
            listOf(sleepEcg(version = "1.0.0"), sleepEcg(version = "1.10.0")),
            ids = listOf("sleep-ecg"),
        )

    assertEquals(listOf(StagerChoice("sleep-ecg", "1.10.0")), choices)
  }

  @Test
  fun theLatestVersionOfAPreferredStagerIsUsed() {
    val choice =
        selectStager(
            fullConfig,
            listOf(
                baseline(version = "1.0.0"),
                baseline(version = "2.1.0"),
                baseline(version = "2.0.9"),
            ),
        )

    assertEquals(StagerChoice("baseline", "2.1.0"), choice)
  }

  @Test
  fun anUnknownRequiredStreamMakesAStagerIncompatible() {
    val unknown =
        AlgorithmDto(
            id = "wav2sleep",
            version = "2.0.0",
            needs = listOf(AlgorithmNeedDto("spo2", required = true)),
        )

    assertTrue(!compatible(unknown, fullConfig))
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
