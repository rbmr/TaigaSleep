package com.rbmr.taigasleep.recording

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.MainActivity
import com.rbmr.taigasleep.tracker.RecordingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The alarm at the end of the window: an alarm clock the system holds, so it fires even when the
 * recording service and the process are gone, and it can be dropped again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeAlarmTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val alarms = context.getSystemService(AlarmManager::class.java)

  @Before
  fun installHooks() {
    RecordingHooks.install(TestHooks)
    RecordingPrefs.get(context).clearArmedWake()
    RecordingPrefs.get(context).activeSessionId = null
    WakeAlarmSettings.get(context).endRecordingOnAlarm = EndRecordingOnAlarm.OPTIONAL
  }

  @After
  fun clearRecording() {
    RecordingPrefs.get(context).activeSessionId = null
  }

  @Test
  fun `the end of the window is armed as an alarm clock`() {
    WakeAlarm.scheduleEnd(context, END_MS)

    val scheduled = shadowOf(alarms).scheduledAlarms.single()
    assertEquals(END_MS, scheduled.triggerAtMs)
    val clock = scheduled.alarmClockInfo!!
    assertEquals(END_MS, clock.triggerTime)
    assertNotNull("an alarm clock carries the screen it opens", clock.showIntent)

    WakeAlarm.cancelEnd(context)
    assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
  }

  @Test
  fun `dismissing the alarm drops the end alarm of a spent window`() {
    WakeAlarm.scheduleEnd(context, END_MS)
    assertEquals(1, shadowOf(alarms).scheduledAlarms.size)

    // The window has rung, so nothing is armed any more: the alarm itself goes too.
    RecordingPrefs.get(context).clearArmedWake()
    WakeAlarmReceiver()
        .onReceive(
            context,
            Intent(context, WakeAlarmReceiver::class.java)
                .setAction(WakeAlarmReceiver.ACTION_DISMISS),
        )

    assertTrue("a dismissed alarm is not left scheduled", shadowOf(alarms).scheduledAlarms.isEmpty())
  }

  @Test
  fun `the alarm offers the actions of the setting while a recording runs`() {
    RecordingPrefs.get(context).activeSessionId = RECORDING_ID
    val expected =
        mapOf(
            EndRecordingOnAlarm.ALWAYS to listOf(WakeAlarmAction.STOP),
            EndRecordingOnAlarm.OPTIONAL to
                listOf(WakeAlarmAction.STOP, WakeAlarmAction.DISMISS),
            EndRecordingOnAlarm.NEVER to listOf(WakeAlarmAction.DISMISS),
        )

    for ((setting, actions) in expected) {
      WakeAlarmSettings.get(context).endRecordingOnAlarm = setting
      assertEquals("$setting", actions, WakeAlarm.actions(context))
    }
  }

  @Test
  fun `without a recording the alarm offers only dismiss`() {
    WakeAlarmSettings.get(context).endRecordingOnAlarm = EndRecordingOnAlarm.ALWAYS

    assertEquals(listOf(WakeAlarmAction.DISMISS), WakeAlarm.actions(context))
  }

  @Test
  fun `the screen is told why the alarm rang`() {
    val signal = WakeAlarm.reason(Intent().putExtra(WakeAlarm.EXTRA_WAKE_REASON, "signal"))
    assertEquals(WakeReason.SIGNAL, signal)

    val end = WakeAlarm.reason(Intent().putExtra(WakeAlarm.EXTRA_WAKE_REASON, "window_end"))
    assertEquals(WakeReason.WINDOW_END, end)
  }

  private object TestHooks : RecordingHooks {
    override fun mayStart(context: Context, mode: RecordingMode) = true

    override fun readinessSummary(context: Context) = "test readiness"

    override fun onRecordingFinished(context: Context, recordingId: String) = Unit

    override fun onFetchCompleted(context: Context, recordingId: String) = Unit

    override fun wakeAlarmScreen(context: Context): Intent = Intent(context, MainActivity::class.java)
  }

  private companion object {
    const val END_MS = 1_792_798_200_000L
    const val RECORDING_ID = "recording-1"
  }
}
