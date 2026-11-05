package com.rbmr.taigasleep.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSettingsTest {
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
}
