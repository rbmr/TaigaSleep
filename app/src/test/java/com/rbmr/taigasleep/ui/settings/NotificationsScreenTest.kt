package com.rbmr.taigasleep.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.notifications.NotificationType
import com.rbmr.taigasleep.notifications.NotificationTypeState
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings → Notifications draws one switch per type, shows Android's answer in it, and offers the
 * app's own notification settings below the list. No row carries an Allowed or Blocked pill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp-xhdpi")
class NotificationsScreenTest {
  @get:Rule val compose = createComposeRule()

  private val context = RuntimeEnvironment.getApplication()

  @Test
  fun `every type has a switch and none has a pill`() {
    show(state(allowed = true))

    assertEquals(NotificationType.entries.size, switches().size)
    for (type in NotificationType.entries) {
      compose.onNodeWithText(context.getString(type.label)).assertExists()
    }
    compose.onAllNodesWithText("Allowed").assertCountEquals(0)
    compose.onAllNodesWithText("Blocked").assertCountEquals(0)
  }

  @Test
  fun `a switch shows what Android allows`() {
    show(state(allowed = true))

    assertEquals(List(NotificationType.entries.size) { ToggleableState.On }, switchStates())
  }

  @Test
  fun `a blocked app shows the card and every switch off`() {
    show(state(appBlocked = true, allowed = false))

    compose
        .onNodeWithText(context.getString(R.string.notifications_app_blocked))
        .assertExists()
    assertEquals(List(NotificationType.entries.size) { ToggleableState.Off }, switchStates())
  }

  @Test
  fun `flipping a switch opens Android's settings for its type`() {
    val opened = mutableListOf<NotificationType>()
    show(state(allowed = true), onOpen = { opened += it })

    compose.onAllNodes(isToggleable()).onFirst().performClick()

    assertEquals(listOf(NotificationType.RECORDING), opened)
  }

  @Test
  fun `the button below the list opens the app's settings`() {
    var opened = 0
    show(state(allowed = true), onOpenSettings = { opened++ })

    compose.onNodeWithText(context.getString(R.string.notifications_open_settings)).performClick()

    assertEquals(1, opened)
  }

  private fun show(
      state: NotificationsUiState,
      onOpen: (NotificationType) -> Unit = {},
      onOpenSettings: () -> Unit = {},
  ) {
    compose.setContent {
      TaigaTheme {
        NotificationsContent(
            state = state,
            onOpen = onOpen,
            onOpenSettings = onOpenSettings,
            onBack = {},
        )
      }
    }
    compose.waitForIdle()
  }

  private fun switches() = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()

  private fun switchStates() =
      switches().map { it.config.getOrNull(SemanticsProperties.ToggleableState) }

  private fun state(appBlocked: Boolean = false, allowed: Boolean) =
      NotificationsUiState(
          appBlocked = appBlocked,
          rows = NotificationType.entries.map { NotificationTypeState(it, on = allowed) },
      )
}
