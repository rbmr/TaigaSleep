package com.rbmr.taigasleep.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alarm's ringing, independent of Android: the volume floor, the alarm stream, the fallbacks,
 * the Stop and the fields the diagnostics get. The phone is a fake, so the same behaviour is
 * checked without a device.
 */
class WakeAlarmRingTest {
  private val logs = mutableListOf<Pair<String, Map<String, Any?>>>()
  private val phone = FakePhone()
  private val player = FakePlayer()
  private val vibrator = FakeVibrator()
  private val notifications = FakeNotifications()
  private val wakeLock = FakeWakeLock()
  private var now = 1_000L
  private val ring =
      WakeAlarmRing(
          phone = phone,
          player = player,
          vibrator = vibrator,
          notifications = notifications,
          log = { type, fields -> logs += type to fields },
          nowMs = { now },
          wakeLock = wakeLock,
      )

  @Test
  fun `a silent ringer still rings on the alarm stream`() {
    phone.ringer = RingerMode.SILENT
    phone.volume = 10
    phone.max = 10

    val result = ring.start(WakeReason.WINDOW_END, "signal")

    assertTrue("the alarm plays on its own stream", result.playbackStarted)
    assertEquals(AlarmSound.USER_RINGTONE, result.sound)
    assertEquals(RingerMode.SILENT, result.ringerMode)
    assertTrue("audio focus was taken", result.focusGranted)
  }

  @Test
  fun `the alarm volume at zero is raised to the floor and put back on stop`() {
    phone.volume = 0
    phone.max = 10

    val result = ring.start(WakeReason.WINDOW_END, "signal")

    assertEquals(0, result.volumeBefore)
    assertEquals(WakeAlarmRing.volumeFloor(10), result.volumeAfter)
    assertEquals(7, result.volumeAfter)
    assertEquals(7, phone.volume)
    assertTrue(result.volumeRaised)

    ring.stop("user")

    assertEquals("the stream is put back where it was", 0, phone.volume)
  }

  @Test
  fun `a volume above the floor is never lowered`() {
    phone.volume = 9
    phone.max = 10

    val result = ring.start(WakeReason.WINDOW_END, "signal")

    assertEquals(9, result.volumeBefore)
    assertEquals(9, result.volumeAfter)
    assertFalse(result.volumeRaised)
    assertEquals(9, phone.volume)
  }

  @Test
  fun `the sounds are tried in order until one starts`() {
    player.refuse = setOf(AlarmSound.USER_RINGTONE, AlarmSound.DEFAULT_ALARM)

    val result = ring.start(WakeReason.SIGNAL, "signal")

    assertTrue(result.playbackStarted)
    assertEquals(AlarmSound.BUNDLED_TONE, result.sound)
    assertEquals(
        listOf(AlarmSound.USER_RINGTONE, AlarmSound.DEFAULT_ALARM, AlarmSound.BUNDLED_TONE),
        player.tried,
    )
  }

  @Test
  fun `a playback that cannot start posts the notification path and records the failure`() {
    phone.ringer = RingerMode.SILENT
    player.refuse = AlarmSound.entries.toSet()

    val result = ring.start(WakeReason.SIGNAL, "signal")

    assertFalse(result.playbackStarted)
    assertNull(result.sound)
    assertEquals("no sound could be played", result.failure)
    assertTrue("the fallback notification sounds", notifications.fallback)
    assertTrue(result.vibrationStarted)
    assertTrue("nothing wrote wake_rang", logs.none { it.first == "wake_rang" })
    val failed = logs.single { it.first == "wake_alarm_failed" }.second
    assertEquals("no sound could be played", failed["reason"])
    assertEquals("silent", failed["ringer_mode"])
  }

  @Test
  fun `stop silences sound vibration and notification and restores the volume`() {
    phone.volume = 2
    phone.max = 10
    ring.start(WakeReason.SIGNAL, "signal")
    now += 4_500

    ring.stop("user")

    assertTrue(player.stopped)
    assertTrue(vibrator.stopped)
    assertTrue(notifications.cancelled)
    assertEquals(2, phone.volume)
    val stopped = logs.last()
    assertEquals("wake_alarm_stopped", stopped.first)
    assertEquals("user", stopped.second["how"])
    assertEquals(4_500L, stopped.second["rang_ms"])
    assertTrue(stopped.second["volume_restored"] as Boolean)
  }

  @Test
  fun `the ring holds a wake lock for the alarm and logs how long`() {
    wakeLock.heldMs = 4_500

    ring.start(WakeReason.WINDOW_END, "window_end")
    assertEquals("a bug cannot hold the lock forever", 15 * 60_000L, wakeLock.timeoutMs)
    assertTrue(wakeLock.acquired)

    ring.stop("user")

    assertTrue(wakeLock.released)
    val stopped = logs.last().second
    assertEquals("wake_alarm_stopped", logs.last().first)
    assertEquals(4_500L, stopped["wake_lock_ms"])
  }

  @Test
  fun `a second start while ringing keeps the original volume for the stop`() {
    phone.volume = 0
    phone.max = 10

    ring.start(WakeReason.SIGNAL, "signal")
    assertEquals(7, phone.volume)

    ring.start(WakeReason.WINDOW_END, "window_end")

    assertEquals("a second request does not raise again", 7, phone.volume)
    assertEquals("the sound is not started twice", 1, player.plays)
    assertEquals("the notification is not posted twice", 1, notifications.posts)
    assertTrue(logs.any { it.first == "wake_alarm_already_ringing" })

    ring.stop("user")

    assertEquals("the first ring's original volume is put back", 0, phone.volume)
  }

  @Test
  fun `the ring and its stop write the new diagnostics fields`() {
    phone.volume = 0
    phone.max = 10
    phone.ringer = RingerMode.VIBRATE
    phone.filter = AlarmEnvironment.INTERRUPTION_FILTER_ALARMS

    ring.start(WakeReason.WINDOW_END, "window_end")
    ring.stop("user")

    val alarm = logs.first { it.first == "wake_alarm" }.second
    assertEquals("window_end", alarm["source"])
    assertEquals("window_end", alarm["reason"])
    assertEquals("vibrate", alarm["ringer_mode"])
    assertEquals("alarms", alarm["dnd_filter"])
    assertEquals(0, alarm["alarm_volume_before"])
    assertEquals(7, alarm["alarm_volume_after"])
    assertEquals(10, alarm["alarm_volume_max"])
    assertEquals(true, alarm["playback_started"])
    assertEquals(true, alarm["audible"])
    assertEquals("user_ringtone", alarm["sound"])
    assertEquals(true, alarm["audio_focus"])
    assertEquals(true, alarm["notification_posted"])
    assertEquals(true, alarm["vibration_started"])
    assertTrue(logs.any { it.first == "wake_rang" })
  }

  @Test
  fun `a stream that stays silent is not audible even with playback`() {
    phone.volume = 0
    phone.max = 10
    phone.refuseRaise = true

    val result = ring.start(WakeReason.WINDOW_END, "signal")

    assertTrue("the sound is playing", result.playbackStarted)
    assertFalse("but nothing can be heard", result.audible)
    assertEquals("the alarm stream is silent", result.failure)
    assertTrue("the notification sounds as a second attempt", notifications.fallback)
    assertFalse("the sound keeps running", player.stopped)
    assertTrue("nothing wrote wake_rang", logs.none { it.first == "wake_rang" })
    val failed = logs.single { it.first == "wake_alarm_failed" }.second
    assertEquals("the alarm stream is silent", failed["reason"])
    val alarm = logs.single { it.first == "wake_alarm" }.second
    assertEquals(false, alarm["audible"])
  }

  @Test
  fun `a stream below the audible threshold is not audible`() {
    phone.volume = 1
    phone.max = 100
    phone.refuseRaise = true

    val result = ring.start(WakeReason.SIGNAL, "signal")

    assertTrue(result.playbackStarted)
    assertFalse(result.audible)
    assertTrue(notifications.fallback)
  }

  @Test
  fun `a test ring that played at zero says it could not be heard`() {
    phone.volume = 0
    phone.max = 10
    phone.refuseRaise = true

    val result = ring.start(WakeReason.WINDOW_END, "test").testResult()

    assertTrue("playback started", result.started)
    assertFalse("but it could not be heard", result.audible)
  }

  @Test
  fun `the test ring reports whether sound started and at which volume`() {
    phone.volume = 3
    phone.max = 10

    val result = ring.start(WakeReason.WINDOW_END, "test").testResult()

    assertTrue(result.started)
    assertEquals(7, result.volume)
    assertEquals(10, result.maxVolume)
  }

  @Test
  fun `a failed test ring reports that it did not start`() {
    player.refuse = AlarmSound.entries.toSet()

    val result = ring.start(WakeReason.WINDOW_END, "test").testResult()

    assertFalse(result.started)
  }

  @Test
  fun `the start check warns for total silence and for notifications off`() {
    val silent = WakeAlarmAudibility.from(totalSilence = true, notificationsOff = false, noOutput = false)
    val blocked =
        WakeAlarmAudibility.from(totalSilence = false, notificationsOff = true, noOutput = false)
    val quiet = WakeAlarmAudibility.from(totalSilence = false, notificationsOff = false, noOutput = true)

    assertEquals(setOf(WakeAlarmWarning.TOTAL_SILENCE), silent.warnings)
    assertFalse(silent.audible())
    assertEquals(setOf(WakeAlarmWarning.NOTIFICATIONS_OFF), blocked.warnings)
    assertEquals(setOf(WakeAlarmWarning.NO_OUTPUT), quiet.warnings)
    assertTrue(WakeAlarmAudibility.from(false, false, false).audible())
  }

  private class FakePhone : AlarmEnvironment {
    var ringer = RingerMode.NORMAL
    var filter = AlarmEnvironment.INTERRUPTION_FILTER_NONE
    var volume = 5
    var max = 10
    var focus = true
    var focusTaken = false
    var refuseRaise = false

    override val ringerMode: RingerMode
      get() = ringer
    override val interruptionFilter: Int
      get() = filter
    override val alarmVolume: Int
      get() = volume
    override val alarmMaxVolume: Int
      get() = max
    override val hasOutput: Boolean
      get() = true

    override fun setAlarmVolume(volume: Int): Boolean {
      if (refuseRaise) return false
      this.volume = volume
      return true
    }

    override fun requestFocus(): Boolean {
      focusTaken = true
      return focus
    }

    override fun releaseFocus() {
      focusTaken = false
    }
  }

  private class FakePlayer : AlarmSoundPlayer {
    var refuse: Set<AlarmSound> = emptySet()
    val tried = mutableListOf<AlarmSound>()
    var stopped = false
    var plays = 0

    override fun play(sound: AlarmSound): Boolean {
      tried += sound
      plays++
      return sound !in refuse
    }

    override fun stopSound() {
      stopped = true
    }
  }

  private class FakeVibrator : AlarmVibrator {
    var stopped = false

    override fun startVibration(): Boolean = true

    override fun stopVibration() {
      stopped = true
    }
  }

  private class FakeWakeLock : AlarmWakeLock {
    var acquired = false
    var released = false
    var timeoutMs = 0L
    var heldMs = 0L

    override fun acquire(timeoutMs: Long) {
      acquired = true
      this.timeoutMs = timeoutMs
    }

    override fun release(): Long {
      released = true
      return heldMs
    }
  }

  private class FakeNotifications : AlarmNotifications {
    var fallback = false
    var cancelled = false
    var posts = 0

    override fun allowed(): Boolean = true

    override fun post(reason: WakeReason, fallback: Boolean): Boolean {
      this.fallback = fallback
      posts++
      return true
    }

    override fun cancel() {
      cancelled = true
    }
  }
}
