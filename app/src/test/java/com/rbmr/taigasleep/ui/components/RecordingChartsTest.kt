package com.rbmr.taigasleep.ui.components

import com.rbmr.taigasleep.backend.HrSeriesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingChartsTest {
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
  fun aFlatSeriesGetsAnAxisTenWideCentredOnIt() {
    val axis = seriesAxis(61.0, 61.0)

    assertEquals(10.0, axis.endInclusive - axis.start, 0.0001)
    assertEquals(61.0, (axis.start + axis.endInclusive) / 2, 0.0001)
  }

  @Test
  fun aFlatSeriesNeverRepeatsAnAxisLabel() {
    val ticks = seriesAxisTicks(seriesAxis(61.0, 61.0))

    assertEquals(ticks.size, ticks.toSet().size)
    assertEquals(61, ticks[2])
  }

  @Test
  fun aSeriesWiderThanTheMinimumKeepsItsOwnRange() {
    val axis = seriesAxis(50.0, 90.0)

    assertEquals(50.0, axis.start, 0.0001)
    assertEquals(90.0, axis.endInclusive, 0.0001)
    assertEquals(listOf(90, 80, 70, 60, 50), seriesAxisTicks(axis))
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
