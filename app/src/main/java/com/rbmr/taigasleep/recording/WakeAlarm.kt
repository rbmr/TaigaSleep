package com.rbmr.taigasleep.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.notifications.Notifier
import kotlinx.coroutines.flow.MutableStateFlow

/** Why a wake-up alarm rings: a chosen signal said the user is waking, or the window ended. */
enum class WakeReason(val wire: String, @StringRes val text: Int) {
  SIGNAL("signal", R.string.wake_reason_signal),
  WINDOW_END("window_end", R.string.wake_reason_end);

  companion object {
    fun fromWire(value: String?): WakeReason? = entries.firstOrNull { it.wire == value }
  }
}

/**
 * The Android side of an armed wake-up window: the alarm at the end of the window, which is an
 * alarm clock so it fires even when this process is gone, and the ringing itself, which
 * [WakeAlarmService] performs on the alarm audio stream. Stop ends the alarm only; the recording
 * always goes on.
 *
 * [WakeDecision] decides when; this only starts the ring.
 */
@Suppress("TooManyFunctions")
object WakeAlarm {
  private const val TAG = "WakeAlarm"
  private const val REQUEST_END = 56
  private const val REQUEST_STOP = 60

  /** The last test ring's result, as the alarm settings show it. */
  val testResult = MutableStateFlow<AlarmTestResult?>(null)

  /** True while the ringing service has an alarm sounding, wherever the app is. */
  val ringing = MutableStateFlow(false)

  /** Arms the alarm at the end of the window; it fires even if the recording service is gone. */
  fun scheduleEnd(context: Context, endUtcMs: Long) {
    val alarms = context.getSystemService(AlarmManager::class.java)
    try {
      alarms.setAlarmClock(
          AlarmManager.AlarmClockInfo(
              endUtcMs,
              Notifier.openApp(context, Notifier.APP_ACTIVITY),
          ),
          end(context),
      )
    } catch (e: SecurityException) {
      // An alarm clock needs the exact-alarm permission. Without it the alarm is still set, just
      // not as an alarm clock the system may hold against the user's next wake-up.
      Log.w(TAG, "the phone refused an alarm clock for the wake-up window", e)
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endUtcMs, end(context))
      } catch (e: SecurityException) {
        Log.e(TAG, "the wake-up window cannot be armed", e)
      }
    }
  }

  fun cancelEnd(context: Context) {
    context.getSystemService(AlarmManager::class.java).cancel(end(context))
  }

  /**
   * Forgets a window whose recording is over, and drops its end alarm. A window belongs to the
   * recording that armed it, so nothing may ring for it once that recording is gone.
   */
  fun disarm(context: Context) {
    RecordingPrefs.get(context).clearArmedWake()
    cancelEnd(context)
  }

  /**
   * Rings now for [reason]. The ringing service takes the alarm stream and the vibration; a phone
   * that refuses to start it gets the notification path instead, and the failure is recorded.
   */
  fun fire(
      context: Context,
      reason: WakeReason,
      source: String = WakeAlarmService.SOURCE_SIGNAL,
  ) {
    start(context, ringIntent(context, reason, source))
  }

  /** Rings for a few seconds through the same path, and reports the result in [testResult]. */
  fun test(context: Context) {
    testResult.value = null
    start(
        context,
        ringIntent(context, WakeReason.WINDOW_END, WakeAlarmService.SOURCE_TEST)
            .putExtra(WakeAlarmService.EXTRA_TEST_MS, TEST_MS),
    )
  }

  /** The Stop action of the ringing alarm: a service action, so sound and volume end together. */
  fun stop(context: Context): PendingIntent =
      PendingIntent.getService(
          context,
          REQUEST_STOP,
          stopIntent(context),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

  /** Stops a ring started by an older notification or by the receiver's dismiss action. */
  fun stopNow(context: Context) {
    try {
      context.startService(stopIntent(context))
    } catch (e: IllegalStateException) {
      stopped(context, e)
    } catch (e: SecurityException) {
      stopped(context, e)
    }
  }

  /** Nothing is ringing, or the phone refused the start: a plain dismiss is all that is left. */
  private fun stopped(context: Context, error: Exception) {
    Log.w(TAG, "the alarm service could not be stopped", error)
    dismiss(context)
  }

  /** The user stopped the alarm; the recording is untouched and the window is spent. */
  fun dismiss(context: Context) {
    Notifier.cancel(context, Notifier.WAKE_ALARM_ID)
    // A window armed since this alarm belongs to a later recording, so only a spent one is dropped.
    if (RecordingPrefs.get(context).armedWake() == null) cancelEnd(context)
  }

  private fun start(context: Context, intent: Intent) {
    try {
      context.startForegroundService(intent)
    } catch (e: IllegalStateException) {
      serviceFailed(context, intent, e)
    } catch (e: SecurityException) {
      serviceFailed(context, intent, e)
    }
  }

  /** The ringing service could not start: record it and fall back to the notification path. */
  private fun serviceFailed(context: Context, intent: Intent, error: Exception) {
    Log.e(TAG, "the alarm service could not be started", error)
    Diagnostics.log(
        "wake_alarm_failed",
        mapOf(
            "source" to intent.getStringExtra(WakeAlarmService.EXTRA_SOURCE),
            "reason" to "service",
        ),
    )
    testResult.value =
        AlarmTestResult(started = false, audible = false, volume = 0, maxVolume = 0)
    fallback(context, intent)
  }

  /** The notification path, for a phone that will not let the ringing service start. */
  private fun fallback(context: Context, intent: Intent) {
    val reason = WakeReason.fromWire(intent.getStringExtra(WakeAlarmService.EXTRA_REASON))
        ?: WakeReason.WINDOW_END
    val text = context.getString(R.string.wake_notification_text, context.getString(reason.text))
    Notifier.post(
        context,
        Notifier.WAKE_ALARM_ID,
        Notifier.wakeAlarm(context, text, stop(context), Notifier.APP_ACTIVITY),
    )
  }

  private fun ringIntent(context: Context, reason: WakeReason, source: String): Intent =
      Intent(context, WakeAlarmService::class.java)
          .setAction(WakeAlarmService.ACTION_RING)
          .putExtra(WakeAlarmService.EXTRA_REASON, reason.wire)
          .putExtra(WakeAlarmService.EXTRA_SOURCE, source)

  private fun stopIntent(context: Context): Intent =
      Intent(context, WakeAlarmService::class.java)
          .setAction(WakeAlarmService.ACTION_STOP)
          .putExtra(WakeAlarmService.EXTRA_HOW, "user")

  private fun end(context: Context): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          REQUEST_END,
          Intent(context, WakeAlarmReceiver::class.java),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

  private const val TEST_MS = 3_000
}
