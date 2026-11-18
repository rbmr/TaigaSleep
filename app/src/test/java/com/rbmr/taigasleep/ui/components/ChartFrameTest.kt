package com.rbmr.taigasleep.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartFrameTest {
  @Test
  fun `a recording chart grows its label column with the font`() {
    assertEquals(68.dp, recordingChartLabelSpace(false, 68.dp, 1f).left)
    assertEquals(102.dp, recordingChartLabelSpace(false, 68.dp, 1.5f).left)
    assertEquals(144.dp, recordingChartLabelSpace(true, 96.dp, 1.5f).left)
  }

  @Test
  fun `the rest of a recording chart's room stays as it was`() {
    assertEquals(
        ChartLabelSpace(left = 102.dp, top = 8.dp, bottom = 22.dp),
        recordingChartLabelSpace(false, 68.dp, 1.5f),
    )
    assertEquals(
        ChartLabelSpace(left = 144.dp, top = 2.dp, bottom = 26.dp),
        recordingChartLabelSpace(true, 96.dp, 1.5f),
    )
  }
}
