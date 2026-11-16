package com.rbmr.taigasleep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.rbmr.taigasleep.backend.HrSeriesDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.insights.TrendRecording
import com.rbmr.taigasleep.insights.trendsFor
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.testFormats
import com.rbmr.taigasleep.ui.components.HeartRateChart
import com.rbmr.taigasleep.ui.components.SleepStagesChart
import com.rbmr.taigasleep.ui.components.StageBreakdown
import com.rbmr.taigasleep.ui.history.HistoryScreen
import com.rbmr.taigasleep.ui.history.HistoryUiState
import com.rbmr.taigasleep.ui.history.ServerWarning
import com.rbmr.taigasleep.ui.history.TrendsContent
import com.rbmr.taigasleep.ui.history.TrendsUiState
import com.rbmr.taigasleep.ui.theme.TaigaThemeWithFormats
import java.time.LocalDate
import java.time.LocalTime
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
 * What a screen reader is told about History, the Trends charts and the charts of a recording. The
 * lists are recorded from the screens as they are; a redrawing that would change what the screen
 * says, or how many parts it has, shows up here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h4000dp-xhdpi")
class ScreenSemanticsTest {
  @get:Rule val compose = createComposeRule()

  // The lists below carry clock times, so the formatter writes a 24-hour clock as they do.
  private val formats =
      testFormats(timeFormat = TimeFormat.TWENTY_FOUR_HOUR, zone = ZoneId.of("UTC"))

  // The rows and the charts print clock times, so the zone and the language are pinned: the lists
  // below are the same on every machine.
  @Before
  fun pinTheClockAndTheLanguage() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US)
  }

  @Test
  fun `history shows the same rows and the same warning`() {
    compose.setContent {
      TaigaThemeWithFormats(formats = formats) {
        HistoryScreen(
            state = historyState,
            onOpenRecording = { _, _, _ -> },
            onOpenServer = {},
        )
      }
    }
    compose.waitForIdle()

    assertEquals(HistorySemantics, semantics())
  }

  @Test
  fun `trends show the same charts`() {
    compose.setContent {
      TaigaThemeWithFormats(formats = formats) { TrendsContent(state = trendsState, onRangeChange = {}) }
    }
    compose.waitForIdle()

    assertEquals(TrendsSemantics, semantics())
  }

  @Test
  fun `the charts of a night describe the same night`() {
    compose.setContent {
      TaigaThemeWithFormats(formats = formats) {
        Column(Modifier.fillMaxWidth()) {
          SleepStagesChart(
              segments = Stages,
              epochSeconds = 30,
              startUnixMs = NightStart,
              chartHeight = 176,
          )
          HeartRateChart(series = heartRate, chartHeight = 176)
          StageBreakdown(segments = Stages, epochSeconds = 30)
        }
      }
    }
    compose.waitForIdle()

    assertEquals(NightChartSemantics, semantics())
  }

  /** Every text and content description a merged screen holds, in the order it is drawn. */
  private fun semantics(): List<String> {
    val seen = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
      node.config.getOrNull(SemanticsProperties.Text)?.forEach { seen += "text " + it.text }
      node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { seen += "desc $it" }
      node.children.forEach(::walk)
    }
    walk(compose.onRoot().fetchSemanticsNode())
    return seen
  }

  private fun night(
      key: String,
      status: RecordingStatus,
      score: Double? = null,
      progress: Float = 0f,
      failure: UploadFailure? = null,
  ): RecordingView {
    val analysis =
        score?.let {
          Analysis(
              id = "analysis-$key",
              algorithm = "wav2sleep",
              version = "1.2",
              status = AnalysisStatus.DONE,
              segments = Stages,
              sleepScore = it,
          )
        }
    return RecordingView(
        key = key,
        localId = key,
        remoteId = "remote-$key",
        startUtcMs = 0,
        endUtcMs = null,
        durationMs = 3_600_000,
        device = "Polar H10",
        status = status,
        progress = progress,
        failure = failure,
        errorDetail = null,
        warning = null,
        analyses = listOfNotNull(analysis),
        selectedAnalysisId = analysis?.id,
        backup = BackupState(),
        local = null,
        remote = null,
        resultDownloaded = analysis != null,
    )
  }

  /** One row of each state History can show, so every pill and the score are drawn. */
  private val historyState: HistoryUiState by lazy {
    HistoryUiState(
        items =
            listOf(
                night("a", RecordingStatus.READY, score = 78.0),
                night("b", RecordingStatus.UPLOADING, progress = 0.42f),
                night("c", RecordingStatus.NOT_ANALYZED),
                night("d", RecordingStatus.FAILED, failure = UploadFailure.ANALYSIS),
                night("e", RecordingStatus.LOCAL_ONLY),
            ),
        serverWarning = ServerWarning.UNREACHABLE,
    )
  }

  private val trendsState: TrendsUiState by lazy {
    val today = LocalDate.of(2026, 11, 15)
    val nights =
        (6L downTo 0L).map { daysAgo ->
          val start =
              today
                  .minusDays(daysAgo)
                  .atTime(23, 12)
                  .atZone(ZoneId.of("UTC"))
                  .toInstant()
                  .toEpochMilli()
          TrendRecording(
              key = "trend-$daysAgo",
              localId = null,
              remoteId = "remote-trend-$daysAgo",
              analysisId = "analysis-trend-$daysAgo",
              startUtcMs = start,
              endUtcMs = start + 8 * 3_600_000L,
              items =
                  mapOf(
                      "total_sleep_s" to 26_000.0 + daysAgo * 300,
                      "sleep_quality" to 74.0 + daysAgo,
                      "sleep_efficiency" to 86.0 + daysAgo,
                      "data_quality" to 88.0,
                      "hr_min_bpm" to 48.0 + daysAgo,
                      "hr_mean_bpm" to 55.0 + daysAgo,
                      "hrv_rmssd_ms" to 50.0 + daysAgo,
                  ),
          )
        }
    TrendsUiState(
        rangeDays = 7,
        data =
            trendsFor(
                recordings = nights,
                rangeDays = 7,
                today = today,
                zone = ZoneId.of("UTC"),
                usualBedtime = LocalTime.of(23, 0),
                usualWakeTime = LocalTime.of(7, 0),
            ),
    )
  }

  private val heartRate =
      HrSeriesDto(
          epoch_seconds = 30,
          start_unix_ms = NightStart,
          count = 16,
          mean_bpm =
              listOf(
                  62.0, 60.0, 58.0, 57.0, null, 61.0, 63.0, 66.0,
                  64.0, 62.0, 60.0, 59.0, 58.0, 57.0, 56.0, 58.0,
              ),
          sample_count = List(16) { 30 },
      )

  private companion object {
    const val NightStart = 1_790_000_000_000L

    val Stages =
        listOf(
            StageSegmentDto(stage = "wake", count = 12),
            StageSegmentDto(stage = "rem", count = 40),
            StageSegmentDto(stage = "light", count = 60),
            StageSegmentDto(stage = "deep", count = 30),
        )

    val HistorySemantics =
        listOf(
            "text History",
            "text Can't reach the server",
            "text Recordings are saved but not analyzed.",
            "text -",
            "text - · 1h",
            "text 78",
            "text Score",
            "desc Sleep stage distribution",
            "text -",
            "text - · 1h",
            "text Uploading 42%",
            "desc Uploading 42%",
            "text -",
            "text - · 1h",
            "text Not analyzed",
            "text -",
            "text - · 1h",
            "text Analysis failed",
            "text -",
            "text - · 1h",
            "text Only on this phone",
            "text Recordings",
            "text Trends",
        )

    val TrendsSemantics =
        listOf(
            "text 7 days",
            "text 14 days",
            "text 30 days",
            "text Sleep",
            "text Sleep score",
            "desc Full screen",
            "text Average",
            "text 77",
            "desc Sleep score, average 77 over 7 recordings",
            "text Total sleep",
            "desc Full screen",
            "text Average",
            "text 7h 28m",
            "text Intended",
            "desc Total sleep, average 7h 28m over 7 recordings",
            "text Sleep schedule",
            "desc Full screen",
            "text Average bedtime",
            "text 23:12",
            "text Average wake time",
            "text 07:12",
            "text Usual bedtime",
            "text Usual wake time",
            "desc Sleep schedule, average bedtime 23:12 and average wake time 07:12 over 7 recordings",
            "text Sleep efficiency",
            "desc Full screen",
            "text Average",
            "text 89%",
            "desc Sleep efficiency, average 89% over 7 recordings",
            "text Heart",
            "text Heart rate",
            "desc Full screen",
            "text Average",
            "text 58 bpm",
            "text Lowest",
            "text 51 bpm",
            "text Average",
            "text Lowest",
            "desc Heart rate, average 58 bpm and lowest 51 bpm over 7 recordings",
            "text HRV (RMSSD)",
            "desc Full screen",
            "text Average",
            "text 53 ms",
            "desc HRV (RMSSD), average 53 ms over 7 recordings",
        )

    val NightChartSemantics =
        listOf(
            "desc Sleep stages from 14:13 to 15:24",
            "desc Heart rate from 14:13 to 14:21, between 56 and 66 bpm",
            "text Awake",
            "text 6m",
            "text 8%",
            "text REM",
            "text 20m",
            "text 28%",
            "text Light",
            "text 30m",
            "text 42%",
            "text Deep",
            "text 15m",
            "text 21%",
        )
  }
}
