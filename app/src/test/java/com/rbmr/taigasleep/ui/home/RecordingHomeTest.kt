package com.rbmr.taigasleep.ui.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recording screen while the recording is stopping: Stop and Discard have had their answer,
 * and Force stop is the way out of a stop that does not finish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingHomeTest {
  @get:Rule val compose = createComposeRule()

  private fun stopping() = CaptureStatus(phase = Phase.STOPPING, liveStreaming = true)

  /** The recording screen animates, so the test draws its frames itself. */
  private fun show(
      onForceStop: () -> Unit = {},
      ringing: Boolean = false,
      onStopAlarm: () -> Unit = {},
  ) {
    compose.mainClock.autoAdvance = false
    compose.setContent {
      TaigaTheme {
        RecordingHome(
            state = HomeUiState.Recording(stopping()),
            autoUpload = false,
            onStop = {},
            onForceStop = onForceStop,
            onDiscard = {},
            onWake = {},
            ringing = ringing,
            onStopAlarm = onStopAlarm,
        )
      }
    }
    compose.mainClock.advanceTimeBy(1_000)
  }

  /**
   * Presses a control through the action it exposes. A pointer gesture needs the frames the test
   * clock controls, and the running screen never lets the clock idle.
   */
  private fun press(text: String) {
    compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
    compose.mainClock.advanceTimeBy(1_000)
  }

  @Test
  fun `force stop stays available while the recording is stopping`() {
    show()

    compose.onNodeWithText("Force stop").assertIsEnabled()
    compose.onNodeWithText("Stop and save").assertIsNotEnabled()
    compose.onNodeWithText("Discard").assertIsNotEnabled()
  }

  @Test
  fun `a ringing alarm can be stopped from the recording screen`() {
    var stopped = false
    show(ringing = true, onStopAlarm = { stopped = true })

    compose.onNodeWithText("Smart alarm is ringing").assertExists()
    press("Stop alarm")

    assertTrue("the screen stops the ringing alarm", stopped)
  }

  @Test
  fun `forcing the stop asks first and can still be called off`() {
    var forced = false
    show(onForceStop = { forced = true })

    press("Force stop")

    // The dialog is a window of its own, so it is found by what it says.
    compose.onNodeWithText("Force stop?").assertExists()
    assertFalse("the question stands between the tap and the end", forced)

    press("Cancel")
    compose.onNodeWithText("Force stop?").assertDoesNotExist()
    assertFalse(forced)
  }
}
