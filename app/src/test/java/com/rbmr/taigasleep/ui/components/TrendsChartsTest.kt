package com.rbmr.taigasleep.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsChartsTest {
  private fun labels(axis: TrendAxis) = axis.ticks.map { it.second }

  @Test
  fun `35 to 65 bpm gets evenly spaced ticks from 30 to 70`() {
    val axis = trendAxis(35.0, 65.0, ::trendNumber)

    assertEquals(30.0, axis.min, 0.0)
    assertEquals(70.0, axis.max, 0.0)
    assertEquals(listOf("70", "60", "50", "40", "30"), labels(axis))
    assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), axis.ticks.map { it.first })
  }

  @Test
  fun `a zero-based axis is even as well`() {
    val axis = trendAxis(0.0, 80.0, ::trendNumber)

    assertEquals(listOf("80", "60", "40", "20", "0"), labels(axis))
    assertEquals(0.0, axis.min, 0.0)
    assertEquals(80.0, axis.max, 0.0)
  }

  @Test
  fun `total sleep is drawn in hours, so its ticks are whole hours`() {
    val hours = totalSleepHours(29_670.0)!!
    assertEquals(8.2417, hours, 1e-4)

    val axis = hoursAxis(listOf(hours), null)

    assertEquals(listOf("9 h", "6 h", "3 h", "0 h"), labels(axis))
    assertEquals(0.0, axis.min, 0.0)
    assertEquals(9.0, axis.max, 0.0)
  }

  @Test
  fun `the intended sleep duration widens the hours axis`() {
    val axis = hoursAxis(listOf(6.0), 8.0)

    assertEquals(listOf("8 h", "6 h", "4 h", "2 h", "0 h"), labels(axis))
    assertEquals(8.0, axis.max, 0.0)
  }

  @Test
  fun `short total sleep still gets whole-hour ticks`() {
    val axis = hoursAxis(listOf(1.2), null)

    assertEquals(listOf("2 h", "1 h", "0 h"), labels(axis))
  }

  @Test
  fun `every axis has three to five even ticks that cover the data`() {
    val ranges =
        listOf(
            0.0 to 80.0,
            35.0 to 65.0,
            55.0 to 62.0,
            70.0 to 95.0,
            0.0 to 8.2417,
            99.0 to 100.0,
        )
    for ((low, high) in ranges) {
      val axis = trendAxis(low, high, ::trendNumber)

      assertTrue("$low..$high has ${axis.ticks.size} ticks", axis.ticks.size in 3..5)
      assertTrue("$low..$high starts above the data", axis.min <= low)
      assertTrue("$low..$high ends below the data", axis.max >= high)
      val steps = axis.ticks.map { it.first }.zipWithNext { a, b -> a - b }
      for (step in steps) assertEquals(steps.first(), step, 1e-6f)
    }
  }

  @Test
  fun `a half step still reads as a half`() {
    assertEquals("2.5", trendNumber(2.5))
    assertEquals("8", trendNumber(8.0))
    assertEquals("0", trendNumber(0.0))
  }
}
