package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.ui.components.BearDen
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.DenSize
import com.rbmr.taigasleep.ui.components.DenTapEffect
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * Home before a recording: the den, one headline that says what is next and the checklist that ends
 * with the button of that next step. A row opens the step it stands for, so no button is ever
 * disabled with a hint below it.
 */
@Composable
internal fun IdleHome(
    view: InactiveView,
    onSelectTracker: () -> Unit,
    onDataSettings: () -> Unit,
    onGuard: () -> Unit,
    onGuardLongClick: (() -> Unit)?,
    onStart: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  val connected = view.tracker?.connection == ConnectionState.CONNECTED
  val needsGuard = view.guard == HomeState.NEEDS_SETTINGS
  val awake = !connected || needsGuard
  val headline =
      when {
        !connected -> R.string.home_headline_connect
        needsGuard -> R.string.home_headline_one_step
        else -> R.string.home_headline_ready
      }
  val (actionLabel, action) =
      when {
        !connected -> R.string.home_action_connect to onSelectTracker
        needsGuard -> R.string.home_action_guard to onGuard
        else -> R.string.home_start to onStart
      }
  val problem =
      view.error
          ?: if (view.denied) stringResource(R.string.rec_permission_denied) else null

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surface)
              .verticalScroll(rememberScrollState())
  ) {
    BearDen(
        size = DenSize.Header,
        variant = if (awake) LogoVariant.Awake else LogoVariant.Sleeping,
        tapEffect = DenTapEffect.Wake,
        contentDescription =
            stringResource(
                if (awake) R.string.home_logo_awake else R.string.home_logo_sleeping
            ),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = space.l).padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Text(
          text = stringResource(headline),
          style = MaterialTheme.taiga.text.screenTitle,
      )
      ChecklistCard(
          view = view,
          connected = connected,
          onSelectTracker = onSelectTracker,
          onDataSettings = onDataSettings,
          onGuard = onGuard,
          onGuardLongClick = onGuardLongClick,
      )
      problem?.let {
        Text(
            text = it,
            style = MaterialTheme.taiga.text.body,
            color = MaterialTheme.colorScheme.error,
        )
      }
      PrimaryButton(
          text = stringResource(actionLabel),
          onClick = action,
          size = ButtonSize.Large,
      )
    }
  }
}

/** The three steps in the order they depend on each other: tracker, data, preventing shutdown. */
@Composable
private fun ChecklistCard(
    view: InactiveView,
    connected: Boolean,
    onSelectTracker: () -> Unit,
    onDataSettings: () -> Unit,
    onGuard: () -> Unit,
    onGuardLongClick: (() -> Unit)?,
) {
  val config = view.config
  val (guardLabel, guardPill) =
      when (view.guard) {
        HomeState.READY -> R.string.home_pill_done to StatusPillState.Ok
        HomeState.NEEDS_SETTINGS -> R.string.home_pill_needed to StatusPillState.Warn
        HomeState.NOT_NEEDED -> R.string.home_pill_not_needed to StatusPillState.Neutral
      }
  TaigaCard(contentPadding = PaddingValues(vertical = MaterialTheme.taiga.space.xs)) {
    ListRow(
        title = view.tracker?.name ?: stringResource(R.string.rec_sensor_none_title),
        icon = Icons.Outlined.Sensors,
        value = view.tracker?.battery?.let { stringResource(R.string.rec_battery, it) },
        trailing =
            ListRowTrailing.Pill(
                label =
                    stringResource(
                        if (connected) R.string.rec_connection_connected
                        else R.string.rec_connection_disconnected
                    ),
                state = if (connected) StatusPillState.Ok else StatusPillState.Neutral,
            ),
        onClick = onSelectTracker,
    )
    if (config != null && (config.canRecordEcg || config.canRecordAcc)) {
      SettingsDivider()
      ListRow(
          title = stringResource(R.string.rec_data_settings),
          value = streamsText(config),
          onClick = onDataSettings,
      )
    }
    SettingsDivider()
    ListRow(
        title = stringResource(R.string.settings_prevent_title),
        trailing =
            ListRowTrailing.Pill(label = stringResource(guardLabel), state = guardPill),
        onClick = onGuard,
        onLongClick = onGuardLongClick,
    )
  }
}

/** The chosen streams as one line, e.g. `Heart rate · ECG · Movement`. */
@Composable
private fun streamsText(config: StartConfig): String {
  val parts = mutableListOf(stringResource(R.string.rec_active_hr))
  if (config.streams.ecg) parts += stringResource(R.string.rec_active_ecg)
  if (config.streams.acc) parts += stringResource(R.string.rec_active_acc)
  return parts.joinToString(" · ")
}
