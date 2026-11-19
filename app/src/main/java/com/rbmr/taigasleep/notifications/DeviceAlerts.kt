package com.rbmr.taigasleep.notifications

import android.content.Context

/**
 * The two alerts that follow a value the app already reads: the tracker's battery and the phone's
 * free space.
 *
 * Each is sent once per episode, so the recording's every-second battery reading and its
 * once-a-minute space check never repeat it. An episode ends when the value recovers: the phone
 * storage alert is then removed, while the battery alert stays until the user dismisses it and only
 * its episode is over. Both follow Android's channel settings, so nothing is sent while the app or
 * the type's channel is blocked, and the episode only starts when one is actually sent.
 */
class DeviceAlerts internal constructor(
    private val context: Context,
    private val access: NotificationAccess,
) {
  constructor(context: Context) : this(context, PhoneNotificationAccess(context))

  private val prefs =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The tracker's reading: [low] is the state the recording already derives from [percent]. */
  fun trackerBattery(percent: Int, low: Boolean) {
    synchronized(lock) {
      if (!low) {
        if (prefs.getBoolean(KEY_BATTERY, false)) {
          prefs.edit().putBoolean(KEY_BATTERY, false).commit()
        }
        return
      }
      if (prefs.getBoolean(KEY_BATTERY, false)) return
      if (!on(NotificationType.TRACKER_BATTERY)) return
      prefs.edit().putBoolean(KEY_BATTERY, true).commit()
      Notifier.post(
          context,
          Notifier.TRACKER_BATTERY_ID,
          Notifier.trackerBatteryLow(context, percent, Notifier.APP_ACTIVITY),
      )
    }
  }

  /** The phone's free space: [low] is the threshold the recording itself warns about. */
  fun phoneStorage(low: Boolean) {
    synchronized(lock) {
      if (!low) {
        if (prefs.getBoolean(KEY_STORAGE, false)) {
          prefs.edit().putBoolean(KEY_STORAGE, false).commit()
          Notifier.cancel(context, Notifier.PHONE_STORAGE_ID)
        }
        return
      }
      if (prefs.getBoolean(KEY_STORAGE, false)) return
      if (!on(NotificationType.PHONE_STORAGE)) return
      prefs.edit().putBoolean(KEY_STORAGE, true).commit()
      Notifier.post(
          context,
          Notifier.PHONE_STORAGE_ID,
          Notifier.phoneStorageLow(context, Notifier.APP_ACTIVITY),
      )
    }
  }

  private fun on(type: NotificationType): Boolean =
      notificationTypeState(type, access).on

  private companion object {
    const val PREFS = "notification_episodes"
    const val KEY_BATTERY = "tracker_battery_low"
    const val KEY_STORAGE = "phone_storage_low"

    /** One lock for every instance: an episode belongs to the app, not to one caller. */
    val lock = Any()
  }
}
