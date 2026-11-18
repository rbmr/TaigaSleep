package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.diagnostics.Diagnostics
import kotlin.math.ceil

/** How the phone is set to ring, as the alarm reports it. */
enum class RingerMode(val wire: String) {
  SILENT("silent"),
  VIBRATE("vibrate"),
  NORMAL("normal"),
  UNKNOWN("unknown"),
}

/**
 * One sound the alarm can play, tried in this order. The phone's own choice comes first, then the
 * default alarm sound, then a short tone the app carries so a phone without either still rings.
 */
enum class AlarmSound(val wire: String) {
  USER_RINGTONE("user_ringtone"),
  DEFAULT_ALARM("default_alarm"),
  BUNDLED_TONE("bundled_tone"),
}

/** The audio and vibration state the alarm reads and changes while it rings. */
interface AlarmEnvironment {
  val ringerMode: RingerMode
  /** `NotificationManager.INTERRUPTION_FILTER_*`; total silence is [INTERRUPTION_FILTER_NONE]. */
  val interruptionFilter: Int
  val alarmVolume: Int
  val alarmMaxVolume: Int

  /** Raises the alarm stream; false when the phone refused. */
  fun setAlarmVolume(volume: Int): Boolean

  /** False when the phone has no output the alarm could reach. */
  val hasOutput: Boolean

  /** Takes audio focus for the alarm; false when it was refused. */
  fun requestFocus(): Boolean

  fun releaseFocus()

  companion object {
    /** `NotificationManager.INTERRUPTION_FILTER_*`; total silence is [INTERRUPTION_FILTER_NONE]. */
    const val INTERRUPTION_FILTER_ALL = 1
    const val INTERRUPTION_FILTER_PRIORITY = 2

    /** Total silence: Do Not Disturb blocks even alarms (`INTERRUPTION_FILTER_NONE`). */
    const val INTERRUPTION_FILTER_NONE = 3
    const val INTERRUPTION_FILTER_ALARMS = 4
  }
}

/** Plays one alarm sound in a loop. A test supplies a fake that can refuse. */
interface AlarmSoundPlayer {
  fun play(sound: AlarmSound): Boolean

  fun stopSound()
}

/** The alarm's vibration, on the alarm attributes so a silent profile does not stop it. */
interface AlarmVibrator {
  fun startVibration(): Boolean

  fun stopVibration()
}

/** Posts and clears the alarm's notification, which is the visible control. */
interface AlarmNotifications {
  fun allowed(): Boolean

  /**
   * Posts the notification for [reason]. [fallback] is true when no sound could be started, so the
   * notification itself has to sound and vibrate; false for the silent control the service rings.
   */
  fun post(reason: WakeReason, fallback: Boolean): Boolean

  fun cancel()
}

/** What one ring did, as the service reports it and the diagnostics record it. */
data class AlarmRing(
    val playbackStarted: Boolean,
    val sound: AlarmSound?,
    /** Why the sound did not start; null when it did. */
    val failure: String?,
    val ringerMode: RingerMode,
    val interruptionFilter: Int,
    val volumeBefore: Int,
    val volumeAfter: Int,
    val maxVolume: Int,
    val focusGranted: Boolean,
    val notificationPosted: Boolean,
    val vibrationStarted: Boolean,
) {
  /** True when the alarm raised the stream for the ring and has to put it back on stop. */
  val volumeRaised: Boolean
    get() = volumeAfter > volumeBefore

  /** What the settings' test action reports. */
  fun testResult(): AlarmTestResult =
      AlarmTestResult(playbackStarted, volumeAfter, maxVolume)
}

/** What the alarm settings report after a test ring. */
data class AlarmTestResult(val started: Boolean, val volume: Int, val maxVolume: Int)

/**
 * Rings the alarm on the alarm audio stream and with the alarm's vibration, independent of the
 * ringer's silent mode, and keeps the notification as the visible control.
 *
 * Every Android call sits behind one of the seams, so the order, the volume floor and the
 * fallbacks are testable without a phone. [WakeAlarmService] supplies the phone; the settings'
 * test action uses the same class.
 */
class WakeAlarmRing(
    private val phone: AlarmEnvironment,
    private val player: AlarmSoundPlayer,
    private val vibrator: AlarmVibrator,
    private val notifications: AlarmNotifications,
    private val log: (String, Map<String, Any?>) -> Unit = Diagnostics::log,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
  private var startedAtMs: Long? = null

  /** The volume the alarm found before it raised it, restored on stop; null when it did not raise. */
  private var restoreVolume: Int? = null

  /** The last ring, or null between stop and the next start. */
  @Volatile var current: AlarmRing? = null
    private set

  fun start(reason: WakeReason, source: String): AlarmRing {
    val before = phone.alarmVolume
    val max = phone.alarmMaxVolume
    var after = before
    if (before < volumeFloor(max) && phone.setAlarmVolume(volumeFloor(max))) {
      after = phone.alarmVolume
    }
    // The alarm may raise the stream, never lower it.
    if (after < before) after = before
    val focus = phone.requestFocus()
    var sound: AlarmSound? = null
    for (candidate in AlarmSound.entries) {
      if (player.play(candidate)) {
        sound = candidate
        break
      }
    }
    val playback = sound != null
    if (!playback) player.stopSound()
    val vibration = vibrator.startVibration()
    val notification = notifications.post(reason, fallback = !playback)
    val ring =
        AlarmRing(
            playbackStarted = playback,
            sound = sound,
            failure = if (playback) null else "no sound could be played",
            ringerMode = phone.ringerMode,
            interruptionFilter = phone.interruptionFilter,
            volumeBefore = before,
            volumeAfter = after,
            maxVolume = max,
            focusGranted = focus,
            notificationPosted = notification,
            vibrationStarted = vibration,
        )
    current = ring
    startedAtMs = nowMs()
    restoreVolume = if (after > before) before else null
    logStart(ring, source, reason, sound)
    return ring
  }

  private fun logStart(ring: AlarmRing, source: String, reason: WakeReason, sound: AlarmSound?) {
    log("wake_alarm", fields(ring, source, reason))
    if (sound != null) {
      log(
          "wake_rang",
          mapOf(
              "source" to source,
              "reason" to reason.wire,
              "sound" to sound.wire,
              "volume" to ring.volumeAfter,
              "volume_max" to ring.maxVolume,
          ),
      )
    } else {
      log(
          "wake_alarm_failed",
          mapOf(
              "source" to source,
              "reason" to ring.failure,
              "ringer_mode" to ring.ringerMode.wire,
              "dnd_filter" to dndName(ring.interruptionFilter),
              "notifications" to notifications.allowed(),
          ),
      )
    }
  }

  /** Ends the sound, the vibration and the focus, restores the volume and logs how long it rang. */
  fun stop(how: String) {
    player.stopSound()
    vibrator.stopVibration()
    phone.releaseFocus()
    notifications.cancel()
    restoreVolume?.let { phone.setAlarmVolume(it) }
    val rangMs = startedAtMs?.let { (nowMs() - it).coerceAtLeast(0L) }
    log(
        "wake_alarm_stopped",
        mapOf(
            "how" to how,
            "rang_ms" to rangMs,
            "volume_restored" to (restoreVolume != null),
        ),
    )
    startedAtMs = null
    restoreVolume = null
    current = null
  }

  private fun fields(ring: AlarmRing, source: String, reason: WakeReason) =
      mapOf(
          "source" to source,
          "reason" to reason.wire,
          "ringer_mode" to ring.ringerMode.wire,
          "dnd_filter" to dndName(ring.interruptionFilter),
          "alarm_volume_before" to ring.volumeBefore,
          "alarm_volume_after" to ring.volumeAfter,
          "alarm_volume_max" to ring.maxVolume,
          "playback_started" to ring.playbackStarted,
          "sound" to ring.sound?.wire,
          "audio_focus" to ring.focusGranted,
          "notification_posted" to ring.notificationPosted,
          "vibration_started" to ring.vibrationStarted,
      )

  companion object {
    /** The alarm stream is never left below this share of its maximum while the alarm rings. */
    const val VOLUME_FLOOR_PERCENT = 70

    fun volumeFloor(max: Int): Int = ceil(max * VOLUME_FLOOR_PERCENT / 100.0).toInt()

    /** A readable name for `NotificationManager.INTERRUPTION_FILTER_*`. */
    fun dndName(filter: Int): String =
        when (filter) {
          AlarmEnvironment.INTERRUPTION_FILTER_ALL -> "all"
          AlarmEnvironment.INTERRUPTION_FILTER_PRIORITY -> "priority"
          AlarmEnvironment.INTERRUPTION_FILTER_NONE -> "none"
          AlarmEnvironment.INTERRUPTION_FILTER_ALARMS -> "alarms"
          else -> "unknown"
        }
  }
}

/**
 * Whether the alarm can be heard at all, so the start of a recording and the alarm's own settings
 * can say so. The alarm cannot override the phone's choices, only report them.
 */
data class WakeAlarmAudibility(
    val totalSilence: Boolean,
    val notificationsOff: Boolean,
    val noOutput: Boolean,
) {
  val warnings: Set<WakeAlarmWarning>
    get() =
        buildSet {
          if (totalSilence) add(WakeAlarmWarning.TOTAL_SILENCE)
          if (notificationsOff) add(WakeAlarmWarning.NOTIFICATIONS_OFF)
          if (noOutput) add(WakeAlarmWarning.NO_OUTPUT)
        }

  fun audible(): Boolean = warnings.isEmpty()

  companion object {
    fun from(totalSilence: Boolean, notificationsOff: Boolean, noOutput: Boolean) =
        WakeAlarmAudibility(totalSilence, notificationsOff, noOutput)
  }
}

/** One reason the alarm may not be heard, as the settings show it. */
enum class WakeAlarmWarning {
  TOTAL_SILENCE,
  NOTIFICATIONS_OFF,
  NO_OUTPUT,
}
