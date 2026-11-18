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
  private val ringing = mutableListOf<Boolean>()
  private val stopped = mutableListOf<String>()
  private val session =
      WakeAlarmSession(
          ring,
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
