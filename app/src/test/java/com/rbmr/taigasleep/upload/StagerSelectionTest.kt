package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.remote.AlgorithmNeedDto
import com.rbmr.taigasleep.storage.SessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagerSelectionTest {
  private val fullConfig =
      SessionConfig(
          hr = true,
          ecg = true,
          acc = true,
          ecgRateHz = 130,
          accRateHz = 50,
      )

  @Test
  fun anEmptyAlgorithmListFallsBackToBaseline() {
    assertEquals(StagerChoice("baseline", "1.1.0"), selectStager(fullConfig, emptyList()))
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
  fun anIncompatibleStagerIsSkipped() {
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

  @Test
  fun versionsCompareNumerically() {
    assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
    assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
    assertEquals(0, compareVersions("1.0.0", "1.0.0"))
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
