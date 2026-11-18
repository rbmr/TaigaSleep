package com.rbmr.taigasleep.ui.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A ringing alarm is stoppable from Home even when its notification is blocked or dismissed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleHomeAlarmTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `a ringing alarm can be stopped from Home`() {
    var stopped = false
    compose.setContent {
      TaigaTheme {
        IdleHome(
            view = InactiveView(null, null, HomeState.READY, false, null),
            onSelectTracker = {},
            onDataSettings = {},
            onGuard = {},
            onGuardLongClick = null,
            onStart = {},
            ringing = true,
            onStopAlarm = { stopped = true },
        )
      }
    }

    compose.onNodeWithText("Smart alarm is ringing").assertExists()
    compose.onNodeWithText("Stop alarm").performSemanticsAction(SemanticsActions.OnClick)

    assertTrue("Home stops the ringing alarm", stopped)
  }
}
