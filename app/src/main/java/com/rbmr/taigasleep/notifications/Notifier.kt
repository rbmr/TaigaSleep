package com.rbmr.taigasleep.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rbmr.taigasleep.R

/**
 * Every notification the app builds and posts.
 *
 * A sender hands over the text and the one thing only it knows, such as the action of a ringing
 * alarm. The channel, the id, the icon and the tap target stay here, so a notification looks the
 * same wherever it comes from and a new kind of notification is added in one place.
 *
 * The channels themselves are in [Channels] and are created at app start; a notification posted
 * here belongs to one of them.
 */
object Notifier {
  /**
   * The app's launcher activity, which the manifest declares. It is named rather than imported
   * because the activity is app wiring, which no package may depend on.
   */
  const val APP_ACTIVITY = "com.rbmr.taigasleep.MainActivity"

  /** The id of the recording in progress; an update replaces the notification it belongs to. */
  const val RECORDING_ID = 1

  /** The id of a ringing wake-up alarm; the recording has its own. */
  const val WAKE_ALARM_ID = 2

  /** The id of the upload in progress. */
  const val UPLOAD_ID = 4211

  /** The id of the one notification that batches upload and analysis results. */
  const val RESULTS_ID = 4212

  /** The id of the tracker's low battery alert. */
  const val TRACKER_BATTERY_ID = 4213

  /** The id of the phone's low storage alert. */
  const val PHONE_STORAGE_ID = 4214

  /** The extra the app's activity reads to tell a start from the results notification. */
  const val EXTRA_RESULTS_OPENED = "com.rbmr.taigasleep.notifications.RESULTS_OPENED"

  private const val TAG = "Notifier"
  private const val REQUEST_OPEN = 57
  private const val REQUEST_OPEN_RESULTS = 58
  private const val REQUEST_DISMISS_RESULTS = 59

  /**
   * The recording in progress: the tracker and one status, with the notification's own timer
   * showing the duration. [startUtcMs] is 0 while no time is known, and then no timer is shown.
   */
  fun recording(
      context: Context,
      text: String,
      startUtcMs: Long,
      activity: String,
  ): Notification =
      NotificationCompat.Builder(context, Channels.RECORDING)
          .setSmallIcon(R.drawable.ic_stat_recording)
          .setContentTitle(context.getString(R.string.rec_notification_title))
          .setContentText(text)
          .setContentIntent(openApp(context, activity))
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setCategory(NotificationCompat.CATEGORY_SERVICE)
          .apply {
            if (startUtcMs > 0) {
              setWhen(startUtcMs)
              setUsesChronometer(true)
            }
          }
          .build()

  /** A ringing alarm: it sounds and vibrates until [stop] is used. The fallback path. */
  fun wakeAlarm(
      context: Context,
      text: String,
      stop: PendingIntent,
      activity: String,
  ): Notification {
    val notification =
        NotificationCompat.Builder(context, Channels.WAKE_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.wake_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(context, activity))
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.wake_notification_stop), stop)
            .build()
    // Insistent: the sound repeats until the notification is stopped, like an alarm clock.
    return notification.apply { flags = flags or Notification.FLAG_INSISTENT }
  }

  /**
   * The ringing alarm's visible control: the same title and Stop, but silent, because the ringing
   * service owns the sound and the vibration.
   */
  fun wakeAlarmControl(
      context: Context,
      text: String,
      stop: PendingIntent,
      activity: String,
  ): Notification =
      NotificationCompat.Builder(context, Channels.WAKE_ALARM_CONTROL)
          .setSmallIcon(R.drawable.ic_stat_alarm)
          .setContentTitle(context.getString(R.string.wake_notification_title))
          .setContentText(text)
          .setStyle(NotificationCompat.BigTextStyle().bigText(text))
          .setCategory(NotificationCompat.CATEGORY_ALARM)
          .setContentIntent(openApp(context, activity))
          .setAutoCancel(true)
          .setOngoing(true)
          .setSilent(true)
          .addAction(0, context.getString(R.string.wake_notification_stop), stop)
          .build()

  /** The upload in progress, as the foreground work that keeps the transfer alive. */
  fun upload(context: Context, activity: String): Notification =
      NotificationCompat.Builder(context, Channels.UPLOADS)
          .setSmallIcon(R.drawable.ic_stat_recording)
          .setContentTitle(context.getString(R.string.upload_notification_title))
          .setContentText(context.getString(R.string.upload_notification_body))
          .setContentIntent(openApp(context, activity))
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setCategory(NotificationCompat.CATEGORY_PROGRESS)
          .setProgress(0, 0, true)
          .build()

  /**
   * The one summary of upload and analysis results, updated in place: [text] names only the
   * outcomes whose type is on. Opening it goes to the app and clears the counts, and so does
   * dismissing it.
   */
  fun results(context: Context, text: String, activity: String): Notification =
      NotificationCompat.Builder(context, Channels.UPLOADS)
          .setSmallIcon(R.drawable.ic_stat_recording)
          .setContentTitle(context.getString(R.string.results_notification_title))
          .setContentText(text)
          .setStyle(NotificationCompat.BigTextStyle().bigText(text))
          .setContentIntent(openResults(context, activity))
          .setDeleteIntent(dismissResults(context))
          .setAutoCancel(true)
          .setOnlyAlertOnce(true)
          .setCategory(NotificationCompat.CATEGORY_STATUS)
          .build()

  /** The tracker's battery fell below what the app warns about. */
  fun trackerBatteryLow(context: Context, percent: Int, activity: String): Notification =
      NotificationCompat.Builder(context, Channels.TRACKER_BATTERY)
          .setSmallIcon(R.drawable.ic_stat_recording)
          .setContentTitle(context.getString(R.string.tracker_battery_notification_title))
          .setContentText(context.getString(R.string.tracker_battery_notification_body, percent))
          .setContentIntent(openApp(context, activity))
          .setAutoCancel(true)
          .setCategory(NotificationCompat.CATEGORY_STATUS)
          .build()

  /** Free space on this phone fell below what a recording needs. */
  fun phoneStorageLow(context: Context, activity: String): Notification =
      NotificationCompat.Builder(context, Channels.PHONE_STORAGE)
          .setSmallIcon(R.drawable.ic_stat_recording)
          .setContentTitle(context.getString(R.string.phone_storage_notification_title))
          .setContentText(context.getString(R.string.phone_storage_notification_body))
          .setContentIntent(openApp(context, activity))
          .setAutoCancel(true)
          .setCategory(NotificationCompat.CATEGORY_STATUS)
          .build()

  /** Posts [notification] under [id]; false when the phone refuses it. */
  fun post(context: Context, id: Int, notification: Notification): Boolean {
    return try {
      NotificationManagerCompat.from(context).notify(id, notification)
      true
    } catch (e: SecurityException) {
      Log.e(TAG, "notification $id could not be posted", e)
      false
    }
  }

  /** Removes the notification under [id], if it is there. */
  fun cancel(context: Context, id: Int) {
    NotificationManagerCompat.from(context).cancel(id)
  }

  /**
   * The one tap target: the app's launcher activity, started the way the launcher starts it. A
   * running app comes to the front instead of getting a second copy of its screen on top.
   */
  fun openApp(context: Context, activity: String): PendingIntent =
      PendingIntent.getActivity(
          context,
          REQUEST_OPEN,
          launcherIntent(context, activity),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  /** The results summary's tap: the same launcher start, marked so the app clears its counts. */
  private fun openResults(context: Context, activity: String): PendingIntent =
      PendingIntent.getActivity(
          context,
          REQUEST_OPEN_RESULTS,
          launcherIntent(context, activity).putExtra(EXTRA_RESULTS_OPENED, true),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  /** The results summary's dismissal: the counts it showed are done. */
  private fun dismissResults(context: Context): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          REQUEST_DISMISS_RESULTS,
          Intent(context, ResultsReceiver::class.java).setAction(ResultsReceiver.ACTION_DISMISSED),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  private fun launcherIntent(context: Context, activity: String): Intent =
      Intent(Intent.ACTION_MAIN)
          .addCategory(Intent.CATEGORY_LAUNCHER)
          .setClassName(context, activity)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
