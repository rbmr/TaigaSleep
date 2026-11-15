package com.rbmr.taigasleep.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.rbmr.taigasleep.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The tracker battery and phone storage alerts: sent once per episode, not on every reading, and
 * the storage alert removed when the space is free again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceAlertsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val settings = NotificationSettings(context)
  private val access = FakeAccess()

  private fun alerts() = DeviceAlerts(context, settings, access)

  private fun notification(id: Int): Notification? = shadowOf(manager).getNotification(id)

  @Test
  fun `the tracker battery alert is sent once until the battery recovers`() {
    alerts().trackerBattery(12, low = true)

    val first = notification(Notifier.TRACKER_BATTERY_ID)
    assertNotNull(first)
    assertEquals(
        context.getString(R.string.tracker_battery_notification_body, 12),
        first!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
    )

    // The user dismissed it; the same episode does not post another.
    Notifier.cancel(context, Notifier.TRACKER_BATTERY_ID)
    alerts().trackerBattery(11, low = true)
    assertNull(notification(Notifier.TRACKER_BATTERY_ID))

    // Recovery ends the episode, so the next drop alerts once again.
    alerts().trackerBattery(80, low = false)
    alerts().trackerBattery(10, low = true)
    assertNotNull(notification(Notifier.TRACKER_BATTERY_ID))
  }

  @Test
  fun `the phone storage alert is sent once and removed when space recovers`() {
    alerts().phoneStorage(low = true)

    assertNotNull(notification(Notifier.PHONE_STORAGE_ID))
    // A second check in the same episode is not another alert.
    Notifier.cancel(context, Notifier.PHONE_STORAGE_ID)
    alerts().phoneStorage(low = true)
    assertNull(notification(Notifier.PHONE_STORAGE_ID))

    alerts().phoneStorage(low = false)
    assertNull(notification(Notifier.PHONE_STORAGE_ID))

    alerts().phoneStorage(low = true)
    assertNotNull(notification(Notifier.PHONE_STORAGE_ID))
    assertEquals(
        context.getString(R.string.phone_storage_notification_title),
        notification(Notifier.PHONE_STORAGE_ID)!!
            .extras
            .getCharSequence(Notification.EXTRA_TITLE)
            .toString(),
    )
  }

  @Test
  fun `the storage alert is removed when space recovers`() {
    alerts().phoneStorage(low = true)
    assertNotNull(notification(Notifier.PHONE_STORAGE_ID))

    alerts().phoneStorage(low = false)

    assertNull(notification(Notifier.PHONE_STORAGE_ID))
  }

  @Test
  fun `both alerts follow their app switch`() {
    settings.setOn(NotificationType.TRACKER_BATTERY, false)
    settings.setOn(NotificationType.PHONE_STORAGE, false)

    alerts().trackerBattery(5, low = true)
    alerts().phoneStorage(low = true)

    assertNull(notification(Notifier.TRACKER_BATTERY_ID))
    assertNull(notification(Notifier.PHONE_STORAGE_ID))
  }

  @Test
  fun `both alerts follow what Android allows`() {
    access.channelAllowed = false

    alerts().trackerBattery(5, low = true)
    alerts().phoneStorage(low = true)

    assertNull(notification(Notifier.TRACKER_BATTERY_ID))
    assertNull(notification(Notifier.PHONE_STORAGE_ID))
  }

  @Test
  fun `turning a type on during an episode sends that alert once`() {
    settings.setOn(NotificationType.TRACKER_BATTERY, false)
    alerts().trackerBattery(5, low = true)
    assertNull(notification(Notifier.TRACKER_BATTERY_ID))

    settings.setOn(NotificationType.TRACKER_BATTERY, true)
    alerts().trackerBattery(5, low = true)

    assertNotNull(notification(Notifier.TRACKER_BATTERY_ID))
  }

  private class FakeAccess(
      var appAllowed: Boolean = true,
      var channelAllowed: Boolean = true,
  ) : NotificationAccess {
    override fun appAllowed(): Boolean = appAllowed

    override fun channelAllowed(channelId: String): Boolean = channelAllowed
  }
}
