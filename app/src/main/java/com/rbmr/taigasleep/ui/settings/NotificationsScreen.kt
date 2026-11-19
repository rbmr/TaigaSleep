package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.notifications.NotificationType
import com.rbmr.taigasleep.notifications.NotificationTypeState
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * Settings → Notifications: every notification type the app can send, each with a switch that
 * shows what Android allows. Android does not let the app change its own channels, so a switch or
 * a row opens the phone's settings for that type, where its sound and importance live.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val model: NotificationsViewModel =
      viewModel(factory = remember { NotificationsViewModel.Factory(context) })
  val state by model.state.collectAsStateWithLifecycle()
  // The user may have changed a channel or the app's permission in Android while away.
  LifecycleResumeEffect(Unit) {
    model.refresh()
    onPauseOrDispose {}
  }
  NotificationsContent(
      state = state,
      onOpen = model::openSettings,
      onOpenSettings = model::openAppSettings,
      onBack = onBack,
  )
}

/** The content of Settings → Notifications, drawn from [state]. */
@Composable
internal fun NotificationsContent(
    state: NotificationsUiState,
    onOpen: (NotificationType) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  TaigaScreen(title = stringResource(R.string.settings_notifications_title), onBack = onBack) {
    if (state.appBlocked) {
      TaigaCard {
        Text(
            text = stringResource(R.string.notifications_app_blocked),
            style = MaterialTheme.taiga.text.body,
        )
        Spacer(Modifier.height(space.s))
        PrimaryButton(
            text = stringResource(R.string.notifications_open_settings),
            onClick = onOpenSettings,
        )
      }
    }
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      state.rows.forEachIndexed { index, row ->
        if (index > 0) SettingsDivider()
        NotificationRow(row = row, onOpen = { onOpen(row.type) })
      }
    }
    SecondaryButton(
        text = stringResource(R.string.notifications_open_settings),
        onClick = onOpenSettings,
    )
  }
}

/** One type: a switch that shows Android's answer, and the phone's own screen behind it. */
@Composable
private fun NotificationRow(
    row: NotificationTypeState,
    onOpen: () -> Unit,
) {
  ListRow(
      title = stringResource(row.type.label),
      trailing = ListRowTrailing.Switch(checked = row.on, onCheckedChange = { onOpen() }),
      onClick = onOpen,
      onClickLabel = stringResource(R.string.notifications_open_channel),
  )
}
