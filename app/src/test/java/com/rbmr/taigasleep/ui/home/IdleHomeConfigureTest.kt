package com.rbmr.taigasleep.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Configure row of Home before a recording: shown from the start, disabled until a tracker is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class IdleHomeConfigureTest {
  @get:Rule val compose = createComposeRule()

  private val view = mutableStateOf(noTracker)

  private val connected =
      InactiveView(
          tracker = TrackerView("pulse", "Pulse tracker", ConnectionState.CONNECTED, battery = 74),
          config =
              StartConfig(
                  streams = Streams(setOf("hr")),
                  modes = setOf(RecordingMode.STREAM),
                  mode = RecordingMode.STREAM,
                  canRecordAcc = false,
                  options = listOf(StreamOption("hr", StreamType.HEART_RATE)),
              ),
          guard = HomeState.READY,
          denied = false,
          error = null,
      )

  @Test
  fun `the Configure row is shown disabled before a tracker is connected`() {
    show(noTracker)

    compose.onNodeWithText("Configure").assertIsNotEnabled()
    compose.onNodeWithText("Connect a tracker first").assertExists()
  }

  @Test
  fun `tapping the disabled Configure row does nothing`() {
    var configured = false
    show(noTracker, onDataSettings = { configured = true })

    compose.onNodeWithText("Configure").performTouchInput { click() }

    assertFalse("a disabled row opens nothing", configured)
  }

  @Test
  fun `connecting a tracker enables the Configure row without moving the rows around it`() {
    show(noTracker)
    val configureBefore = boundsOf("Configure")
    val guardBefore = boundsOf("Preventing shutdown")

    compose.runOnIdle { view.value = connected }
    compose.waitForIdle()

    compose.onNodeWithText("Configure").assertIsEnabled()
    compose.onNodeWithText("Heart rate").assertExists()
    assertEquals("the Configure row stays in place", configureBefore, boundsOf("Configure"))
    assertEquals("the row below stays in place", guardBefore, boundsOf("Preventing shutdown"))
  }

  private fun show(view: InactiveView, onDataSettings: () -> Unit = {}) {
    this.view.value = view
    compose.setContent {
      TaigaTheme {
        IdleHome(
            view = this.view.value,
            onSelectTracker = {},
            onDataSettings = onDataSettings,
            onGuard = {},
            onGuardLongClick = null,
            onStart = {},
        )
      }
    }
  }

  private fun boundsOf(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

  private companion object {
    val noTracker =
        InactiveView(
            tracker = null,
            config = null,
            guard = HomeState.READY,
            denied = false,
            error = null,
        )
  }
}
