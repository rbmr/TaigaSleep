package com.rbmr.taigasleep.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service's ringing lifecycle: a ring, its stop, and another ring on the same service
 * instance. The Android side is replaced by the same fakes the ring test uses.
 */
class WakeAlarmSessionTest {
  private val phone = FakePhone()
  private val player = FakePlayer()
  private val vibrator = FakeVibrator()
  private val notifications = FakeNotifications()
  private val ring = WakeAlarmRing(phone, player, vibrator, notifications, log = { _, _ -> })
  private val timers = FakeTimers()
  private val ringing = mutableListOf<Boolean>()
  private val stopped = mutableListOf<String>()
  private val session =
      WakeAlarmSession(
          ring,
          timers,
          onRinging = { ringing += it },
          onStopped = { stopped += it },
      )

  @Test
  fun `a ring after a stop is a new ring and can be stopped again`() {
    session.start(WakeReason.SIGNAL, "signal")
    assertTrue(session.ringing)
    assertTrue(session.stop("user"))
    assertFalse(session.ringing)

    session.start(WakeReason.WINDOW_END, "window_end")
    assertTrue("a start after a stop has to be stoppable again", session.ringing)
    assertTrue(session.stop("user"))

    assertEquals(listOf("user", "user"), stopped)
    assertEquals(listOf(true, false, true, false), ringing)
    assertEquals(2, player.plays)
    assertEquals(2, player.stops)
    assertEquals(2, notifications.cancels)
  }

  @Test
  fun `a stop with nothing ringing ends nothing`() {
    assertFalse(session.stop("user"))

    assertTrue(stopped.isEmpty())
    assertTrue(ringing.isEmpty())
    assertEquals(0, notifications.cancels)
  }

  @Test
  fun `a test request while a real alarm rings is ignored`() {
    session.start(WakeReason.SIGNAL, "signal")

    val ignored = session.start(WakeReason.WINDOW_END, "test", testMs = 3_000)

    assertEquals(null, ignored)
    assertEquals(AlarmPhase.RINGING, session.phase)
    assertEquals("the real ring is not started again", 1, player.plays)
    assertTrue("no test stop is scheduled", timers.tasks.none { it.delayMs == 3_000L })
  }

  @Test
  fun `a real alarm cancels a pending test stop and wins`() {
    session.start(WakeReason.WINDOW_END, "test", testMs = 3_000)
    assertEquals(AlarmPhase.TEST, session.phase)
    val testStop = timers.tasks.single { it.delayMs == 3_000L }

    session.start(WakeReason.SIGNAL, "signal")

    assertEquals(AlarmPhase.RINGING, session.phase)
    assertTrue("the test timer is cancelled", testStop.cancelled)
    assertEquals("the test ring is ended, then the real one starts", 2, player.plays)
    assertEquals(1, player.stops)
  }

  @Test
  fun `the test stops itself after the requested time`() {
    session.start(WakeReason.WINDOW_END, "test", testMs = 3_000)

    timers.tasks.single { it.delayMs == 3_000L }.action()

    assertFalse(session.ringing)
    assertEquals(listOf("test"), stopped)
    assertEquals(1, player.stops)
  }

  @Test
  fun `the alarm stops itself after the maximum ring time`() {
    session.start(WakeReason.SIGNAL, "signal")

    timers.tasks.single { it.delayMs == WakeAlarmSession.MAX_RING_MS }.action()

    assertFalse(session.ringing)
    assertEquals(listOf("timeout"), stopped)
    assertEquals(1, player.stops)
    assertEquals(1, notifications.cancels)
  }

  private class FakeTimers : AlarmTimers {
    inner class Task(val delayMs: Long, val action: () -> Unit) : AlarmCancel {
      var cancelled = false

      override fun cancel() {
        cancelled = true
      }
    }

    val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, action: () -> Unit): AlarmCancel =
        Task(delayMs, action).also { tasks += it }
  }

  private class FakePhone : AlarmEnvironment {
    var volume = 10
    var max = 10

    override val ringerMode = RingerMode.NORMAL
    override val interruptionFilter = AlarmEnvironment.INTERRUPTION_FILTER_ALL
    override val alarmVolume: Int
      get() = volume
    override val alarmMaxVolume: Int
      get() = max
    override val hasOutput = true

    override fun setAlarmVolume(volume: Int): Boolean {
      this.volume = volume
      return true
    }

    override fun requestFocus() = true

    override fun releaseFocus() = Unit
  }

  private class FakePlayer : AlarmSoundPlayer {
    var plays = 0
    var stops = 0

    override fun play(sound: AlarmSound): Boolean {
      plays++
      return true
    }

    override fun stopSound() {
      stops++
    }
  }

  private class FakeVibrator : AlarmVibrator {
    override fun startVibration() = true

    override fun stopVibration() = Unit
  }

  private class FakeNotifications : AlarmNotifications {
    var cancels = 0

    override fun allowed() = true

    override fun post(reason: WakeReason, fallback: Boolean) = true

    override fun cancel() {
      cancels++
    }
  }
}
