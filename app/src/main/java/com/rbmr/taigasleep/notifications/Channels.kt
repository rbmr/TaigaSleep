package com.rbmr.taigasleep.notifications

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.annotation.StringRes
import com.rbmr.taigasleep.R

/**
 * Every notification channel of the app, created once at app start.
 *
 * The channels are grouped by purpose, so the phone's notification settings show one place per
 * kind of work. A channel's id is the key Android keeps the user's choice under, so the ids never
 * change: a new id would silently reset what the user turned on or off.
 */
object Channels {
  /** The recording in progress. */
  const val RECORDING = "recording"

  /** A ringing wake-up alarm. */
  const val WAKE_ALARM = "wake_alarm"

  /** Uploads, and the analyses that follow them. */
  const val UPLOADS = "uploads"

  /** The channel groups, one per purpose. */
  const val GROUP_RECORDING = "group_recording"
  const val GROUP_ALARM = "group_alarm"
  const val GROUP_UPLOAD_AND_ANALYSIS = "group_upload_and_analysis"
  const val GROUP_DEVICE_HEALTH = "group_device_health"

  /** The channel of the full-screen alarm the app had before; removed from phones that have it. */
  private const val RETIRED_SCREEN_ALARM = "wake_alarm_screen"

  private val VIBRATION = longArrayOf(0, 800, 600)

  /** Creates every group and channel; the system keeps them, so a repeat changes nothing. */
  fun create(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannelGroups(
        listOf(
            group(context, GROUP_RECORDING, R.string.notification_group_recording),
            group(context, GROUP_ALARM, R.string.notification_group_alarm),
            group(context, GROUP_UPLOAD_AND_ANALYSIS, R.string.notification_group_uploads_analyses),
            group(context, GROUP_DEVICE_HEALTH, R.string.notification_group_device_health),
        )
    )
    manager.createNotificationChannel(
        NotificationChannel(
                RECORDING,
                context.getString(R.string.rec_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            .apply { group = GROUP_RECORDING }
    )
    manager.createNotificationChannel(
        NotificationChannel(
                UPLOADS,
                context.getString(R.string.upload_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            .apply { group = GROUP_UPLOAD_AND_ANALYSIS }
    )
    manager.createNotificationChannel(alarm(context))
    manager.deleteNotificationChannel(RETIRED_SCREEN_ALARM)
  }

  /** The alarm's channel, on the alarm sound and volume. */
  private fun alarm(context: Context): NotificationChannel {
    val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    return NotificationChannel(
            WAKE_ALARM,
            context.getString(R.string.wake_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        )
        .apply {
          setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attributes)
          enableVibration(true)
          vibrationPattern = VIBRATION
          group = GROUP_ALARM
        }
  }

  private fun group(context: Context, id: String, @StringRes name: Int) =
      NotificationChannelGroup(id, context.getString(name))
}
