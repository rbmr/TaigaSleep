package com.rbmr.taigasleep.recording

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.tracker.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  }

  @Test
  fun `the end of the window is armed as an alarm clock`() {
    WakeAlarm.scheduleEnd(context, END_MS)

    val scheduled = shadowOf(alarms).scheduledAlarms.single()
    assertEquals(END_MS, scheduled.triggerAtMs)
    val clock = scheduled.alarmClockInfo!!
    assertEquals(END_MS, clock.triggerTime)
    assertNotNull("an alarm clock carries the app it opens", clock.showIntent)

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
  fun `the end of the window starts the ringing service with no recording service running`() {
    WakeAlarmReceiver().onReceive(context, Intent(context, WakeAlarmReceiver::class.java))

    val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
    assertEquals(WakeAlarmService::class.java.name, started.component?.className)
    assertEquals(WakeAlarmService.ACTION_RING, started.action)
    assertEquals(
        WakeAlarmService.SOURCE_WINDOW_END,
        started.getStringExtra(WakeAlarmService.EXTRA_SOURCE),
    )
    assertEquals(
        WakeReason.WINDOW_END.wire,
        started.getStringExtra(WakeAlarmService.EXTRA_REASON),
    )
  }

  @Test
  fun `the stop action goes to the ringing service`() {
    val intent = shadowOf(WakeAlarm.stop(context)).savedIntent

    assertEquals(WakeAlarmService::class.java.name, intent.component?.className)
    assertEquals(WakeAlarmService.ACTION_STOP, intent.action)
  }

  @Test
  fun `the test action starts the same ringing service for a few seconds`() {
    WakeAlarm.testResult.value = AlarmTestResult(started = true, volume = 5, maxVolume = 10)

    WakeAlarm.test(context)

    assertNull("a new test clears what the old one reported", WakeAlarm.testResult.value)
    val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
    assertEquals(WakeAlarmService.ACTION_RING, started.action)
    assertEquals(WakeAlarmService.SOURCE_TEST, started.getStringExtra(WakeAlarmService.EXTRA_SOURCE))
    assertTrue(started.getIntExtra(WakeAlarmService.EXTRA_TEST_MS, 0) > 0)
  }

  private object TestHooks : RecordingHooks {
    override fun mayStart(context: Context, mode: RecordingMode) = true

    override fun readinessSummary(context: Context) = "test readiness"

    override fun onRecordingFinished(context: Context, recordingId: String) = Unit

    override fun onFetchCompleted(context: Context, recordingId: String) = Unit
  }

  private companion object {
    const val END_MS = 1_792_798_200_000L
  }
}
