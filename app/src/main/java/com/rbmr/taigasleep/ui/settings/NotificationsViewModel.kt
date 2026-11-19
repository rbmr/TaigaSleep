package com.rbmr.taigasleep.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.notifications.NotificationAccess
import com.rbmr.taigasleep.notifications.NotificationType
import com.rbmr.taigasleep.notifications.NotificationTypeState
import com.rbmr.taigasleep.notifications.PhoneNotificationAccess
import com.rbmr.taigasleep.notifications.notificationTypeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Notifications as one state: the app's block, and one row per notification type. */
data class NotificationsUiState(
    val appBlocked: Boolean = false,
    val rows: List<NotificationTypeState> = emptyList(),
)

/**
 * The state of settings → Notifications, read from Android's notification settings. That is the
 * only source: Android does not let the app change its own channels, so a switch or a row opens
 * the matching Android screen and the row shows the phone's answer when the user returns.
 */
class NotificationsViewModel(
    private val access: NotificationAccess,
    private val openApp: () -> Unit,
    private val openChannel: (String) -> Unit,
) : ViewModel() {
  private val _state = MutableStateFlow(read())
  val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

  /** Reads the phone's settings again; the screen calls this every time it is shown. */
  fun refresh() {
    _state.value = read()
  }

  /** A switch or a row opens the Android screen that changes this type. */
  fun openSettings(type: NotificationType) {
    val channel = type.channelIds.singleOrNull()
    if (channel == null || !access.appAllowed()) openApp() else openChannel(channel)
  }

  /** The block's button and the one below the list: the app's own notification settings. */
  fun openAppSettings() {
    openApp()
  }

  private fun read(): NotificationsUiState =
      NotificationsUiState(
          appBlocked = !access.appAllowed(),
          rows = NotificationType.entries.map { notificationTypeState(it, access) },
      )

  /** Builds the state from the phone's settings. */
  class Factory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NotificationsViewModel(
            access = PhoneNotificationAccess(context),
            openApp = { openPhoneSettings(context, null) },
            openChannel = { channelId -> openPhoneSettings(context, channelId) },
        ) as T
  }
}

/** Opens the phone's settings for the app's notifications, or for one of its channels. */
internal fun openPhoneSettings(context: Context, channelId: String?) {
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
