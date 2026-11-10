package com.rbmr.taigasleep.ui.recording

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.library.formatDuration
import com.rbmr.taigasleep.library.formatRecordingDateAndTime
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.theme.taiga

/** Everything about the recording that is not a result: where its bytes are and what can be done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingDetailsSheet(
    state: RecordingDetailUiState,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onDownloadRaw: () -> Unit,
    onDeleteRaw: () -> Unit,
    onDelete: () -> Unit,
) {
  val context = LocalContext.current
  val local = state.local
  val device = state.detailsDevice
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      contentWindowInsets = { WindowInsets.navigationBars },
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
          text = stringResource(R.string.night_details_title),
          style = MaterialTheme.typography.headlineSmall,
      )
      DetailRow(
          label = stringResource(R.string.night_details_started),
          value = formatRecordingDateAndTime(state.startUtcMs),
      )
      DetailRow(
          label = stringResource(R.string.night_details_stopped),
          value =
              state.endUtcMs?.takeIf { it > 0 }?.let { formatRecordingDateAndTime(it) }
                  ?: "—",
      )
      DetailRow(
          label = stringResource(R.string.night_details_device),
          value = device.ifBlank { stringResource(R.string.night_device_unknown) },
      )
      DetailRow(
          label = stringResource(R.string.night_details_duration),
          value = formatDuration(state.durationMs),
      )
      DetailRow(
          label = stringResource(R.string.night_details_phone),
          value =
              when {
                local?.hasRaw == true ->
                    stringResource(
                        R.string.night_details_raw_on_phone,
                        Formatter.formatShortFileSize(context, state.rawSizeBytes),
                    )
                else -> stringResource(R.string.night_details_not_on_phone)
              },
      )
      when {
        local?.hasRaw == true ->
            TextButton(
                onClick = onDeleteRaw,
                enabled = !state.exportBusy,
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(0.dp),
            ) {
              Text(
                  text = stringResource(R.string.night_details_delete_raw),
                  color = MaterialTheme.taiga.bad,
              )
            }
        local != null && state.remoteId != null ->
            TextButton(
                onClick = onDownloadRaw,
                enabled = !state.exportBusy,
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(0.dp),
            ) {
              Text(text = stringResource(R.string.night_details_download_raw))
            }
      }
      if (state.exportBusy) {
        val percent = ((state.exportProgress ?: 0f) * 100).toInt().coerceIn(0, 100)
        if (state.exportProgress != null) {
          LinearProgressIndicator(
              progress = { state.exportProgress.coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth(),
          )
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text =
                when (state.exportPhase) {
                  ExportPhase.DOWNLOADING ->
                      stringResource(R.string.night_details_downloading, percent)
                  ExportPhase.ZIPPING -> stringResource(R.string.night_details_zipping, percent)
                  null -> stringResource(R.string.night_details_preparing)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      DetailRow(
          label = stringResource(R.string.night_details_server),
          value =
              when {
                state.remoteId == null -> stringResource(R.string.night_details_not_uploaded)
                else ->
                    state.remoteCreatedAtMs?.takeIf { it > 0 }?.let {
                      formatRecordingDateAndTime(it)
                    } ?: "—"
              },
      )
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PrimaryButton(
            text = stringResource(R.string.night_export_action),
            onClick = onExport,
            enabled = state.canExport && !state.exportBusy,
            modifier = Modifier.weight(1f),
        )
        DestructiveButton(
            text =
                stringResource(
                    if (state.remoteId == null) R.string.night_discard_action
                    else R.string.night_delete_action
                ),
            onClick = onDelete,
            enabled = !state.exportBusy,
            modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End,
        modifier = Modifier.weight(1.4f),
    )
  }
}
