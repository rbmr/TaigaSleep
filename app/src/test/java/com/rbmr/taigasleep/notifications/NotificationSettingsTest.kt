package com.rbmr.taigasleep.notifications

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app's own switches: every optional type starts on, a choice is kept, and a required type has
 * no switch to store. Android's settings are never written here, so a blocked channel does not
 * change what is stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSettingsTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Before
  fun clear() {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun `every optional type starts on`() {
    val settings = NotificationSettings(context)

    NotificationType.entries
        .filter { !it.required }
        .forEach { assertTrue(it.name, settings.isOn(it)) }
  }

  @Test
  fun `turning an optional type off is kept for the next read`() {
    NotificationSettings(context).setOn(NotificationType.ANALYSES, false)

    assertFalse(NotificationSettings(context).isOn(NotificationType.ANALYSES))
    assertTrue(NotificationSettings(context).isOn(NotificationType.UPLOADS))
    assertTrue(NotificationSettings(context).isOn(NotificationType.PHONE_STORAGE))
  }

  @Test
  fun `an optional type can be turned on again`() {
    val settings = NotificationSettings(context)
    settings.setOn(NotificationType.TRACKER_BATTERY, false)

    settings.setOn(NotificationType.TRACKER_BATTERY, true)

    assertTrue(settings.isOn(NotificationType.TRACKER_BATTERY))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `a required type has no stored switch to read`() {
    NotificationSettings(context).isOn(NotificationType.RECORDING)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `a required type has no stored switch to write`() {
    NotificationSettings(context).setOn(NotificationType.WAKE_ALARM, false)
  }
}
