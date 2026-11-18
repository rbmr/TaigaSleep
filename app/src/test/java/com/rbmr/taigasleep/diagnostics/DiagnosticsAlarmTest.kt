package com.rbmr.taigasleep.diagnostics

import android.app.AlarmManager
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

    Diagnostics.markRunning()
    val scheduled = shadowOf(alarms).scheduledAlarms.single()
    assertTrue("a wakeup that survives Doze", scheduled.isAllowWhileIdle)
    assertTrue("a future wakeup", scheduled.triggerAtMs > 0)

    // A process that recovers a recording marks itself running again and replaces the one alarm.
    Diagnostics.markRunning()
    assertEquals(1, shadowOf(alarms).scheduledAlarms.size)

    Diagnostics.markCleanShutdown("test")
    assertTrue("a clean finish cancels it", shadowOf(alarms).scheduledAlarms.isEmpty())
  }
}
