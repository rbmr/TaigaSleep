package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.tracker.RecordingMode

/**
 * The configuration step: which data to record from this sensor, and, when the streams allow more
 * than one, how the tracker records. The choice is stored as it is made.
 */
@Composable
internal fun DataSheet(
    config: StartConfig,
    onEcg: (Boolean) -> Unit,
    onAcc: (Boolean) -> Unit,
    onMode: (RecordingMode) -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.rec_data_title)) },
      text = {
        Column {
          ToggleRow(stringResource(R.string.rec_config_hr), checked = true, enabled = false) {}
          Text(
              text = stringResource(R.string.rec_config_hr_note),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
          )
          if (config.canRecordEcg) {
            ToggleRow(stringResource(R.string.rec_config_ecg), config.streams.ecg) { onEcg(it) }
          }
          if (config.canRecordAcc) {
            ToggleRow(stringResource(R.string.rec_config_acc), config.streams.acc) { onAcc(it) }
          }
          if (config.offeredModes.size > 1) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.rec_mode_title),
                style = MaterialTheme.typography.titleSmall,
            )
            ModeRow(
                label = stringResource(R.string.rec_mode_stream),
                selected = config.mode == RecordingMode.STREAM,
                enabled = RecordingMode.STREAM in config.modes,
            ) {
              onMode(RecordingMode.STREAM)
            }
            ModeRow(
                label = stringResource(R.string.rec_mode_on_device),
                selected = config.mode == RecordingMode.ON_DEVICE,
                enabled = RecordingMode.ON_DEVICE in config.modes,
            ) {
              onMode(RecordingMode.ON_DEVICE)
            }
            Text(
                text =
                    stringResource(
                        when {
                          RecordingMode.ON_DEVICE !in config.modes -> R.string.rec_mode_on_device_unavailable_note
                          config.mode == RecordingMode.ON_DEVICE -> R.string.rec_mode_on_device_note
                          else -> R.string.rec_mode_stream_note
                        }
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
            )
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rec_done)) } },
  )
}

@Composable
private fun ModeRow(label: String, selected: Boolean, enabled: Boolean, onChange: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange() },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = if (enabled) onChange else null, enabled = enabled)
    Spacer(Modifier.width(8.dp))
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    Spacer(Modifier.width(8.dp))
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
  }
}
