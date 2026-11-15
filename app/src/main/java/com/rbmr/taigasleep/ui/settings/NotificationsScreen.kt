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
import com.rbmr.taigasleep.notifications.NotificationTypeState
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * Settings → Notifications: every notification type the app can send. The optional ones have a
 * switch of the app's own; the required ones only say whether Android allows them. A row opens the
 * phone's settings for its channel, where its sound and importance live.
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
            onClick = model::openAppNotificationSettings,
        )
      }
    }
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      state.rows.forEachIndexed { index, row ->
        if (index > 0) SettingsDivider()
        NotificationRow(
            row = row,
            onSwitch = { on -> model.setOn(row.type, on) },
            onClick = { model.openSettings(row.type) },
        )
      }
    }
  }
}

/** One type: the app's switch when it has one, and the phone's own screen behind the row. */
@Composable
private fun NotificationRow(
    row: NotificationTypeState,
    onSwitch: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
  val trailing =
      if (row.hasSwitch) {
        ListRowTrailing.Switch(checked = row.on, onCheckedChange = onSwitch)
      } else {
        ListRowTrailing.Pill(
            label =
                stringResource(
                    if (row.allowed) R.string.notifications_allowed
                    else R.string.notifications_blocked
                ),
            state = if (row.allowed) StatusPillState.Ok else StatusPillState.Warn,
        )
      }
  ListRow(
      title = stringResource(row.type.label),
      trailing = trailing,
      onClick = onClick,
      onClickLabel = stringResource(R.string.notifications_open_channel),
  )
}
