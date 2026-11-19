package com.rbmr.taigasleep.ui

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.insights.TrendRecording
import com.rbmr.taigasleep.insights.trendsFor
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.library.BackupPhase
import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.recording.RecordingState
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.ui.history.HistoryScreen
import com.rbmr.taigasleep.ui.history.HistoryUiState
import com.rbmr.taigasleep.ui.history.TrendsContent
import com.rbmr.taigasleep.ui.history.TrendsUiState
import com.rbmr.taigasleep.ui.home.ActiveHome
import com.rbmr.taigasleep.ui.home.HomeUiState
import com.rbmr.taigasleep.ui.home.IdleHome
import com.rbmr.taigasleep.ui.home.InactiveView
import com.rbmr.taigasleep.ui.home.StartConfig
import com.rbmr.taigasleep.ui.home.StreamOption
import com.rbmr.taigasleep.ui.home.Streams
import com.rbmr.taigasleep.ui.home.TrackerView
import com.rbmr.taigasleep.ui.home.WakeChoice
import com.rbmr.taigasleep.ui.theme.AnimationMode
import com.rbmr.taigasleep.ui.theme.AppMotionScale
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * With animations off the app shows the end state of every animation at once, and with them on
 * every animation runs. The app's scale decides both, and Compose reads it from the coroutine
 * context of the window's recomposer, so the test composes through [AppMotionScale] as the window
 * does.
 *
 * The screens that carry motion — Home, History, Trends and the recording screen — write no
 * animated state while the app's scale is zero. Every animation writes the snapshot state it draws
 * from on every frame, so the count is zero exactly when nothing runs. The same count on Home with
 * the scale at one shows the check can see motion at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ReduceMotionTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val motion = AppMotionScale(context)

  @get:Rule val compose = createComposeRule(effectContext = motion)

  private var screen by mutableStateOf(Screen.HOME)

  private enum class Screen {
    HOME,
    HISTORY,
    TRENDS,
    RECORDING,
  }

  @Test
  fun `nothing animates on the four screens with animations off`() {
    start(AnimationMode.OFF)
    for (target in Screen.entries) {
      compose.runOnIdle { screen = target }
      compose.waitForIdle()
      compose.mainClock.advanceTimeBy(1_000)
      compose.onNodeWithText(labelOf(target)).assertExists()

      assertEquals(
          "${target.name} animated with animations off",
          0,
          animatedWrites(during = 1_000),
      )
    }
  }

  @Test
  fun `the home screen animates while the app animates`() {
    start(AnimationMode.ON)

    assertTrue("the bear's idle motion wrote nothing", animatedWrites(during = 1_000) > 0)
  }

  /** Draws [CurrentScreen] at [mode]'s scale, on a clock the test moves itself. */
  private fun start(mode: AnimationMode) {
    motion.select(mode)
    compose.mainClock.autoAdvance = false
    compose.setContent {
      val scale by motion.scale
      TaigaTheme(motionScale = scale) { CurrentScreen() }
    }
    compose.mainClock.advanceTimeBy(1_000)
    compose.waitForIdle()
  }

  /**
   * How many snapshot writes the screen makes while the clock runs for [during] ms. Every animation
   * writes the state it draws from on every frame, so a screen with nothing running writes nothing.
   */
  private fun animatedWrites(during: Long): Int {
    val writes = AtomicInteger(0)
    val observer =
        Snapshot.registerApplyObserver { changed, _ -> writes.addAndGet(changed.count { it is State<*> }) }
    try {
      var elapsed = 0L
      while (elapsed < during) {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
        elapsed += FrameMs
      }
    } finally {
      observer.dispose()
    }
    return writes.get()
  }

  @Composable
  private fun CurrentScreen() {
    when (screen) {
      Screen.HOME ->
          IdleHome(
              view = homeView,
              onSelectTracker = {},
              onDataSettings = {},
              onGuard = {},
              onGuardLongClick = null,
              onStart = {},
          )
      Screen.HISTORY ->
          HistoryScreen(
              state = HistoryUiState(items = listOf(recordedNight)),
              onOpenRecording = { _, _, _ -> },
              onOpenServer = {},
          )
      Screen.TRENDS -> TrendsContent(state = trendsState, onRangeChange = {})
      Screen.RECORDING ->
          ActiveHome(
              state = recordingState,
              onStop = {},
              onForceStop = {},
              onDiscard = {},
              onWake = {},
          )
    }
  }

  /** A label the screen draws, so a screen that rendered nothing cannot pass. */
  private fun labelOf(screen: Screen): String =
      when (screen) {
        Screen.HOME -> "Start recording"
        Screen.HISTORY -> "History"
        Screen.TRENDS -> "Sleep score"
        Screen.RECORDING -> "Recording"
      }

  private val zone: ZoneId = ZoneId.systemDefault()

  private val stages =
      listOf(
          StageSegmentDto(stage = "wake", count = 12),
          StageSegmentDto(stage = "rem", count = 40),
          StageSegmentDto(stage = "light", count = 60),
          StageSegmentDto(stage = "deep", count = 30),
      )

  private val startUtcMs: Long =
      LocalDate.now(zone).minusDays(1).atTime(23, 12).atZone(zone).toInstant().toEpochMilli()

  /** One recorded night with a finished analysis, so History draws a score and its stage bar. */
  private val recordedNight: RecordingView by lazy {
    val analysis =
        Analysis(
            id = "analysis-night",
            algorithm = "wav2sleep",
            version = "1.2",
            status = AnalysisStatus.DONE,
            segments = stages,
            sleepScore = 78.0,
        )
    RecordingView(
        key = "night",
        localId = "night",
        remoteId = "remote-night",
        startUtcMs = startUtcMs,
        endUtcMs = startUtcMs + 8 * 3_600_000L,
        durationMs = 8 * 3_600_000L,
        device = "Polar H10",
        status = RecordingStatus.READY,
        progress = 0f,
        failure = null,
        errorDetail = null,
        warning = null,
        analyses = listOf(analysis),
        selectedAnalysisId = analysis.id,
        backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
        local = null,
        remote = null,
        resultDownloaded = true,
    )
  }

  private val homeView: InactiveView by lazy {
    InactiveView(
        tracker =
            TrackerView(id = "H10", name = "Polar H10", connection = ConnectionState.CONNECTED, battery = 82),
        config =
            StartConfig(
                streams = Streams(setOf("hr", "ecg", "acc")),
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

  private val recordingState: HomeUiState.Recording by lazy {
    HomeUiState.Recording(
        CaptureStatus(
            phase = Phase.RECORDING,
            recordingId = "night",
            startUtcMs = startUtcMs,
            sensorName = "Polar H10",
            liveStreaming = true,
            state =
                RecordingState(
                    capture =
                        RecordingState.Capture(
                            id = "night",
                            phase = RecordingState.Phase.RECORDING,
                            startUtcMs = startUtcMs,
                            elapsedMs = 8 * 60 * 60 * 1_000L,
                        ),
                ),
        )
    )
  }

  private companion object {
    /** One frame of the test clock: 16 ms is 60 frames a second. */
    const val FrameMs = 16L
  }

  private val trendsState: TrendsUiState by lazy {
    val today = LocalDate.now(zone)
    val nights =
        (6L downTo 0L).map { daysAgo ->
          val start =
              LocalDate.now(zone)
                  .minusDays(daysAgo)
                  .atTime(23, 12)
                  .atZone(zone)
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
                zone = zone,
                usualBedtime = LocalTime.of(23, 0),
                usualWakeTime = LocalTime.of(7, 0),
            ),
    )
  }
}
