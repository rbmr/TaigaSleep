package com.rbmr.taigasleep.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one row of settings → Notifications says for every combination of the app's preference,
 * the channel and the app's notification permission. The switch is on only when all three allow
 * the type; a required type ignores the preference because it has no switch.
 */
class NotificationTypeStateTest {
  @Test
  fun `an optional switch is on only when the preference and both Android settings allow it`() {
    val shown =
        listOf(true, false).flatMap { preference ->
          listOf(true, false).flatMap { channel ->
            listOf(true, false).map { app ->
              val settings = Triple(preference, channel, app)
              settings to state(NotificationType.UPLOADS, preference, app, channel)
            }
          }
        }

    assertEquals(
        listOf(
            "preference on, channel allowed, app allowed" to true,
            "preference on, channel allowed, app blocked" to false,
            "preference on, channel blocked, app allowed" to false,
            "preference on, channel blocked, app blocked" to false,
            "preference off, channel allowed, app allowed" to false,
            "preference off, channel allowed, app blocked" to false,
            "preference off, channel blocked, app allowed" to false,
            "preference off, channel blocked, app blocked" to false,
        ),
        shown.map { (settings, state) -> describe(settings) to state.on },
    )
  }

  @Test
  fun `an optional row is allowed only when Android allows the app and the channel`() {
    for (preference in listOf(true, false)) {
      for (channel in listOf(true, false)) {
        for (app in listOf(true, false)) {
          val state = state(NotificationType.ANALYSES, preference, app, channel)

          assertEquals(
              "preference=$preference channel=$channel app=$app",
              channel && app,
              state.allowed,
          )
        }
      }
    }
  }

  @Test
  fun `a required type is on whenever Android allows it, whatever the preference says`() {
    for (type in NotificationType.entries.filter { it.required }) {
      for (preference in listOf(true, false)) {
        for (channel in listOf(true, false)) {
          for (app in listOf(true, false)) {
            val state = state(type, preference, app, channel)

            assertEquals(
                "${type.name}: preference=$preference channel=$channel app=$app",
                channel && app,
                state.on,
            )
          }
        }
      }
    }
  }

  @Test
  fun `only a required type has no switch`() {
    for (type in NotificationType.entries) {
      assertEquals(type.name, !type.required, state(type, true, true, true).hasSwitch)
    }
    assertFalse(state(NotificationType.RECORDING, true, true, true).hasSwitch)
    assertFalse(state(NotificationType.WAKE_ALARM, true, true, true).hasSwitch)
    assertTrue(state(NotificationType.UPLOADS, true, true, true).hasSwitch)
    assertTrue(state(NotificationType.PHONE_STORAGE, true, true, true).hasSwitch)
  }

  @Test
  fun `the optional types are the ones after the required ones on the screen`() {
    assertEquals(
        listOf(
            NotificationType.RECORDING,
            NotificationType.WAKE_ALARM,
            NotificationType.UPLOADS,
            NotificationType.ANALYSES,
            NotificationType.TRACKER_BATTERY,
            NotificationType.PHONE_STORAGE,
        ),
        NotificationType.entries,
    )
    assertEquals(
        listOf(false, false, true, true, true, true),
        NotificationType.entries.map { !it.required },
    )
  }

  private fun state(
      type: NotificationType,
      preference: Boolean,
      app: Boolean,
      channel: Boolean,
  ) = NotificationTypeState(
      type = type,
      preferenceOn = preference,
      appAllowed = app,
      channelAllowed = channel,
  )

  private fun describe(settings: Triple<Boolean, Boolean, Boolean>): String {
    val (preference, channel, app) = settings
    return "preference ${if (preference) "on" else "off"}, " +
        "channel ${if (channel) "allowed" else "blocked"}, " +
        "app ${if (app) "allowed" else "blocked"}"
  }
}
