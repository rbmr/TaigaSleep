package com.rbmr.taigasleep.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The batched results notification: one id for every upload and analysis outcome, updated in place,
 * naming only the types that are on, and counting since it was last opened or dismissed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultNotificationsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val settings = NotificationSettings(context)
  private val access = FakeAccess()

  private fun results() = ResultNotifications(context, settings, access)

  private fun notification(): Notification? =
      shadowOf(manager).getNotification(Notifier.RESULTS_ID)

  private fun text(): String? =
      notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

  @Test
  fun `several uploads and analyses update one notification`() {
    results().uploadFinished()
    results().uploadFinished()
    results().analysisFinished()
    results().uploadFailed()

    assertEquals("one notification for every outcome", 1, shadowOf(manager).size())
    assertEquals(
        "2 recordings uploaded · 1 analysis finished · 1 upload failed",
        text(),
    )
    assertEquals(
        context.getString(R.string.results_notification_title),
        notification()!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
    )
  }

  @Test
  fun `only the counts of the types that are on are listed`() {
    settings.setOn(NotificationType.ANALYSES, false)

    results().uploadFinished()
    results().analysisFinished()
    results().uploadFailed()

    assertEquals("1 recording uploaded · 1 upload failed", text())
  }

  @Test
  fun `only the analyses are listed when the upload switch is off`() {
    settings.setOn(NotificationType.UPLOADS, false)

    results().uploadFinished()
    results().analysisFinished()
    results().analysisFailed()

    assertEquals("1 analysis finished · 1 analysis failed", text())
  }

  @Test
  fun `nothing is posted when only a disabled type occurs`() {
    settings.setOn(NotificationType.ANALYSES, false)

    results().analysisFinished()
    results().analysisFailed()

    assertNull(notification())
    assertEquals(0, shadowOf(manager).size())
  }

  @Test
  fun `nothing is posted when only an upload occurs with its switch off`() {
    settings.setOn(NotificationType.UPLOADS, false)

    results().uploadFinished()
    results().uploadFailed()

    assertNull(notification())
    assertEquals(0, shadowOf(manager).size())
  }

  @Test
  fun `nothing is posted when only a type Android blocks occurs`() {
    access.channelAllowed = false

    results().uploadFinished()

    assertNull(notification())
    assertEquals(0, shadowOf(manager).size())
  }

  @Test
  fun `nothing is posted when the app's notifications are off`() {
    access.appAllowed = false

    results().analysisFinished()

    assertNull(notification())
  }

  @Test
  fun `dismissing the notification resets the counts`() {
    results().uploadFinished()
    val dismiss = shadowOf(notification()!!.deleteIntent!!).savedIntent

    assertEquals(ResultsReceiver.ACTION_DISMISSED, dismiss.action)
    ResultsReceiver().onReceive(context, dismiss)
    results().uploadFinished()

    assertEquals("1 recording uploaded", text())
  }

  @Test
  fun `opening the notification resets the counts`() {
    results().uploadFinished()
    results().analysisFinished()
    val tap = shadowOf(notification()!!.contentIntent!!).savedIntent

    assertTrue(tap.getBooleanExtra(Notifier.EXTRA_RESULTS_OPENED, false))
    resultsOpened(context, tap)
    results().uploadFinished()

    assertEquals("1 recording uploaded", text())
  }

  @Test
  fun `a start from anywhere else does not reset the counts`() {
    results().uploadFinished()

    resultsOpened(context, Intent(Intent.ACTION_MAIN))
    results().uploadFinished()

    assertEquals("2 recordings uploaded", text())
  }

  private class FakeAccess(
      var appAllowed: Boolean = true,
      var channelAllowed: Boolean = true,
  ) : NotificationAccess {
    override fun appAllowed(): Boolean = appAllowed

    override fun channelAllowed(channelId: String): Boolean = channelAllowed
  }
}
