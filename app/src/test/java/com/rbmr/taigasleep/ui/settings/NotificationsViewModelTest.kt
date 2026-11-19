package com.rbmr.taigasleep.ui.settings

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.rbmr.taigasleep.notifications.Channels
import com.rbmr.taigasleep.notifications.FakeNotificationAccess
import com.rbmr.taigasleep.notifications.NotificationType
import com.rbmr.taigasleep.notifications.NotificationTypeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings → Notifications: Android's channel settings are the only source. A switch shows what
 * Android allows, and flipping it or tapping the row opens the matching Android screen because the
 * app cannot change its own channels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationsViewModelTest {
  private val application: Application = RuntimeEnvironment.getApplication()
  private val context: Context = application
  private val access = FakeNotificationAccess()
  private val openedApp = mutableListOf<String>()
  private val openedChannels = mutableListOf<String>()

  @Before
  fun setUp() {
    openedApp.clear()
    openedChannels.clear()
  }

  @Test
  fun `the screen lists every type`() {
    assertEquals(NotificationType.entries, model().state.value.rows.map { it.type })
  }

  @Test
  fun `every switch is on when Android allows the app and the channels`() {
    model().state.value.rows.forEach { assertTrue(it.type.name, it.on) }
  }

  @Test
  fun `a blocked channel shows its switch off`() {
    access.block(Channels.UPLOADS)

    assertFalse(row(model(), NotificationType.UPLOADS).on)
  }

  @Test
  fun `a blocked app shows every switch off and says so once at the top`() {
    access.appAllowed = false
    val model = model()

    assertTrue(model.state.value.appBlocked)
    model.state.value.rows.forEach { assertFalse(it.type.name, it.on) }
  }

  @Test
  fun `without a block the screen says nothing at the top`() {
    assertFalse(model().state.value.appBlocked)
  }

  @Test
  fun `a change in Android's settings shows the next time the screen is read`() {
    access.block(Channels.TRACKER_BATTERY)
    val model = model()
    assertFalse(row(model, NotificationType.TRACKER_BATTERY).on)

    access.allow(Channels.TRACKER_BATTERY)
    model.refresh()

    assertTrue(row(model, NotificationType.TRACKER_BATTERY).on)
  }

  @Test
  fun `a row opens its channel's Android settings`() {
    model().openSettings(NotificationType.TRACKER_BATTERY)

    assertEquals(listOf(Channels.TRACKER_BATTERY), openedChannels)
    assertTrue(openedApp.isEmpty())
  }

  @Test
  fun `a row of a type with two channels opens the app's Android settings`() {
    model().openSettings(NotificationType.WAKE_ALARM)

    assertEquals(listOf("app"), openedApp)
    assertTrue(openedChannels.isEmpty())
  }

  @Test
  fun `a row opens the app's Android settings when all notifications are off`() {
    access.appAllowed = false

    model().openSettings(NotificationType.RECORDING)

    assertEquals(listOf("app"), openedApp)
    assertTrue(openedChannels.isEmpty())
  }

  @Test
  fun `the open settings button opens the app's Android settings`() {
    model().openAppSettings()

    assertEquals(listOf("app"), openedApp)
  }

  @Test
  fun `the Android intents name the app and, for a channel, its id`() {
    openPhoneSettings(context, null)
    val app = shadowOf(application).nextStartedActivity
    assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, app.action)
    assertEquals(context.packageName, app.getStringExtra(Settings.EXTRA_APP_PACKAGE))

    openPhoneSettings(context, Channels.UPLOADS)
    val channel = shadowOf(application).nextStartedActivity
    assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
    assertEquals(context.packageName, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    assertEquals(Channels.UPLOADS, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
  }

  private fun model() =
      NotificationsViewModel(
          access = access,
          openApp = { openedApp += "app" },
          openChannel = { openedChannels += it },
      )

  private fun row(
      model: NotificationsViewModel,
      type: NotificationType,
  ): NotificationTypeState = model.state.value.rows.first { it.type == type }
}
