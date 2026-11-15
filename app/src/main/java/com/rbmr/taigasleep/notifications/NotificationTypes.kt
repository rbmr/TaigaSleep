package com.rbmr.taigasleep.notifications

import androidx.annotation.StringRes
import com.rbmr.taigasleep.R

/**
 * Every kind of notification the app can send, each posted on its channel from [Channels].
 *
 * A required type belongs to the recording or to the alarm, and the app cannot work without it, so
 * it has no switch in the app. An optional type has a switch of its own: the app posts it only when
 * that switch is on and Android allows its channel. The order is the order the settings screen
 * lists them: the required types first, then the optional ones.
 */
enum class NotificationType(
    internal val preferenceKey: String,
    val channelId: String,
    val required: Boolean,
    @StringRes val label: Int,
) {
  RECORDING(
      "recording",
      Channels.RECORDING,
      required = true,
      label = R.string.notification_type_recording,
  ),
  WAKE_ALARM(
      "wake_alarm",
      Channels.WAKE_ALARM,
      required = true,
      label = R.string.notification_type_wake_alarm,
  ),
  UPLOADS(
      "uploads",
      Channels.UPLOADS,
      required = false,
      label = R.string.notification_type_uploads,
  ),
  ANALYSES(
      "analyses",
      Channels.UPLOADS,
      required = false,
      label = R.string.notification_type_analyses,
  ),
  TRACKER_BATTERY(
      "tracker_battery",
      Channels.TRACKER_BATTERY,
      required = false,
      label = R.string.notification_type_tracker_battery,
  ),
  PHONE_STORAGE(
      "phone_storage",
      Channels.PHONE_STORAGE,
      required = false,
      label = R.string.notification_type_phone_storage,
  ),
}

/**
 * One notification type as the settings screen shows it: the app's own preference for an optional
 * type, and what Android allows for it.
 *
 * The switch is on only when the preference is on and Android allows both the app and the channel.
 * A required type has no switch and is on exactly when Android allows it. Nothing Android says is
 * stored, so the app's preference survives a block and returns when the block is lifted.
 */
data class NotificationTypeState(
    val type: NotificationType,
    val preferenceOn: Boolean,
    val appAllowed: Boolean,
    val channelAllowed: Boolean,
) {
  /** True when Android allows this type to be posted. */
  val allowed: Boolean
    get() = appAllowed && channelAllowed

  /** True when the app would post this type now. */
  val on: Boolean
    get() = allowed && (type.required || preferenceOn)

  /** True when the row can carry a switch; a required type has none. */
  val hasSwitch: Boolean
    get() = !type.required
}
