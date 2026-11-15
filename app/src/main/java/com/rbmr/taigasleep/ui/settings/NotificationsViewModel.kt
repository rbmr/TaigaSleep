package com.rbmr.taigasleep.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.notifications.NotificationAccess
import com.rbmr.taigasleep.notifications.NotificationSettings
import com.rbmr.taigasleep.notifications.NotificationType
import com.rbmr.taigasleep.notifications.NotificationTypeState
import com.rbmr.taigasleep.notifications.PhoneNotificationAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Notifications as one state: the app's block, and one row per notification type. */
data class NotificationsUiState(
    val appBlocked: Boolean = false,
    val rows: List<NotificationTypeState> = emptyList(),
)

/**
 * The state of settings → Notifications, read from the app's own switches and from Android's
 * notification settings. Android's choice is never written: a switch the phone blocks sends the
 * user to the matching Android screen instead, and the row shows the phone's answer when they
 * return.
 */
class NotificationsViewModel(
    private val settings: NotificationSettings,
    private val access: NotificationAccess,
    private val openAppSettings: () -> Unit,
    private val openChannelSettings: (String) -> Unit,
) : ViewModel() {
  private val _state = MutableStateFlow(read())
  val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

  /** Reads the phone's settings again; the screen calls this every time it is shown. */
  fun refresh() {
    _state.value = read()
  }

  /**
   * The user moved the switch of an optional type. Turning it off only stores the app's choice.
   * Turning it on while Android blocks the app or the channel opens Android's settings and stores
   * nothing, so the switch still follows the phone.
   */
  fun setOn(type: NotificationType, on: Boolean) {
    if (type.required) return
    val row = row(type)
    if (on && !row.allowed) {
      open(row)
      return
    }
    settings.setOn(type, on)
    refresh()
  }

  /** A row opens its channel's Android settings, or the app's when all notifications are off. */
  fun openSettings(type: NotificationType) {
    open(row(type))
  }

  /** The button of the block at the top: the app's own notification settings in Android. */
  fun openAppNotificationSettings() {
    openAppSettings()
  }

  private fun open(row: NotificationTypeState) {
    if (row.appAllowed) openChannelSettings(row.type.channelId) else openAppSettings()
  }

  private fun row(type: NotificationType): NotificationTypeState =
      _state.value.rows.first { it.type == type }

  private fun read(): NotificationsUiState {
    val appAllowed = access.appAllowed()
    return NotificationsUiState(
        appBlocked = !appAllowed,
        rows =
            NotificationType.entries.map { type ->
              NotificationTypeState(
                  type = type,
                  preferenceOn = type.required || settings.isOn(type),
                  appAllowed = appAllowed,
                  channelAllowed = access.channelAllowed(type.channelId),
              )
            },
    )
  }

  /** Builds the state from the phone's settings. */
  class Factory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NotificationsViewModel(
            settings = NotificationSettings.get(context),
            access = PhoneNotificationAccess(context),
            openAppSettings = { openNotificationSettings(context, null) },
            openChannelSettings = { channelId -> openNotificationSettings(context, channelId) },
        ) as T
  }
}

/** Opens the phone's settings for the app's notifications, or for one of its channels. */
internal fun openNotificationSettings(context: Context, channelId: String?) {
  val intent =
      if (channelId == null) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      } else {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
      }
  context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
