package com.rbmr.taigasleep.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.rbmr.taigasleep.R

internal object UploadNotifications {
  /**
   * The app's launcher activity, which the manifest declares. It is named rather than imported
   * because the activity is app wiring, which no package may depend on.
   */
  internal const val APP_ACTIVITY = "com.rbmr.taigasleep.MainActivity"

  private const val CHANNEL = "uploads"
  private const val NOTIFICATION_ID = 4211

  fun foreground(context: Context): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL,
            context.getString(R.string.upload_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    )
    val open =
        PendingIntent.getActivity(
            context,
            0,
            Intent().setClassName(context, APP_ACTIVITY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.upload_notification_title))
            .setContentText(context.getString(R.string.upload_notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()
    return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }
}
