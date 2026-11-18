@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package com.rbmr.taigasleep.recording

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.notifications.Channels
import com.rbmr.taigasleep.notifications.Notifier

/**
 * Foreground service that rings the wake-up alarm on the alarm audio stream. It is started by the
 * end-of-window alarm clock, by the live decision and by the settings' test action, so it rings
 * even when the recording service and its process are gone.
 *
 * The notification stays on screen as the visible Stop, but it is silent: the sound and the
 * vibration are this service's, taken from the alarm stream rather than the ringer.
 */
class WakeAlarmService : Service() {
  private lateinit var device: AndroidAlarmDevice
  private lateinit var ring: WakeAlarmRing
  private val handler = Handler(Looper.getMainLooper())
  private var finished = false

  override fun onCreate() {
    super.onCreate()
    device = AndroidAlarmDevice(this)
    ring =
        WakeAlarmRing(
            phone = device,
            player = device,
            vibrator = device,
            notifications = device,
        )
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> finish(intent.getStringExtra(EXTRA_HOW) ?: HOW_USER)
      ACTION_RING -> started(intent)
      else -> finish(HOW_SERVICE)
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    // A destroyed service must not leave the stream raised or the vibration running.
    if (!finished) finish(HOW_SERVICE)
    super.onDestroy()
  }

  private fun started(intent: Intent) {
    val reason = WakeReason.fromWire(intent.getStringExtra(EXTRA_REASON)) ?: WakeReason.WINDOW_END
    val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_WINDOW_END
    val testMs = intent.getIntExtra(EXTRA_TEST_MS, 0)
    val text = getString(R.string.wake_notification_text, getString(reason.text))
    val placeholder =
        Notifier.wakeAlarmControl(this, text, WakeAlarm.stop(this), Notifier.APP_ACTIVITY)
    val foreground =
        try {
          startForeground(
              Notifier.WAKE_ALARM_ID,
              placeholder,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
          )
          true
        } catch (e: Exception) {
          Log.e(TAG, "the phone refused the alarm's foreground service", e)
          false
        }
    if (!foreground) {
      // Without the foreground state the service would be killed at once; the notification path
      // still sounds and vibrates, and the failure is recorded.
      Diagnostics.log(
          "wake_alarm_failed",
          mapOf("source" to source, "reason" to "the foreground service was refused"),
      )
      Notifier.post(
          this,
          Notifier.WAKE_ALARM_ID,
          Notifier.wakeAlarm(this, text, WakeAlarm.stop(this), Notifier.APP_ACTIVITY),
      )
      finished = true
      stopSelf()
      return
    }
    val result = ring.start(reason, source)
    if (testMs > 0) {
      WakeAlarm.testResult.value = result.testResult()
      handler.postDelayed({ finish(HOW_TEST) }, testMs.toLong())
      return
    }
    report(result, source)
  }

  /** Tells a recording that is still alive whether its alarm could actually be heard. */
  private fun report(result: AlarmRing, source: String) {
    if (!RecordingService.running) return
    if (result.audible) {
      RecordingService.wakeRang(this, source)
    } else {
      RecordingService.wakeFailed(this, result.failure ?: "no sound")
    }
  }

  private fun finish(how: String) {
    if (finished) return
    finished = true
    handler.removeCallbacksAndMessages(null)
    ring.stop(how)
    WakeAlarm.dismiss(this)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  companion object {
    private const val TAG = "WakeAlarmService"

    const val ACTION_RING = "com.rbmr.taigasleep.recording.WAKE_ALARM_RING"
    const val ACTION_STOP = "com.rbmr.taigasleep.recording.WAKE_ALARM_STOP"
    const val EXTRA_REASON = "wake_reason"
    const val EXTRA_SOURCE = "wake_source"
    const val EXTRA_TEST_MS = "wake_test_ms"
    const val EXTRA_HOW = "wake_stop_how"

    const val SOURCE_WINDOW_END = "window_end"
    const val SOURCE_SIGNAL = "signal"
    const val SOURCE_TEST = "test"

    private const val HOW_USER = "user"
    private const val HOW_SERVICE = "service"
    private const val HOW_TEST = "test"
  }
}

/**
 * Reads what the phone does that could keep the alarm from being heard. The alarm reports it and
 * never overrides it.
 */
fun readWakeAlarmAudibility(context: Context): WakeAlarmAudibility {
  val app = context.applicationContext
  val audio = app.getSystemService(AudioManager::class.java)
  val notifications = app.getSystemService(NotificationManager::class.java)
  return WakeAlarmAudibility.from(
      totalSilence =
          notifications.currentInterruptionFilter == AlarmEnvironment.INTERRUPTION_FILTER_NONE,
      notificationsOff = !NotificationManagerCompat.from(app).areNotificationsEnabled(),
      noOutput = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isEmpty(),
  )
}

/**
 * The phone behind [WakeAlarmRing]: the alarm stream's volume, audio focus, the vibrator on alarm
 * attributes, the three sounds and the notification.
 */
class AndroidAlarmDevice(private val context: Context) :
    AlarmEnvironment, AlarmSoundPlayer, AlarmVibrator, AlarmNotifications {
  private val audio = context.getSystemService(AudioManager::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)
  private val vibrator: Vibrator =
      context.getSystemService(VibratorManager::class.java).defaultVibrator
  private val attributes =
      AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
  private var focus: AudioFocusRequest? = null
  private var player: MediaPlayer? = null
  private var vibration = false

  override val ringerMode: RingerMode
    get() =
        when (audio.ringerMode) {
          AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
          AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
          AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
          else -> RingerMode.UNKNOWN
        }

  override val interruptionFilter: Int
    get() = notifications.currentInterruptionFilter

  override val alarmVolume: Int
    get() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

  override val alarmMaxVolume: Int
    get() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

  override fun setAlarmVolume(volume: Int): Boolean =
      try {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        true
      } catch (e: SecurityException) {
        Log.w(TAG, "the phone refused to raise the alarm volume", e)
        false
      }

  override val hasOutput: Boolean
    get() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isNotEmpty()

  override fun requestFocus(): Boolean {
    val request =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener {}
            .build()
    focus = request
    return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  override fun releaseFocus() {
    focus?.let { audio.abandonAudioFocusRequest(it) }
    focus = null
  }

  override fun play(sound: AlarmSound): Boolean {
    player?.release()
    player = null
    return try {
      val next = open(sound) ?: return false
      next.isLooping = true
      next.start()
      player = next
      true
    } catch (e: Exception) {
      Log.w(TAG, "the alarm sound $sound could not be played", e)
      false
    }
  }

  private fun open(sound: AlarmSound): MediaPlayer? {
    return when (sound) {
      AlarmSound.USER_RINGTONE -> prepared(userRingtone())
      AlarmSound.DEFAULT_ALARM ->
          prepared(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
      AlarmSound.BUNDLED_TONE ->
          MediaPlayer.create(context, R.raw.alarm_tone, attributes, 0)
    }
  }

  /**
   * The user's alarm ringtone: the sound Android currently uses for alarms. The channel's own
   * choice comes first when the phone let the user pick one.
   */
  private fun userRingtone(): Uri? =
      notifications.getNotificationChannel(Channels.WAKE_ALARM)?.sound
          ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)

  private fun prepared(uri: Uri?): MediaPlayer? {
    if (uri == null) return null
    return MediaPlayer().apply {
      setAudioAttributes(attributes)
      setDataSource(context, uri)
      prepare()
    }
  }

  override fun stopSound() {
    player?.let {
      runCatching { it.stop() }
      it.release()
    }
    player = null
  }

  override fun startVibration(): Boolean =
      try {
        val pattern = longArrayOf(0, 800, 600)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        if (Build.VERSION.SDK_INT >= 33) {
          val vibrationAttributes =
              VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
          vibrator.vibrate(effect, vibrationAttributes)
        } else {
          vibrator.vibrate(effect, attributes)
        }
        vibration = true
        true
      } catch (e: Exception) {
        Log.w(TAG, "the alarm could not vibrate", e)
        false
      }

  override fun stopVibration() {
    if (vibration) vibrator.cancel()
    vibration = false
  }

  override fun allowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

  override fun post(reason: WakeReason, fallback: Boolean): Boolean {
    val text = context.getString(R.string.wake_notification_text, context.getString(reason.text))
    val stop = WakeAlarm.stop(context)
    val notification =
        if (fallback) Notifier.wakeAlarm(context, text, stop, Notifier.APP_ACTIVITY)
        else Notifier.wakeAlarmControl(context, text, stop, Notifier.APP_ACTIVITY)
    return Notifier.post(context, Notifier.WAKE_ALARM_ID, notification)
  }

  override fun cancel() {
    Notifier.cancel(context, Notifier.WAKE_ALARM_ID)
  }

  private companion object {
    const val TAG = "AndroidAlarmDevice"
  }
}
