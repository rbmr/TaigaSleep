package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.ConfirmDialog
import com.rbmr.taigasleep.ui.components.ConfirmDialogStyle
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.TaigaCard

internal enum class StopAction {
  STOP,
  FORCE_STOP,
  DISCARD,
}

/**
 * Recording: the live numbers, Stop as the one prominent action, and the two harsher ways to end
 * the night behind the overflow.
 */
@Composable
internal fun ActiveHome(
    state: HomeUiState.Recording,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onDiscard: () -> Unit,
) {
  val stopping = state.stopping
  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      ScreenHeader(
          title = stringResource(R.string.rec_home_title_active),
          subtitle = stringResource(R.string.rec_home_subtitle_active),
      )
    }
    item { HomeLogo(LogoVariant.Sleeping, glow = true) }
    item { RecordingCard(state.status) }
    item {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        PrimaryButton(
            text = stringResource(R.string.rec_stop),
            onClick = onStop,
            enabled = !stopping,
            size = ButtonSize.Large,
            modifier = Modifier.weight(1f),
        )
        OverflowActions(
            enabled = !stopping,
            onForceStop = onForceStop,
            onDiscard = onDiscard,
        )
      }
    }
  }
}

/** Force stop and Discard: the actions that are not the ordinary way to end a night. */
@Composable
private fun OverflowActions(
    enabled: Boolean,
    onForceStop: () -> Unit,
    onDiscard: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, enabled = enabled) {
      Icon(
          imageVector = Icons.Outlined.MoreVert,
          contentDescription = stringResource(R.string.rec_more_actions),
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
          text = { Text(stringResource(R.string.rec_force_stop)) },
          onClick = {
            open = false
            onForceStop()
          },
      )
      DropdownMenuItem(
          text = {
            Text(
                text = stringResource(R.string.rec_discard),
                color = MaterialTheme.colorScheme.error,
            )
          },
          onClick = {
            open = false
            onDiscard()
          },
      )
    }
  }
}

/** The tracker still holds a night: explain why and offer the only useful action. */
@Composable
internal fun FetchPendingHome(state: HomeUiState.FetchPending, onFetchAgain: () -> Unit) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      ScreenHeader(
          title = stringResource(R.string.rec_fetch_pending_title),
          subtitle = stringResource(R.string.rec_fetch_pending_subtitle),
      )
    }
    item { HomeLogo(LogoVariant.Sleeping, glow = false) }
    item {
      TaigaCard {
        Text(
            text = stringResource(R.string.rec_fetch_pending_body, state.sensorName),
            style = MaterialTheme.typography.bodyLarge,
        )
        state.error?.let {
          Text(
              text = it,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
    item {
      PrimaryButton(
          text = stringResource(R.string.rec_fetch_again),
          onClick = onFetchAgain,
          size = ButtonSize.Large,
      )
    }
    item { Note(R.string.rec_fetch_pending_note) }
  }
}

@Composable
private fun Note(res: Int) {
  Text(
      text = stringResource(res),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * The confirmation that stands between a tap and ending the recording. Each text says what happens
 * to the data in this mode, and the stop one says where the recording goes next.
 */
@Composable
internal fun StopConfirmDialog(
    action: StopAction,
    onDevice: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
  val (title, body, label) =
      when (action) {
        StopAction.STOP ->
            Triple(
                R.string.rec_stop_title,
                if (onDevice) R.string.rec_stop_body_on_device else R.string.rec_stop_body,
                R.string.rec_stop,
            )
        StopAction.FORCE_STOP ->
            Triple(
                R.string.rec_force_stop_title,
                if (onDevice) R.string.rec_force_stop_body_on_device else R.string.rec_force_stop_body,
                R.string.rec_force_stop,
            )
        StopAction.DISCARD ->
            Triple(
                R.string.rec_discard_title,
                if (onDevice) R.string.rec_discard_body_on_device else R.string.rec_discard_body,
                R.string.rec_discard_confirm,
            )
      }
  ConfirmDialog(
      title = stringResource(title),
      body = stringResource(body),
      confirmLabel = stringResource(label),
      onConfirm = onConfirm,
      onDismiss = onDismiss,
      style =
          if (action == StopAction.DISCARD) ConfirmDialogStyle.Destructive
          else ConfirmDialogStyle.Primary,
  )
}
