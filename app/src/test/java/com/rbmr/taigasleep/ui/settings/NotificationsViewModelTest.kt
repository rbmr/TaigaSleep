package com.rbmr.taigasleep.ui.settings

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.rbmr.taigasleep.notifications.NotificationAccess
import com.rbmr.taigasleep.notifications.NotificationSettings
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
 * Settings → Notifications: the app's switches and Android's settings together. Turning a switch
 * off only stores the app's choice; turning one on while Android blocks the type sends the user to
 * the matching Android screen instead of storing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationsViewModelTest {
  private val application: Application = RuntimeEnvironment.getApplication()
  private val context: Context = application
  private val access = FakeAccess()
  private val openedApp = mutableListOf<String>()
  private val openedChannels = mutableListOf<String>()
  private lateinit var settings: NotificationSettings

  @Before
  fun setUp() {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    settings = NotificationSettings(context)
    access.allowed = true
    access.channels.clear()
    openedApp.clear()
    openedChannels.clear()
  }

  @Test
  fun `the screen lists every type, the required ones first`() {
    val model = model()

    assertEquals(NotificationType.entries, model.state.value.rows.map { it.type })
  }

  @Test
  fun `every optional switch is on when Android allows it`() {
    val model = model()

    model.state.value.rows.filter { it.hasSwitch }.forEach { assertTrue(it.type.name, it.on) }
  }

  @Test
  fun `a blocked channel shows its switch off while the preference stays stored`() {
    settings.setOn(NotificationType.UPLOADS, true)
    access.channels[NotificationType.UPLOADS.channelId] = false
    val model = model()

    assertFalse(row(model, NotificationType.UPLOADS).on)
    assertTrue(settings.isOn(NotificationType.UPLOADS))
  }

  @Test
  fun `a blocked app shows every switch off and says so once at the top`() {
    access.allowed = false
    val model = model()

    assertTrue(model.state.value.appBlocked)
    model.state.value.rows.filter { it.hasSwitch }.forEach { assertFalse(it.type.name, it.on) }
  }

  @Test
  fun `without a block the screen says nothing at the top`() {
    assertFalse(model().state.value.appBlocked)
  }

  @Test
  fun `turning a switch off stores the preference and opens nothing`() {
    val model = model()

    model.setOn(NotificationType.ANALYSES, false)

    assertFalse(settings.isOn(NotificationType.ANALYSES))
    assertFalse(row(model, NotificationType.ANALYSES).on)
    assertTrue(openedApp.isEmpty())
    assertTrue(openedChannels.isEmpty())
  }

  @Test
  fun `turning a switch on for an allowed type stores the preference`() {
    settings.setOn(NotificationType.UPLOADS, false)
    val model = model()

    model.setOn(NotificationType.UPLOADS, true)

    assertTrue(settings.isOn(NotificationType.UPLOADS))
    assertTrue(row(model, NotificationType.UPLOADS).on)
    assertTrue(openedApp.isEmpty())
    assertTrue(openedChannels.isEmpty())
  }

  @Test
  fun `turning on a channel-blocked type opens the channel settings and stores nothing`() {
    settings.setOn(NotificationType.UPLOADS, false)
    access.channels[NotificationType.UPLOADS.channelId] = false
    val model = model()

    model.setOn(NotificationType.UPLOADS, true)

    assertEquals(listOf(NotificationType.UPLOADS.channelId), openedChannels)
    assertTrue("the app's own settings are not the answer here", openedApp.isEmpty())
    assertFalse("a blocked type is not stored as on", settings.isOn(NotificationType.UPLOADS))
    assertFalse(row(model, NotificationType.UPLOADS).on)
  }

  @Test
  fun `turning on a type while the app is blocked opens the app settings and stores nothing`() {
    settings.setOn(NotificationType.PHONE_STORAGE, false)
    access.allowed = false
    val model = model()

    model.setOn(NotificationType.PHONE_STORAGE, true)

    assertEquals(listOf("app"), openedApp)
    assertTrue(openedChannels.isEmpty())
    assertFalse(settings.isOn(NotificationType.PHONE_STORAGE))
  }

  @Test
  fun `a change in Android's settings shows the next time the screen is read`() {
    settings.setOn(NotificationType.TRACKER_BATTERY, true)
    access.channels[NotificationType.TRACKER_BATTERY.channelId] = false
    val model = model()
    assertFalse(row(model, NotificationType.TRACKER_BATTERY).on)

    access.channels[NotificationType.TRACKER_BATTERY.channelId] = true
    model.refresh()

    assertTrue(row(model, NotificationType.TRACKER_BATTERY).on)
  }

  @Test
  fun `a row opens its channel's Android settings`() {
    val model = model()

    model.openSettings(NotificationType.WAKE_ALARM)

    assertEquals(listOf(NotificationType.WAKE_ALARM.channelId), openedChannels)
    assertTrue(openedApp.isEmpty())
  }

  @Test
  fun `a row opens the app's Android settings when all notifications are off`() {
    access.allowed = false
    val model = model()

    model.openSettings(NotificationType.RECORDING)

    assertEquals(listOf("app"), openedApp)
    assertTrue(openedChannels.isEmpty())
  }

  @Test
  fun `the block's button opens the app's Android settings`() {
    model().openAppNotificationSettings()

    assertEquals(listOf("app"), openedApp)
  }

  @Test
  fun `the Android intents name the app and, for a channel, its id`() {
    openNotificationSettings(context, null)
    val app = shadowOf(application).nextStartedActivity
    assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, app.action)
    assertEquals(context.packageName, app.getStringExtra(Settings.EXTRA_APP_PACKAGE))

    openNotificationSettings(context, NotificationType.UPLOADS.channelId)
    val channel = shadowOf(application).nextStartedActivity
    assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
    assertEquals(context.packageName, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    assertEquals(
        NotificationType.UPLOADS.channelId,
        channel.getStringExtra(Settings.EXTRA_CHANNEL_ID),
    )
  }

  @Test
  fun `a required type has no switch and cannot be turned off`() {
    val model = model()

    val required = model.state.value.rows.filter { it.type.required }
    assertTrue(required.isNotEmpty())
    required.forEach { assertFalse(it.type.name, it.hasSwitch) }

    model.setOn(NotificationType.RECORDING, false)

    assertTrue(openedApp.isEmpty())
    assertTrue(openedChannels.isEmpty())
    assertTrue(row(model, NotificationType.RECORDING).on)
  }

  private fun model() =
      NotificationsViewModel(
          settings = settings,
          access = access,
          openAppSettings = { openedApp += "app" },
          openChannelSettings = { openedChannels += it },
      )

  private fun row(
      model: NotificationsViewModel,
      type: NotificationType,
  ): NotificationTypeState = model.state.value.rows.first { it.type == type }

  /** Android's answer, set by the test instead of the phone. */
  private class FakeAccess(
      var allowed: Boolean = true,
      val channels: MutableMap<String, Boolean> = mutableMapOf(),
  ) : NotificationAccess {
    override fun appAllowed(): Boolean = allowed

    override fun channelAllowed(channelId: String): Boolean = channels[channelId] ?: true
  }
}
