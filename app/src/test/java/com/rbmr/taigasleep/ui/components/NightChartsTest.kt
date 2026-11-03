package com.rbmr.taigasleep.ui.components

import com.rbmr.taigasleep.remote.HrSeriesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightChartsTest {
  @Test
  fun aMissingSeriesHasNoPoints() {
    assertTrue(hrSeriesPoints(null).isEmpty())
  }

  @Test
  fun gapsStayGapsInsteadOfBeingBridged() {
    val points =
        hrSeriesPoints(
            HrSeriesDto(
                epoch_seconds = 30,
                count = 4,
                mean_bpm = listOf(60.0, null, Double.NaN, 58.0),
            )
        )

    assertEquals(listOf<Double?>(60.0, null, null, 58.0), points)
  }

  @Test
  fun aSeriesIsTrimmedToItsDeclaredCount() {
    val points =
        hrSeriesPoints(
            HrSeriesDto(
                epoch_seconds = 30,
                count = 2,
                mean_bpm = listOf(60.0, 61.0, 62.0),
            )
        )

    assertEquals(listOf<Double?>(60.0, 61.0), points)
  }

  @Test
  fun aFirstValidPointStartsItsOwnRun() {
    val points = listOf<Double?>(60.0, 61.0, null, 58.0)

    assertEquals(
        listOf(listOf(0, 1), listOf(3)),
        hrSeriesRuns(points).map { run -> run.map { it.index } },
    )
  }
}
