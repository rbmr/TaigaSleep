package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton

internal enum class StopAction {
  STOP,
  FORCE_STOP,
  DISCARD,
}

/** Recording: the live numbers and the three ways to end it. Nothing else. */
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
          kicker = stringResource(R.string.home_kicker),
          title = stringResource(R.string.rec_home_title_active),
          subtitle = stringResource(R.string.rec_home_subtitle_active),
      )
    }
    item { HomeLogo(LogoVariant.Sleeping, glow = true) }
    item { RecordingCard(state.status) }
    item {
      PrimaryButton(
          text = stringResource(R.string.rec_stop),
          onClick = onStop,
          enabled = !stopping,
          size = ButtonSize.Large,
      )
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            text = stringResource(R.string.rec_force_stop),
            onClick = onForceStop,
            enabled = !stopping,
            modifier = Modifier.fillMaxWidth(),
        )
        Note(R.string.rec_force_stop_note)
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DestructiveButton(
            text = stringResource(R.string.rec_discard),
            onClick = onDiscard,
            enabled = !stopping,
            modifier = Modifier.fillMaxWidth(),
        )
        Note(R.string.rec_discard_note)
      }
    }
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

/** The confirmation that stands between a tap and ending the night one of three ways. */
@Composable
internal fun ConfirmDialog(action: StopAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  val (title, body, label) =
      when (action) {
        StopAction.STOP ->
            Triple(R.string.rec_stop_title, R.string.rec_stop_body, R.string.rec_stop)
        StopAction.FORCE_STOP ->
            Triple(R.string.rec_force_stop_title, R.string.rec_force_stop_note, R.string.rec_force_stop)
        StopAction.DISCARD ->
            Triple(R.string.rec_discard_title, R.string.rec_discard_body, R.string.rec_discard_confirm)
      }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(title)) },
      text = { Text(stringResource(body)) },
      confirmButton = {
        TextButton(onClick = onConfirm) {
          Text(
              text = stringResource(label),
              color =
                  if (action == StopAction.DISCARD) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.primary,
          )
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rec_cancel)) } },
  )
}
