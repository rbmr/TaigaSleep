package com.rbmr.taigasleep.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.backend.HrSeriesDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.insights.TrendNight
import com.rbmr.taigasleep.testFormats
import com.rbmr.taigasleep.ui.theme.LocalFormats
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The charts as they are drawn, compared pixel by pixel with the pictures next to this test. A
 * refactoring that changes a line, a label or a space shows up here even when nothing a test can
 * query has changed.
 *
 * The pictures are rendered with the real graphics stack, at the default text size, with the clock,
 * the language and the way times are written pinned. To accept an intended change, set
 * `TAIGA_UPDATE_CHART_GOLDEN=1` and run the test once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ChartRenderTest {
  @get:Rule val compose = createComposeRule()

  private val formats =
      testFormats(timeFormat = TimeFormat.TWENTY_FOUR_HOUR, zone = ZoneId.of("UTC"))

  @Before
  fun pinTheClockAndTheLanguage() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US)
  }

  @Test
  fun `the stages of a night look the same`() {
    show { SleepStagesChart(Stages, EpochSeconds, NightStart, Modifier.fillMaxWidth()) }
    assertGolden("recording-stages")
  }

  @Test
  fun `the stages of a night look the same full screen`() {
    show {
      SleepStagesChart(Stages, EpochSeconds, NightStart, Modifier.fillMaxSize(), fullscreen = true)
    }
    assertGolden("recording-stages-fullscreen")
  }

  @Test
  fun `the heart rate of a night looks the same`() {
    show { HeartRateChart(HeartRate, Modifier.fillMaxWidth()) }
    assertGolden("recording-heart-rate")
  }

  @Test
  fun `the heart rate of a night looks the same full screen`() {
    show { HeartRateChart(HeartRate, Modifier.fillMaxSize(), fullscreen = true) }
    assertGolden("recording-heart-rate-fullscreen")
  }

  @Test
  fun `the score ring the stage bar and the stage breakdown look the same`() {
    show {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScoreRing(82.0, "Sleep score")
        StageBar(Stages)
        StageBreakdown(Stages, EpochSeconds)
      }
    }
    assertGolden("recording-figures")
  }

  @Test
  fun `a scrubbed recording chart looks the same`() {
    show {
      SleepStagesChart(
          Stages,
          EpochSeconds,
          NightStart,
          Modifier.fillMaxWidth().testTag(ChartTag),
      )
    }
    compose.onNodeWithTag(ChartTag).performTouchInput {
      down(Offset(width * 0.65f, height / 2f))
    }
    assertGolden("recording-stages-scrubbed")
  }

  @Test
  fun `the line of a trend looks the same`() {
    show { ScoreTrend() }
    assertGolden("trend-line")
  }

  @Test
  fun `the bars of a trend look the same`() {
    show { SleepTrend() }
    assertGolden("trend-bars")
  }

  @Test
  fun `the schedule of a trend looks the same`() {
    show { ScheduleTrend() }
    assertGolden("trend-schedule")
  }

  @Test
  fun `a scrubbed trend looks the same`() {
    show { ScoreTrend() }
    compose.onNodeWithContentDescription(ScoreDescription).performTouchInput { down(center) }
    assertGolden("trend-scrubbed")
  }

  /** Draws one scene on the theme's own background, so the pictures do not depend on the window. */
  private fun show(scene: @Composable () -> Unit) {
    compose.setContent {
      TaigaTheme(darkTheme = false) {
        CompositionLocalProvider(LocalFormats provides formats) {
          Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(8.dp)) { scene() }
          }
        }
      }
    }
    compose.waitForIdle()
  }

  private fun assertGolden(name: String) {
    compose.waitForIdle()
    val root = requireNotNull(compose.onRoot().fetchSemanticsNode().root) as ViewRootForTest
    val view = root.view
    val rendered = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(rendered))
    val path = File("src/test/resources/chart-golden/$name.png")
    if (System.getenv("TAIGA_UPDATE_CHART_GOLDEN") == "1") {
      path.parentFile?.mkdirs()
      path.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
      return
    }
    val expected =
        requireNotNull(javaClass.getResourceAsStream("/chart-golden/$name.png")) {
          "chart-golden/$name.png is missing"
        }
    val golden =
        requireNotNull(
            BitmapFactory.decodeStream(
                expected,
                null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        )
    assertEquals("$name width", golden.width, rendered.width)
    assertEquals("$name height", golden.height, rendered.height)
    val actualPixels = IntArray(rendered.width * rendered.height)
    rendered.getPixels(actualPixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
    val goldenPixels = IntArray(golden.width * golden.height)
    golden.getPixels(goldenPixels, 0, golden.width, 0, 0, golden.width, golden.height)
    val first =
        actualPixels.indices.firstOrNull { index -> actualPixels[index] != goldenPixels[index] }
    val difference =
        if (first == null) {
          ""
        } else {
          "first at ${first % rendered.width},${first / rendered.width}"
        }
    assertEquals("$name differs $difference", 0, actualPixels.indices.count {
      actualPixels[it] != goldenPixels[it]
    })
  }

  @Composable
  private fun ScoreTrend() {
    TrendChart(
        title = "Sleep score",
        days = Days,
        kind =
            TrendChartKind.Line(
                listOf(
                    TrendSeries(
                        label = "Average",
                        points = Days.mapIndexed { index, day -> TrendPoint(68.0 + index, false, night(day)) },
                        color = MaterialTheme.colorScheme.primary,
                    )
                )
            ),
        summary = listOf(TrendSummary("Average", "71")),
        description = ScoreDescription,
        axisFormat = WholeAxisFormat,
        steps = WholeSteps,
    )
  }

  @Composable
  private fun SleepTrend() {
    TrendChart(
        title = "Total sleep",
        days = Days,
        kind =
            TrendChartKind.Bars(
                points = Days.mapIndexed { index, day -> TrendPoint(6.5 + index * 0.3, index == 3, night(day)) },
                average = 7.1,
            ),
        summary = listOf(TrendSummary("Average", "7h 6m")),
        description = "Total sleep over seven recordings",
        references = listOf(TrendReference("Intended", 7.5)),
        topLabel = { formats.hours(it) },
        steps = HourSteps,
        minStep = 1.0,
    )
  }

  @Composable
  private fun ScheduleTrend() {
    TrendChart(
        title = "Sleep schedule",
        days = Days,
        kind = TrendChartKind.FloatingBars(Days.map { TrendSpanPoint(night(it), 400.0, 1120.0) }),
        summary =
            listOf(
                TrendSummary("Average bedtime", formats.clockTime(23, 12)),
                TrendSummary("Average wake time", formats.clockTime(7, 4)),
            ),
        description = "Sleep schedule over seven recordings",
        references =
            listOf(
                TrendReference("Usual bedtime", 400.0),
                TrendReference("Usual wake time", 1120.0),
            ),
        axisFormat = clockAxisFormat(formats),
        steps = ClockSteps,
        minStep = 60.0,
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
    const val ChartTag = "chart"
    const val ScoreDescription = "Sleep score over seven recordings"
    const val EpochSeconds = 30
    const val NightStart = 1_790_000_000_000L

    val Days: List<LocalDate> = List(7) { LocalDate.of(2026, 11, 5).plusDays(it.toLong()) }

    val Stages: List<StageSegmentDto> =
        listOf(
            StageSegmentDto("wake", 2),
            StageSegmentDto("light", 8),
            StageSegmentDto("deep", 6),
            StageSegmentDto("rem", 4),
            StageSegmentDto("light", 5),
            StageSegmentDto("unknown", 1),
        )

    val HeartRate: HrSeriesDto =
        HrSeriesDto(
            epoch_seconds = EpochSeconds,
            start_unix_ms = NightStart,
            count = 20,
            mean_bpm =
                listOf(
                    62.0, 60.0, 58.0, 57.0, null, 61.0, 63.0, 66.0, 64.0, 62.0,
                    60.0, 59.0, 58.0, 57.0, 56.0, 58.0, 61.0, 67.0, 65.0, 62.0,
                ),
            sample_count = List(20) { EpochSeconds },
        )
  }
}
