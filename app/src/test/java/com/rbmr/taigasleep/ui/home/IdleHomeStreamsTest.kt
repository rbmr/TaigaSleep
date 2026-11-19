package com.rbmr.taigasleep.ui.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.SoundChoice
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The data row of Home names the streams the tracker records, whatever they are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleHomeStreamsTest {
  @get:Rule val compose = createComposeRule()

  private val tracker = TrackerView("Pulse tracker", ConnectionState.CONNECTED, battery = 74)

  @Test
  fun `a tracker that records only pulse names it in the summary`() {
    show(
        StartConfig(
            streams = Streams(setOf("ppg")),
            modes = setOf(RecordingMode.STREAM),
            mode = RecordingMode.STREAM,
            canRecordAcc = false,
            options = listOf(StreamOption("ppg", StreamType.PPG)),
        )
    )

    compose.onNodeWithText("Pulse (PPG)").assertExists()
  }

  @Test
  fun `sound is named in the summary with the streams it records`() {
    show(
        StartConfig(
            streams = Streams(setOf("hr")),
            modes = setOf(RecordingMode.STREAM),
            mode = RecordingMode.STREAM,
            canRecordAcc = false,
            options = listOf(StreamOption("hr", StreamType.HEART_RATE)),
            sound = SoundChoice.COMPRESSED,
        )
    )

    compose.onNodeWithText("Heart rate · Sound").assertExists()
  }

  private fun show(config: StartConfig) {
    compose.setContent {
      TaigaTheme {
        IdleHome(
            view = InactiveView(tracker, config, HomeState.READY, false, null),
            onSelectTracker = {},
            onDataSettings = {},
            onGuard = {},
            onGuardLongClick = null,
            onStart = {},
        )
      }
    }
  }
}
