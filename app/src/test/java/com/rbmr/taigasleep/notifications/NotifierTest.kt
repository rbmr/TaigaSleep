package com.rbmr.taigasleep.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What each notification the app posts looks like: its text, its category, what it keeps on screen
 * and what it lets the user do. The alarm and the upload are the ones the user can see for a whole
 * night, so their flags are part of the behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifierTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val manager = context.getSystemService(NotificationManager::class.java)

  @Test
  fun `the ids stay what they were`() {
    assertEquals(1, Notifier.RECORDING_ID)
    assertEquals(2, Notifier.WAKE_ALARM_ID)
    assertEquals(4211, Notifier.UPLOAD_ID)
  }

  @Test
  fun `the recording notification shows the tracker, one status and its timer`() {
    val notification = recording("Strap · Connected", START_MS)

    assertEquals(
        context.getString(R.string.rec_notification_title),
        notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
    )
    assertEquals(
        "Strap · Connected",
        notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
    )
    assertEquals(Notification.CATEGORY_SERVICE, notification.category)
    assertEquals(START_MS, notification.`when`)
    assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertNotNull("a tap on the recording opens the app", notification.contentIntent)
  }

  @Test
  fun `a recording without a start time shows no timer`() {
    val notification = recording("Strap · Connected", 0)

    assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
  }

  @Test
  fun `the alarm repeats its sound and stops with one button`() {
    val stop = openAction()
    val notification =
        Notifier.wakeAlarm(context, "Your smart alarm is ringing", stop, Notifier.APP_ACTIVITY)

    assertEquals(Notification.CATEGORY_ALARM, notification.category)
    assertTrue(notification.flags and Notification.FLAG_INSISTENT != 0)
    assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertEquals(
        "Your smart alarm is ringing",
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
    )
    assertEquals(1, notification.actions.size)
    assertEquals(
        context.getString(R.string.wake_notification_stop),
        notification.actions.single().title,
    )
    assertEquals(stop, notification.actions.single().actionIntent)
    assertNotNull(notification.contentIntent)
  }

  @Test
  fun `the upload notification is progress that stays until the upload ends`() {
    val notification = Notifier.upload(context, Notifier.APP_ACTIVITY)

    assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
    assertEquals(
        context.getString(R.string.upload_notification_title),
        notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
    )
    assertEquals(
        context.getString(R.string.upload_notification_body),
        notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
    )
    assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertNotNull(notification.contentIntent)
  }

  @Test
  fun `posting uses the id and cancelling removes it`() {
    Notifier.post(context, Notifier.RECORDING_ID, recording("Strap · Connected", START_MS))
    assertNotNull(shadowOf(manager).getNotification(Notifier.RECORDING_ID))

    Notifier.cancel(context, Notifier.RECORDING_ID)
    assertNull(shadowOf(manager).getNotification(Notifier.RECORDING_ID))
  }

  private fun recording(text: String, startUtcMs: Long): Notification =
      Notifier.recording(context, text, startUtcMs, Notifier.APP_ACTIVITY)

  private fun openAction(): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          3,
          Intent("com.rbmr.taigasleep.test.STOP"),
          PendingIntent.FLAG_IMMUTABLE,
      )

  private companion object {
    const val START_MS = 1_792_798_200_000L
  }
}
