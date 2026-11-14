package com.rbmr.taigasleep.ui.home

import com.rbmr.taigasleep.ui.components.joinParts
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
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.ui.components.BearDen
import com.rbmr.taigasleep.ui.components.ButtonSize
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
  val needsData = connected && view.config?.canStart == false
  val needsGuard = view.guard == HomeState.NEEDS_SETTINGS
  val headline =
      when {
        !connected -> R.string.home_headline_connect
        needsData -> R.string.home_headline_data
        needsGuard -> R.string.home_headline_one_step
        else -> R.string.home_headline_ready
      }
  val (actionLabel, action) =
      when {
        !connected -> R.string.home_action_connect to onSelectTracker
        needsData -> R.string.home_action_data to onDataSettings
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
        variant = LogoVariant.Sleeping,
        tapEffect = DenTapEffect.Wake,
        contentDescription = stringResource(R.string.home_logo_sleeping),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = space.m).padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Text(
          text = stringResource(headline),
          style = MaterialTheme.taiga.text.screenTitle,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
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
        title = view.tracker?.name ?: stringResource(R.string.home_tracker_title),
        icon = Icons.Outlined.Sensors,
        subtitle =
            if (connected) {
              view.tracker?.battery?.let { stringResource(R.string.rec_battery, it) }
            } else {
              stringResource(R.string.home_tracker_hint)
            },
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
    if (config != null) {
      SettingsDivider()
      ListRow(
          title = stringResource(R.string.rec_configure_title),
          icon = Icons.AutoMirrored.Outlined.List,
          subtitle = streamsText(config),
          onClick = onDataSettings,
      )
    }
    SettingsDivider()
    ListRow(
        title = stringResource(R.string.settings_prevent_title),
        icon = Icons.Outlined.Shield,
        trailing =
            ListRowTrailing.Pill(label = stringResource(guardLabel), state = guardPill),
        onClick = onGuard,
        onLongClick = onGuardLongClick,
    )
  }
}

/** The chosen streams and mode as one line, e.g. `Heart rate · ECG · Streaming`. */
@Composable
private fun streamsText(config: StartConfig): String {
  if (!config.streams.any) return stringResource(R.string.rec_config_none)
  val parts = mutableListOf<String>()
  if (config.streams.hr) parts += stringResource(R.string.rec_active_hr)
  if (config.streams.ecg) parts += stringResource(R.string.rec_active_ecg)
  if (config.streams.acc) parts += stringResource(R.string.rec_active_acc)
  if (config.modes.size > 1) {
    parts +=
        stringResource(
            if (config.onTracker) R.string.rec_mode_summary_on_device
            else R.string.rec_mode_summary_stream
        )
  }
  return joinParts(parts).orEmpty()
}
