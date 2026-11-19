package com.rbmr.taigasleep.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** What Android allows: the app's notifications and each of its channels. */
interface NotificationAccess {
  /** False when all of the app's notifications are off, including a permission not granted. */
  fun appAllowed(): Boolean

  /** False when the user blocked this channel, or its group, in the phone's settings. */
  fun channelAllowed(channelId: String): Boolean
}

/**
 * Reads [NotificationAccess] from the phone. The app can read these settings but cannot change
 * them, so the Notifications screen shows them as they are and the user changes a type in
 * Android's own screens.
 */
class PhoneNotificationAccess(context: Context) : NotificationAccess {
  private val application = context.applicationContext
  private val manager = application.getSystemService(NotificationManager::class.java)

  override fun appAllowed(): Boolean =
      NotificationManagerCompat.from(application).areNotificationsEnabled()

  override fun channelAllowed(channelId: String): Boolean {
    // A channel the phone has not created yet cannot be blocked; the app creates them at start.
    val channel = manager.getNotificationChannel(channelId) ?: return true
    if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    val groupId = channel.group ?: return true
    val group = manager.getNotificationChannelGroup(groupId) ?: return true
    return !group.isBlocked
  }
}
