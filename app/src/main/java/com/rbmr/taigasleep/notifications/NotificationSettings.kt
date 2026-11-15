package com.rbmr.taigasleep.notifications

import android.content.Context

/**
 * The app's own switch for every optional [NotificationType]. All of them are on until the user
 * turns one off. A required type has no preference here, because the app cannot stop posting it.
 *
 * Only the app's choice is stored. Whether Android allows a notification is read from the phone
 * every time, so a change made in its settings is never overwritten.
 */
class NotificationSettings internal constructor(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  /** The user's switch for an optional type; on until they turn it off. */
  fun isOn(type: NotificationType): Boolean {
    require(!type.required) { "${type.name} is required and has no switch" }
    return prefs.getBoolean(key(type), true)
  }

  /** Stores the user's switch for an optional type. Android's own settings are not touched. */
  fun setOn(type: NotificationType, on: Boolean) {
    require(!type.required) { "${type.name} is required and has no switch" }
    prefs.edit().putBoolean(key(type), on).apply()
  }

  private fun key(type: NotificationType): String = "notification_on_${type.preferenceKey}"

  companion object {
    @Volatile private var instance: NotificationSettings? = null

    fun get(context: Context): NotificationSettings =
        instance
            ?: synchronized(this) {
              instance ?: NotificationSettings(context).also { instance = it }
            }
  }
}
