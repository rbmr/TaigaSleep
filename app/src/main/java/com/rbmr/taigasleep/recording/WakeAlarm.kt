package com.rbmr.taigasleep.recording

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.diagnostics.Diagnostics

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
  /** The notification of a ringing alarm; the recording's own notification has its own id. */
  const val NOTIFICATION_ID = 2

  private const val TAG = "WakeAlarm"
  private const val REQUEST_END = 56
  private const val REQUEST_OPEN = 57
  private const val CHANNEL_ALARM = "wake_alarm"

  /** The channel of the full-screen alarm the app had before; removed from phones that have it. */
  private const val CHANNEL_SCREEN_RETIRED = "wake_alarm_screen"
  private val VIBRATION = longArrayOf(0, 800, 600)

  /** Arms the alarm at the end of the window; it fires even if the recording service is gone. */
  fun scheduleEnd(context: Context, endUtcMs: Long) {
    val alarms = context.getSystemService(AlarmManager::class.java)
    try {
      alarms.setAlarmClock(AlarmManager.AlarmClockInfo(endUtcMs, open(context)), end(context))
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
    val manager = context.getSystemService(NotificationManager::class.java)
    channels(context, manager)
    val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    Diagnostics.log("wake_alarm", mapOf("reason" to reason.wire, "notifications" to allowed))
    val text =
        context.getString(R.string.wake_notification_text, context.getString(reason.text))
    val notification =
        Notification.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.wake_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open(context))
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                        null,
                        context.getString(R.string.wake_notification_stop),
                        WakeAlarmReceiver.dismiss(context),
                    )
                    .build()
            )
            .build()
    // Insistent: the sound repeats until the notification is stopped, like an alarm clock.
    notification.flags = notification.flags or Notification.FLAG_INSISTENT
    try {
      manager.notify(NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
      Log.e(TAG, "the wake-up alarm could not be posted", e)
    }
  }

  /** The user stopped the alarm; the recording is untouched and the window is spent. */
  fun dismiss(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    // A window armed since this alarm belongs to a later recording, so only a spent one is dropped.
    if (RecordingPrefs.get(context).armedWake() == null) cancelEnd(context)
  }

  /** Opens the app, from the alarm or from the system's alarm clock icon. */
  private fun open(context: Context): PendingIntent =
      PendingIntent.getActivity(
          context,
          REQUEST_OPEN,
          context.packageManager.getLaunchIntentForPackage(context.packageName),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

  private fun end(context: Context): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          REQUEST_END,
          Intent(context, WakeAlarmReceiver::class.java),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

  /** The alarm's channel, on the alarm sound and volume. */
  private fun channels(context: Context, manager: NotificationManager) {
    val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    manager.createNotificationChannel(
        NotificationChannel(
                CHANNEL_ALARM,
                context.getString(R.string.wake_channel_alarm),
                NotificationManager.IMPORTANCE_HIGH,
            )
            .apply {
              setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attributes)
              enableVibration(true)
              vibrationPattern = VIBRATION
            }
    )
    manager.deleteNotificationChannel(CHANNEL_SCREEN_RETIRED)
  }
}
