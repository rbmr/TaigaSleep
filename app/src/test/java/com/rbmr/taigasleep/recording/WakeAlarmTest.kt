package com.rbmr.taigasleep.recording

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.MainActivity
import com.rbmr.taigasleep.tracker.RecordingMode
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

  private object TestHooks : RecordingHooks {
    override fun mayStart(context: Context, mode: RecordingMode) = true

    override fun readinessSummary(context: Context) = "test readiness"

    override fun onRecordingFinished(context: Context, recordingId: String) = Unit

    override fun onFetchCompleted(context: Context, recordingId: String) = Unit

    override fun wakeAlarmScreen(context: Context): Intent = Intent(context, MainActivity::class.java)
  }

  private companion object {
    const val END_MS = 1_792_798_200_000L
  }
}
