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
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.diagnostics.Diagnostics

/**
 * The Android side of an armed wake-up window: the alarm at the end of the window, which is an
 * alarm clock so it fires even when this process is gone, and the alarm itself — a full-screen
 * screen with sound and vibration, or a high-priority notification with sound when the phone does
 * not let the app show the screen.
 *
 * [WakeDecision] decides when; this only rings.
 */
object WakeAlarm {
  /** The notification of a ringing alarm; the recording's own notification has its own id. */
  const val NOTIFICATION_ID = 2

  private const val TAG = "WakeAlarm"
  private const val REQUEST_END = 56
  private const val REQUEST_SCREEN = 57
  private const val CHANNEL_ALARM = "wake_alarm"
  private const val CHANNEL_SCREEN = "wake_alarm_screen"
  private val VIBRATION = longArrayOf(0, 800, 600)

  /** Arms the alarm at the end of the window; it fires even if the recording service is gone. */
  fun scheduleEnd(context: Context, endUtcMs: Long) {
    val alarms = context.getSystemService(AlarmManager::class.java)
    val show = screen(context, PendingIntent.FLAG_UPDATE_CURRENT)
    try {
      alarms.setAlarmClock(AlarmManager.AlarmClockInfo(endUtcMs, show), end(context))
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

  /** Rings now: the screen when the phone allows it, otherwise a notification that sounds. */
  fun fire(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    channels(context, manager)
    val fullScreen = canUseFullScreen(manager)
    Diagnostics.log("wake_alarm", mapOf("full_screen" to fullScreen))
    // A notification the phone refuses to show cannot ring, so the screen rings on its own then.
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
      ringScreen(context)
      return
    }
    val screen = screen(context, PendingIntent.FLAG_UPDATE_CURRENT)
    val notification =
        Notification.Builder(context, if (fullScreen) CHANNEL_SCREEN else CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.wake_notification_title))
            .setContentText(context.getString(R.string.wake_notification_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(screen)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                        null,
                        context.getString(R.string.wake_notification_dismiss),
                        WakeAlarmReceiver.dismiss(context),
                    )
                    .build()
            )
            .apply { if (fullScreen) setFullScreenIntent(screen, true) }
            .build()
    try {
      manager.notify(NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
      Log.e(TAG, "the wake-up alarm could not be posted", e)
      ringScreen(context)
    }
  }

  /** The user dismissed the alarm; the recording is untouched and the window is spent. */
  fun dismiss(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    // A window armed since this alarm belongs to a later recording, so only a spent one is dropped.
    if (RecordingPrefs.get(context).armedWake() == null) cancelEnd(context)
  }

  /** The tone an alarm rings with, for the screen that rings it. */
  fun sound(): Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

  /** True when the phone lets this app open its alarm screen over the lock screen. */
  fun canUseFullScreen(manager: NotificationManager): Boolean =
      Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()

  private fun screenIntent(context: Context): Intent = RecordingHooks.current().wakeAlarmScreen(context)

  /**
   * Opens the alarm screen without a notification. A background start is not always allowed, so this
   * is the last attempt to ring, not the normal path.
   */
  private fun ringScreen(context: Context) {
    runCatching {
      context.startActivity(screenIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  private fun screen(context: Context, flags: Int): PendingIntent =
      PendingIntent.getActivity(
          context,
          REQUEST_SCREEN,
          screenIntent(context),
          flags or PendingIntent.FLAG_IMMUTABLE,
      )

  private fun end(context: Context): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          REQUEST_END,
          Intent(context, WakeAlarmReceiver::class.java),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

  /**
   * Two channels, because a notification's sound belongs to its channel: the silent one goes with
   * the screen, which rings itself, and the loud one is the fallback when the screen may not open.
   */
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
              setSound(sound(), attributes)
              enableVibration(true)
              vibrationPattern = VIBRATION
            }
    )
    manager.createNotificationChannel(
        NotificationChannel(
                CHANNEL_SCREEN,
                context.getString(R.string.wake_channel_screen),
                NotificationManager.IMPORTANCE_HIGH,
            )
            .apply {
              setSound(null, null)
              enableVibration(false)
              setShowBadge(false)
            }
    )
  }
}
