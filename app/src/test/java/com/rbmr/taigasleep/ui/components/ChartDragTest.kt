package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.insights.TrendNight
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading a chart that sits in a page which also swaps on a sideways drag. Touching the plot shows
 * the values of the day under the finger, dragging across it keeps doing so without the page
 * following, and a drag that starts beside the chart still turns the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ChartDragTest {
  @get:Rule val rule = createComposeRule()

  private val days = List(7) { LocalDate.of(2026, 11, 5).plusDays(it.toLong()) }

  /** The date the tooltip gives to the day at [index]. */
  private fun tooltipDate(index: Int): String =
      days[index].format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

  @Test
  fun `a horizontal drag on a chart reads values and leaves the page where it is`() {
    showTabs()
    val chart = rule.onNodeWithContentDescription(ChartDescription)

    // A finger on the last day, dragged left across the plot to the first: the drag that turns the
    // page when the chart lets it through.
    chart.performTouchInput {
      val plotLeft = (PlotLeftDp + 2f) * density
      down(Offset(width - 4f, height / 2f))
      for (step in 1..10) {
        advanceEventTime(16)
        moveTo(Offset(width - 4f - (width - 4f - plotLeft) * step / 10f, height / 2f))
      }
    }
    rule.onNodeWithText(tooltipDate(0)).assertIsDisplayed()
    rule.onNodeWithTag("page").assertTextEquals("page 0")

    // The same gesture carries on: the read-out follows the finger back to the middle.
    chart.performTouchInput { moveTo(Offset((width + PlotLeftDp * density) / 2f, height / 2f)) }
    rule.onNodeWithText(tooltipDate(MiddleDay)).assertIsDisplayed()
    rule.onNodeWithTag("page").assertTextEquals("page 0")

    chart.performTouchInput { up() }
    rule.onNodeWithTag("page").assertTextEquals("page 0")
  }

  @Test
  fun `a horizontal drag beside a chart turns the page`() {
    showTabs()

    rule.onNodeWithTag("outside").performTouchInput { swipeLeft() }

    rule.onNodeWithTag("page").assertTextEquals("page 1")
  }

  @Test
  fun `a vertical drag on a chart scrolls the page`() {
    showTabs()
    rule.onNodeWithTag("scrolled").assertTextEquals("scrolled false")

    rule.onNodeWithContentDescription(ChartDescription).performTouchInput { swipeUp() }

    rule.onNodeWithTag("scrolled").assertTextEquals("scrolled true")
    rule.onNodeWithTag("page").assertTextEquals("page 0")
  }

  private fun showTabs() {
    rule.setContent { TaigaTheme { Tabs() } }
  }

  /** A page that swaps on a sideways drag, with a trends chart at the top and room to scroll. */
  @Composable
  private fun Tabs() {
    val pager = rememberPagerState { 2 }
    val list = rememberLazyListState()
    val scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0
    Column(Modifier.fillMaxSize()) {
      // The page and the scroll position are what the test watches, so they are written out.
      Text("page ${pager.currentPage}", Modifier.testTag("page"))
      Text("scrolled $scrolled", Modifier.testTag("scrolled"))
      HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
        if (page == 0) {
          LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(400.dp)) {
            item { ScoreChart() }
            item { Box(Modifier.fillMaxWidth().height(60.dp).testTag("outside")) }
            item { Box(Modifier.fillMaxWidth().height(800.dp)) }
          }
        } else {
          Text("second tab")
        }
      }
    }
  }

  @Composable
  private fun ScoreChart() {
    val color = MaterialTheme.colorScheme.primary
    TrendChart(
        title = "Sleep score",
        days = days,
        kind =
            TrendChartKind.Line(
                listOf(
                    TrendSeries(
                        label = "Average",
                        points =
                            days.mapIndexed { index, day ->
                              TrendPoint(60.0 + index, false, night(day))
                            },
                        color = color,
                    )
                )
            ),
        summary = emptyList(),
        description = ChartDescription,
        axisFormat = ::trendWhole,
        steps = WholeSteps,
    )
  }

  private fun night(date: LocalDate): TrendNight =
      TrendNight(
          key = date.toString(),
          localId = null,
          remoteId = null,
          analysisId = null,
          date = date,
          startMinuteOfDay = 23 * 60 + 12,
          endMinuteOfDay = 7 * 60 + 4,
          onsetMinuteOfDay = 23 * 60 + 30,
          finalAwakeningMinuteOfDay = 6 * 60 + 50,
          durationSeconds = 8 * 3_600,
          dataQuality = 88.0,
          poorData = false,
          totalSleepSeconds = 7.0 * 3_600,
          score = 78.0,
          efficiency = 88.0,
          heartRateMinBpm = 48.0,
          heartRateMeanBpm = 56.0,
          hrvRmssdMs = 52.0,
      )

  private companion object {
    const val ChartDescription = "Sleep score over seven recordings"

    /** The 48 dp of y-axis labels that a trend chart keeps clear of the plot. */
    const val PlotLeftDp = 48f

    /** The day under the middle of the plot of seven. */
    const val MiddleDay = 3
  }
}
