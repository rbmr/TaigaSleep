package com.rbmr.taigasleep.ui.alarm

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rbmr.taigasleep.recording.WakeAlarm
import com.rbmr.taigasleep.ui.theme.TaigaTheme

/**
 * The wake-up alarm, opened full screen by its notification over the lock screen: it rings and
 * vibrates until it is dismissed. Dismissing ends the alarm only, never the recording.
 *
 * The alarm itself is posted by `recording.WakeAlarm`; this is only its screen.
 */
class AlarmActivity : ComponentActivity() {
  private var player: MediaPlayer? = null
  private var vibrator: Vibrator? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    enableEdgeToEdge()
    ring()
    val manager = getSystemService(NotificationManager::class.java)
    val fullScreen = WakeAlarm.canUseFullScreen(manager)
    setContent {
      TaigaTheme(darkTheme = true) {
        AlarmScreen(fullScreenAllowed = fullScreen, onDismiss = ::dismiss)
      }
    }
  }

  override fun onDestroy() {
    stopRinging()
    // Leaving the screen any other way than Dismiss still ends the alarm, not the recording.
    if (isFinishing) WakeAlarm.dismiss(this)
    super.onDestroy()
  }

  /** The alarm tone, looping until it is dismissed; a phone without one still vibrates. */
  private fun ring() {
    val alarm =
        MediaPlayer().apply {
          setAudioAttributes(
              AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_ALARM)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build()
          )
          isLooping = true
          setOnPreparedListener { it.start() }
          setOnErrorListener { _, _, _ -> true }
        }
    val ready =
        runCatching {
              alarm.setDataSource(this, WakeAlarm.sound())
              alarm.prepareAsync()
            }
            .isSuccess
    if (ready) {
      player = alarm
    } else {
      alarm.release()
    }
    vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
    runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION, 0)) }
  }

  private fun stopRinging() {
    player?.let {
      runCatching { it.stop() }
      it.release()
    }
    player = null
    vibrator?.cancel()
    vibrator = null
  }

  private fun dismiss() {
    WakeAlarm.dismiss(this)
    stopRinging()
    finish()
  }

  private companion object {
    val VIBRATION = longArrayOf(0, 800, 600)
  }
}
