package com.rbmr.taigasleep.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.backend.StageTimelineDto
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.insights.TrendRecording
import com.rbmr.taigasleep.insights.trendsFor
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.library.BackupPhase
import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.history.HistoryScreen
import com.rbmr.taigasleep.ui.history.HistoryUiState
import com.rbmr.taigasleep.ui.history.ServerWarning
import com.rbmr.taigasleep.ui.history.TrendsContent
import com.rbmr.taigasleep.ui.history.TrendsUiState
import com.rbmr.taigasleep.ui.home.IdleHome
import com.rbmr.taigasleep.ui.home.InactiveView
import com.rbmr.taigasleep.ui.home.StartConfig
import com.rbmr.taigasleep.ui.home.StreamOption
import com.rbmr.taigasleep.ui.home.Streams
import com.rbmr.taigasleep.ui.home.TrackerView
import com.rbmr.taigasleep.ui.home.WakeChoice
import com.rbmr.taigasleep.ui.recording.RecordingBody
import com.rbmr.taigasleep.ui.recording.RecordingDetailUiState
import com.rbmr.taigasleep.ui.settings.SettingsContent
import com.rbmr.taigasleep.ui.settings.SettingsUiState
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen that carries a label, drawn at the default, a large and the largest text size a
 * phone offers. Nothing on any of them may be cut off.
 *
 * The viewport is tall enough for a whole screen, so every part of it is composed at every text
 * size and nothing is checked only because it is scrolled out of view. The charts draw their axis
 * and date labels on a canvas rather than as text, so those are not in the tree; the room they
 * reserve is asserted in `LargeTextComponentsTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h4000dp-xhdpi")
class LargeTextScreensTest {
  @get:Rule val compose = createComposeRule()

  private var screen by mutableStateOf(Screen.HISTORY)

  private enum class Screen {
    HISTORY,
    HOME,
    SETTINGS,
    NIGHT,
    TRENDS,
  }

  @Test
  fun `no screen cuts a label off at the default text size`() = checkEveryScreen(1f)

  @Config(fontScale = 1.5f)
  @Test
  fun `no screen cuts a label off at a large text size`() = checkEveryScreen(1.5f)

  @Config(fontScale = 2f)
  @Test
  fun `no screen cuts a label off at the largest text size`() = checkEveryScreen(2f)

  private fun checkEveryScreen(fontScale: Float) {
    assertEquals("the test runs at another font scale", fontScale, compose.density.fontScale, 0.01f)
    // A recording that is uploading and an analysis that is running spin forever, so the test draws
    // the frames itself instead of waiting for an idle screen.
    compose.mainClock.autoAdvance = false
    compose.setContent { TaigaTheme { CurrentScreen() } }
    compose.mainClock.advanceTimeBy(1_000)
    for (target in Screen.entries) {
      compose.runOnIdle { screen = target }
      compose.waitForIdle()
      compose.mainClock.advanceTimeBy(1_000)
      val labels = compose.allLabels()
      val clipped = compose.clippedLabels()
      assertEquals(
          "${target.name} at font scale $fontScale: $clipped",
          emptyList<String>(),
          clipped,
      )
      // A screen that drew nothing would pass the check above for the wrong reason.
      assertTrue(
          "${target.name} at font scale $fontScale drew no labels",
          labels.size >= 8,
      )
    }
  }

  @Composable
  private fun CurrentScreen() {
    when (screen) {
      Screen.HISTORY ->
          HistoryScreen(
              state = historyState,
              onOpenRecording = { _, _, _ -> },
              onOpenServer = {},
          )
      Screen.HOME ->
          IdleHome(
              view = homeView,
              onSelectTracker = {},
              onDataSettings = {},
              onGuard = {},
              onGuardLongClick = null,
              onStart = {},
          )
      Screen.SETTINGS ->
          SettingsContent(state = settingsState, onOpen = {})
      Screen.NIGHT -> NightDetail()
      Screen.TRENDS -> TrendsContent(state = trendsState, onRangeChange = {})
    }
  }

  /** The night detail: its screen with the title, and the body of the recording it shows. */
  @Composable
  private fun NightDetail() {
    TaigaScreen(
        title = "Sun 9 Nov",
        subtitle = "Polar H10 · 23:12 – 07:04 · 7h 52m",
        onBack = {},
        scrollable = false,
    ) {
      RecordingBody(
          state = nightState,
          onUpload = {},
          onRefresh = {},
          onRetryAnalysis = {},
          onLogin = {},
          onOpenAnalysis = {},
          onOpenScore = {},
          onExpand = {},
          onExport = {},
          onDownloadRaw = {},
          onDeleteRaw = {},
          onDelete = {},
          modifier = Modifier.weight(1f),
      )
    }
  }

  @Test
  fun `a history row keeps its height at the default text size`() {
    compose.setContent {
      TaigaTheme {
        HistoryScreen(
            state = HistoryUiState(items = listOf(night("a", 1, RecordingStatus.READY, 78.0))),
            onOpenRecording = { _, _, _ -> },
            onOpenServer = {},
        )
      }
    }
    compose.waitForIdle()

    assertEquals(RowHeightAtDefaultText, heightOfRow(score = "78"))
  }

  @Config(fontScale = 2f)
  @Test
  fun `a history row grows with its text instead of cutting it off`() {
    compose.setContent {
      TaigaTheme {
        HistoryScreen(
            state = HistoryUiState(items = listOf(night("a", 1, RecordingStatus.READY, 78.0))),
            onOpenRecording = { _, _, _ -> },
            onOpenServer = {},
        )
      }
    }
    compose.waitForIdle()

    assertTrue(heightOfRow(score = "78") > RowHeightAtDefaultText)
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  /** The height of the History row showing [score], in dp. The row is one button. */
  private fun heightOfRow(score: String): Float {
    val node = compose.onNodeWithText(score).fetchSemanticsNode()
    return with(compose.density) { node.size.height.toDp().value }
  }

  private val zone: ZoneId = ZoneId.systemDefault()

  private val stages =
      listOf(
          StageSegmentDto(stage = "wake", count = 12),
          StageSegmentDto(stage = "rem", count = 40),
          StageSegmentDto(stage = "light", count = 60),
          StageSegmentDto(stage = "deep", count = 30),
      )

  /** The start of a night [daysAgo] nights ago, at 23:12 local time. */
  private fun nightStart(daysAgo: Long): Long =
      LocalDate.now(zone)
          .minusDays(daysAgo)
          .atTime(23, 12)
          .atZone(zone)
          .toInstant()
          .toEpochMilli()

  private fun night(
      key: String,
      daysAgo: Long,
      status: RecordingStatus,
      score: Double? = null,
      progress: Float = 0f,
      failure: UploadFailure? = null,
  ): RecordingView {
    val start = nightStart(daysAgo)
    val end = start + 8 * 3_600_000L
    val analysis =
        score?.let {
          Analysis(
              id = "analysis-$key",
              algorithm = "wav2sleep",
              version = "1.2",
              status = AnalysisStatus.DONE,
              segments = stages,
              sleepScore = it,
          )
        }
    return RecordingView(
        key = key,
        localId = key,
        remoteId = "remote-$key",
        startUtcMs = start,
        endUtcMs = end,
        durationMs = end - start,
        device = "Polar H10",
        status = status,
        progress = progress,
        failure = failure,
        errorDetail = null,
        warning = null,
        analyses = listOfNotNull(analysis),
        selectedAnalysisId = analysis?.id,
        backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
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
                night("a", 1, RecordingStatus.READY, score = 78.0),
                night("b", 2, RecordingStatus.UPLOADING, progress = 0.42f),
                night("c", 3, RecordingStatus.NOT_ANALYZED),
                night("d", 4, RecordingStatus.FAILED, failure = UploadFailure.ANALYSIS),
                night("e", 5, RecordingStatus.LOCAL_ONLY),
            ),
        serverWarning = ServerWarning.UNREACHABLE,
    )
  }

  private val homeView: InactiveView by lazy {
    InactiveView(
        tracker = TrackerView(name = "Polar H10", connection = ConnectionState.CONNECTED, battery = 82),
        config =
            StartConfig(
                streams = Streams(hr = true, ecg = true, acc = true),
                modes = setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE),
                mode = RecordingMode.STREAM,
                canRecordAcc = true,
                wake = WakeChoice(startMinute = 6 * 60 + 30, lengthMinutes = 30),
                options =
                    listOf(
                        StreamOption("hr", StreamType.HEART_RATE),
                        StreamOption("rr", StreamType.RR_INTERVAL),
                        StreamOption("ecg", StreamType.ECG),
                        StreamOption("acc", StreamType.ACCELEROMETER),
                    ),
            ),
        guard = HomeState.READY,
        denied = false,
        error = null,
    )
  }

  private val settingsState: SettingsUiState by lazy {
    SettingsUiState(
        serverStatus = RemoteStatus.Connected(email = "someone@example.com", checkedAtMs = null),
        serverHost = "sleep.example.com",
        serverEmail = "someone@example.com",
        serverSet = true,
        tokenCount = "2",
        autoUpload = true,
        autoAnalysis = false,
        personalValue = "3 of 5 set",
    )
  }

  private fun number(id: String, label: String, group: String, unit: String, value: Double) =
      ResultItemDto(
          kind = "number",
          id = id,
          label = label,
          group = group,
          unit = unit,
          value = value,
          explanation = "$label, explained.",
      )

  private val nightState: RecordingDetailUiState by lazy {
    val view = night("night", 1, RecordingStatus.READY, score = 78.0)
    val result =
        AnalysisResultDto(
            contract_version = 7,
            model = "wav2sleep",
            model_version = "1.2",
            stages =
                StageTimelineDto(
                    epoch_seconds = 30,
                    start_unix_ms = view.startUtcMs,
                    count = stages.sumOf { it.count },
                    segments = stages,
                ),
            items =
                listOf(
                    number("total_sleep_s", "Total sleep", "sleep", "s", 27_600.0),
                    number("sleep_efficiency", "Sleep efficiency", "sleep", "%", 88.0),
                    number("hr_mean_bpm", "Average heart rate", "heart", "bpm", 54.0),
                    number("hrv_rmssd_ms", "HRV (RMSSD)", "heart", "ms", 52.0),
                    number("movement_index", "Movement index", "movement", "count", 26.0),
                    number("scored_duration_s", "Scored duration", "quality", "s", 28_000.0),
                    ResultItemDto(
                        kind = "score",
                        id = "data_quality",
                        label = "Data quality",
                        group = "score",
                        value = 88.0,
                    ),
                ),
            warnings = listOf("Two hours of the night had poor signal."),
        )
    RecordingDetailUiState(
        recording = view,
        result =
            AnalysisDto(
                id = "analysis-night",
                recording = "remote-night",
                algorithm = "wav2sleep",
                version = "1.2",
                status = "succeeded",
                result = result,
            ),
        loading = false,
        connected = true,
    )
  }

  private val trendsState: TrendsUiState by lazy {
    val today = LocalDate.now(zone)
    val nights =
        (6L downTo 0L).map { daysAgo ->
          val start = nightStart(daysAgo)
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
                zone = zone,
                usualBedtime = LocalTime.of(23, 0),
                usualWakeTime = LocalTime.of(7, 0),
            ),
    )
  }

  private companion object {
    /** The height a History row has always had: 46 dp of text, its gaps and its bar. */
    const val RowHeightAtDefaultText = 90f
  }
}
