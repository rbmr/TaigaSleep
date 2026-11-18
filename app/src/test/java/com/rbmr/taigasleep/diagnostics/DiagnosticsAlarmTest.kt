package com.rbmr.taigasleep.diagnostics

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** The slow alarm exists only while the run is marked running; a clean finish takes it away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsAlarmTest {
  private val app: Application = RuntimeEnvironment.getApplication()
  private val alarms = app.getSystemService(AlarmManager::class.java)

  @Test
  fun `the alarm follows the run marker`() {
    Diagnostics.init(app)
    assertTrue("a process start arms nothing", shadowOf(alarms).scheduledAlarms.isEmpty())

    // A leftover alarm may still fire here: with nothing marked running it stops after this one.
    fire()
    assertTrue("a leftover alarm is not armed again", shadowOf(alarms).scheduledAlarms.isEmpty())

    Diagnostics.markRunning()
    val scheduled = shadowOf(alarms).scheduledAlarms.single()
    assertTrue("a wakeup that survives Doze", scheduled.isAllowWhileIdle)
    assertTrue("a future wakeup", scheduled.triggerAtMs > 0)

    // A process that recovers a recording marks itself running again and replaces the one alarm;
    // a firing alarm in such a run arms the next one.
    Diagnostics.markRunning()
    assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    val first = shadowOf(alarms).scheduledAlarms.single().triggerAtMs
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    fire()
    assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    assertTrue(
        "a live run arms the next alarm",
        shadowOf(alarms).scheduledAlarms.single().triggerAtMs > first,
    )

    Diagnostics.markCleanShutdown("test")
    assertTrue("a clean finish cancels it", shadowOf(alarms).scheduledAlarms.isEmpty())
  }

  /** What the manifest receiver does when the slow alarm fires. */
  private fun fire() =
      AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java))
}
