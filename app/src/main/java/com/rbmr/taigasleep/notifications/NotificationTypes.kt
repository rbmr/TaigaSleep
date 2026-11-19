package com.rbmr.taigasleep.notifications

import androidx.annotation.StringRes
import com.rbmr.taigasleep.R

/**
 * Every kind of notification the app can send, each posted on its own channels from [Channels].
 *
 * A type that posts on more than one channel is allowed only when Android allows all of them, so a
 * blocked channel is never hidden by another one that is open. Android's settings for these channels
 * are the only switches: the app posts a type exactly when Android allows the app and the type's
 * channels. An app cannot change its own channels, so the Notifications screen opens the phone's
 * settings instead of keeping a copy. The order is the order the screen lists them.
 */
enum class NotificationType(
    val channelIds: List<String>,
    @StringRes val label: Int,
) {
  RECORDING(
      channelIds = listOf(Channels.RECORDING),
      label = R.string.notification_type_recording,
  ),
  WAKE_ALARM(
      channelIds = listOf(Channels.WAKE_ALARM, Channels.WAKE_ALARM_CONTROL),
      label = R.string.notification_type_wake_alarm,
  ),
  UPLOADS(
      channelIds = listOf(Channels.UPLOADS),
      label = R.string.notification_type_uploads,
  ),
  ANALYSES(
      channelIds = listOf(Channels.ANALYSES),
      label = R.string.notification_type_analyses,
  ),
  TRACKER_BATTERY(
      channelIds = listOf(Channels.TRACKER_BATTERY),
      label = R.string.notification_type_tracker_battery,
  ),
  PHONE_STORAGE(
      channelIds = listOf(Channels.PHONE_STORAGE),
      label = R.string.notification_type_phone_storage,
  ),
}

/**
 * One type as the Notifications screen shows it: on when Android allows the app and every channel
 * the type posts on.
 *
 * Nothing is stored, so the phone's settings are the only source and a change made there is what
 * the next notification follows.
 */
data class NotificationTypeState(
    val type: NotificationType,
    val on: Boolean,
)

/** [type] as Android has it now: the app's notifications and every channel the type posts on. */
internal fun notificationTypeState(
    type: NotificationType,
    access: NotificationAccess,
): NotificationTypeState =
    NotificationTypeState(
        type = type,
        on = access.appAllowed() && type.channelIds.all(access::channelAllowed),
    )
