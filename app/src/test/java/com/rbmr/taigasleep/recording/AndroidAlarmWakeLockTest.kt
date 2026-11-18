package com.rbmr.taigasleep.recording

import android.content.Context
import com.rbmr.taigasleep.diagnostics.RecordingCost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The alarm's own wake lock must not appear in the cost of a recording: it is held beside the
 * recording's lock and only [WakeAlarmRing] reports it, in `wake_alarm_stopped`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmWakeLockTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val sources = FakeSources()

  @After
  fun reset() {
    RecordingCost.install(null)
  }

  @Test
  fun `the alarm wake lock is not counted as the recording's`() {
    RecordingCost.install(sources) { _, _ -> }
    RecordingCost.begin(
        recordingId = "R1",
        startedUtcMs = START_MS,
        nowUtcMs = START_MS,
        nowElapsedMs = 1_000,
        resumed = false,
    )

    val lock = AndroidAlarmWakeLock(context)
    lock.acquire(15 * 60_000L)
    lock.release()

    val fields =
        RecordingCost.heartbeatFields(
            2_000,
            batteryPct = 80,
            interactive = true,
            doze = false,
            charging = false,
        )
    assertEquals("no held time and no acquisition", listOf(0L, 0L), fields!!["w"])
  }

  private class FakeSources : RecordingCost.Sources {
    override fun batteryPercent() = 80
    override fun chargeCounterUah(): Long? = null
    override fun currentNowUa(): Long? = null
    override fun energyNwh(): Long? = null
    override fun charging() = false
    override fun processCpuMs(): Long? = null
    override fun ownThreadCpuMs(): Long? = null
    override fun gcCount(): Long? = null
    override fun gcTimeMs(): Long? = null
    override fun txBytes(): Long? = null
    override fun rxBytes(): Long? = null
    override fun thermalHeadroom(): Float? = null
  }

  private companion object {
    const val START_MS = 1_792_798_200_000L
  }
}
