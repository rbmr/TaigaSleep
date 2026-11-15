package com.rbmr.taigasleep.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.notifications.Notifier

/** Why a wake-up alarm rings: a chosen signal said the user is waking, or the window ended. */
enum class WakeReason(val wire: String, @StringRes val text: Int) {
  SIGNAL("signal", R.string.wake_reason_signal),
  WINDOW_END("window_end", R.string.wake_reason_end),
}

/**
 * The Android side of an armed wake-up window: the alarm at the end of the window, which is an
 * alarm clock so it fires even when this process is gone, and the alarm itself — a high-priority
 * notification on the alarm sound that rings until Stop is pressed. Stop ends the alarm only; the
 * recording always goes on.
 *
 * [WakeDecision] decides when; this only rings.
 */
object WakeAlarm {
  private const val TAG = "WakeAlarm"
  private const val REQUEST_END = 56

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

  /** Rings now for [reason]: a notification that sounds and vibrates until it is stopped. */
  fun fire(context: Context, reason: WakeReason) {
    val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    Diagnostics.log("wake_alarm", mapOf("reason" to reason.wire, "notifications" to allowed))
    val text =
        context.getString(R.string.wake_notification_text, context.getString(reason.text))
    Notifier.post(
        context,
        Notifier.WAKE_ALARM_ID,
        Notifier.wakeAlarm(
            context,
            text,
            WakeAlarmReceiver.dismiss(context),
            Notifier.APP_ACTIVITY,
        ),
    )
  }

  /** The user stopped the alarm; the recording is untouched and the window is spent. */
  fun dismiss(context: Context) {
    Notifier.cancel(context, Notifier.WAKE_ALARM_ID)
    // A window armed since this alarm belongs to a later recording, so only a spent one is dropped.
    if (RecordingPrefs.get(context).armedWake() == null) cancelEnd(context)
  }

  private fun end(context: Context): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          REQUEST_END,
          Intent(context, WakeAlarmReceiver::class.java),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
}
