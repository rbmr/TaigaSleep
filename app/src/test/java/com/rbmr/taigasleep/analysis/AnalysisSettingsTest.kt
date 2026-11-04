package com.rbmr.taigasleep.analysis

import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.remote.AlgorithmNeedDto
import com.rbmr.taigasleep.storage.SessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSettingsTest {
  private val ecg =
      SessionConfig(
          hr = true,
          ecg = true,
          acc = true,
          ecgRateHz = 130,
          accRateHz = 50,
      )

  @Test
  fun theDefaultIsAutomaticAnalysisInTheDocumentedOrder() {
    val settings = AnalysisSettings()

    assertTrue(settings.auto)
    assertEquals(listOf("wav2sleep", "sleep-ecg", "baseline"), settings.preferenceOrder)
  }

  @Test
  fun aMissingStoredOrderFallsBackToTheDefault() {
    assertEquals(StagerPreference, parsePreferenceOrder(null))
    assertEquals(StagerPreference, parsePreferenceOrder(""))
    assertEquals(StagerPreference, parsePreferenceOrder(" , , "))
  }

  @Test
  fun aStoredOrderIsTrimmedAndDeduplicated() {
    assertEquals(
        listOf("baseline", "wav2sleep"),
        parsePreferenceOrder(" baseline , wav2sleep, , baseline "),
    )
  }

  @Test
  fun automaticAnalysisOffDoesNotChooseARun() {
    val choice =
        autoChoice(
            ecg,
            listOf(wav2sleep(version = "1.1.0"), sleepEcg(version = "1.0.0")),
            AnalysisSettings(auto = false),
        )

    assertNull(choice)
  }

  @Test
  fun automaticAnalysisOnChoosesTheBestCompatibleRun() {
    val choice =
        autoChoice(
            ecg,
            listOf(
                baseline(version = "1.0.0"),
                sleepEcg(version = "1.0.0"),
                wav2sleep(version = "1.1.0"),
            ),
            AnalysisSettings(),
        )

    assertEquals(StagerChoice("wav2sleep", "1.1.0"), choice)
  }

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

  @Test
  fun theEffectiveOrderAppendsInstalledAlgorithmsThatAreNotInTheSavedOrder() {
    assertEquals(
        listOf("wav2sleep", "baseline", "sleep-ecg"),
        effectiveOrder(
            stored = listOf("wav2sleep", "baseline"),
            installed = listOf("baseline", "sleep-ecg", "wav2sleep"),
        ),
    )
  }

  @Test
  fun movingAnAlgorithmStopsAtTheEndsAndIgnoresUnknownIds() {
    val order = listOf("wav2sleep", "sleep-ecg", "baseline")

    assertEquals(listOf("sleep-ecg", "wav2sleep", "baseline"), movePreference(order, "sleep-ecg", -1))
    assertEquals(listOf("wav2sleep", "baseline", "sleep-ecg"), movePreference(order, "sleep-ecg", 1))
    assertEquals(order, movePreference(order, "wav2sleep", -1))
    assertEquals(order, movePreference(order, "baseline", 1))
    assertEquals(order, movePreference(order, "missing", 1))
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
