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
 * The results summaries: one notification per type, updated in place on its own channel, naming
 * only that type's outcomes, and counting since its summary was last opened or dismissed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultNotificationsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val access = FakeNotificationAccess()

  private fun results() = ResultNotifications(context, access)

  private fun notification(id: Int): Notification? = shadowOf(manager).getNotification(id)

  private fun text(id: Int): String? =
      notification(id)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

  private fun title(id: Int): String? =
      notification(id)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

  @Test
  fun `uploads share one summary and analyses another`() {
    results().uploadFinished()
    results().uploadFinished()
    results().uploadFailed()
    results().analysisFinished()
    results().analysisFailed()

    assertEquals("2 recordings uploaded · 1 upload failed", text(Notifier.UPLOAD_RESULTS_ID))
    assertEquals("1 analysis finished · 1 analysis failed", text(Notifier.ANALYSIS_RESULTS_ID))
    assertEquals(2, shadowOf(manager).size())
  }

  @Test
  fun `each summary is posted on its own channel`() {
    results().uploadFinished()
    results().analysisFinished()

    assertEquals(Channels.UPLOADS, notification(Notifier.UPLOAD_RESULTS_ID)!!.channelId)
    assertEquals(Channels.ANALYSES, notification(Notifier.ANALYSIS_RESULTS_ID)!!.channelId)
  }

  @Test
  fun `each summary carries the name of its type`() {
    results().uploadFinished()
    results().analysisFinished()

    assertEquals(
        context.getString(R.string.notification_type_uploads),
        title(Notifier.UPLOAD_RESULTS_ID),
    )
    assertEquals(
        context.getString(R.string.notification_type_analyses),
        title(Notifier.ANALYSIS_RESULTS_ID),
    )
  }

  @Test
  fun `a blocked type posts nothing and is not counted`() {
    access.block(Channels.ANALYSES)

    results().analysisFinished()
    assertNull(notification(Notifier.ANALYSIS_RESULTS_ID))

    access.allow(Channels.ANALYSES)
    results().analysisFinished()

    assertEquals("1 analysis finished", text(Notifier.ANALYSIS_RESULTS_ID))
  }

  @Test
  fun `a blocked channel leaves the other summary alone`() {
    access.block(Channels.ANALYSES)

    results().uploadFinished()
    results().analysisFinished()

    assertEquals("1 recording uploaded", text(Notifier.UPLOAD_RESULTS_ID))
    assertNull(notification(Notifier.ANALYSIS_RESULTS_ID))
  }

  @Test
  fun `nothing is posted while the app's notifications are off`() {
    access.appAllowed = false

    results().uploadFinished()
    results().analysisFinished()

    assertEquals(0, shadowOf(manager).size())
  }

  @Test
  fun `dismissing one summary resets only its counts`() {
    results().uploadFinished()
    results().analysisFinished()
    val dismiss = shadowOf(notification(Notifier.UPLOAD_RESULTS_ID)!!.deleteIntent!!).savedIntent

    assertEquals(ResultsReceiver.ACTION_DISMISSED, dismiss.action)
    assertEquals(
        NotificationType.UPLOADS.name,
        dismiss.getStringExtra(Notifier.EXTRA_RESULTS_TYPE),
    )
    ResultsReceiver().onReceive(context, dismiss)
    results().uploadFinished()

    assertEquals("1 recording uploaded", text(Notifier.UPLOAD_RESULTS_ID))
    assertEquals("1 analysis finished", text(Notifier.ANALYSIS_RESULTS_ID))
  }

  @Test
  fun `opening a summary resets every count`() {
    results().uploadFinished()
    results().analysisFinished()
    val tap = shadowOf(notification(Notifier.UPLOAD_RESULTS_ID)!!.contentIntent!!).savedIntent

    assertTrue(tap.getBooleanExtra(Notifier.EXTRA_RESULTS_OPENED, false))
    resultsOpened(context, tap)
    results().uploadFinished()
    results().analysisFinished()

    assertEquals("1 recording uploaded", text(Notifier.UPLOAD_RESULTS_ID))
    assertEquals("1 analysis finished", text(Notifier.ANALYSIS_RESULTS_ID))
  }

  @Test
  fun `a start from anywhere else does not reset the counts`() {
    results().uploadFinished()
    results().analysisFinished()

    resultsOpened(context, Intent(Intent.ACTION_MAIN))
    results().uploadFinished()
    results().analysisFinished()

    assertEquals("2 recordings uploaded", text(Notifier.UPLOAD_RESULTS_ID))
    assertEquals("2 analyses finished", text(Notifier.ANALYSIS_RESULTS_ID))
  }
}
