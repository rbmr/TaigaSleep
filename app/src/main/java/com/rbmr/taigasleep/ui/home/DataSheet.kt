package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import com.rbmr.taigasleep.tracker.RecordingMode

/**
 * The configuration step: which data to record from this sensor, how the tracker records when the
 * streams allow more than one, and the wake-up window of the night. Every choice is stored as it is
 * made.
 */
@Composable
internal fun DataSheet(
    config: StartConfig,
    onEcg: (Boolean) -> Unit,
    onAcc: (Boolean) -> Unit,
    onMode: (RecordingMode) -> Unit,
    onWake: (WakeChoice?) -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.rec_data_title)) },
      text = {
        // The sheet grew with the wake-up window; a small screen scrolls instead of clipping it.
        Column(Modifier.verticalScroll(rememberScrollState())) {
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
          Spacer(Modifier.height(16.dp))
          WakeSection(config, onWake)
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rec_done)) } },
  )
}

/**
 * The wake-up window: off until the user turns it on, and not offered for a night the tracker stores
 * itself, because nothing streams to the phone then.
 */
@Composable
private fun WakeSection(config: StartConfig, onWake: (WakeChoice?) -> Unit) {
  val onDevice = config.mode == RecordingMode.ON_DEVICE
  val wake = config.wake
  Row(
      modifier =
          Modifier.fillMaxWidth().clickable(enabled = !onDevice) {
            onWake(if (wake == null) DEFAULT_WAKE else null)
          },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Switch(checked = wake != null, onCheckedChange = null, enabled = !onDevice)
    Spacer(Modifier.width(8.dp))
    Text(
        text = stringResource(R.string.wake_title),
        style = MaterialTheme.typography.titleSmall,
        color =
            if (onDevice) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else MaterialTheme.colorScheme.onSurface,
    )
  }
  when {
    onDevice ->
        Note(stringResource(R.string.wake_on_device_note))
    wake == null ->
        Note(stringResource(R.string.wake_off_note))
    else -> {
      Note(stringResource(R.string.wake_on_note))
      TimeRow(stringResource(R.string.wake_from), wake.startMinute) { picked ->
        if (picked != wake.endMinute) onWake(wake.copy(startMinute = picked))
      }
      TimeRow(stringResource(R.string.wake_to), wake.endMinute) { picked ->
        if (picked != wake.startMinute) onWake(wake.copy(endMinute = picked))
      }
      Text(
          text = stringResource(R.string.wake_trigger_title),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 8.dp, start = 16.dp),
      )
      ToggleRow(stringResource(R.string.wake_trigger_hr), wake.heartRate) {
        onWake(wake.copy(heartRate = it))
      }
      ToggleRow(
          stringResource(R.string.wake_trigger_movement),
          wake.movement,
          enabled = config.streams.acc,
      ) {
        onWake(wake.copy(movement = it))
      }
      if (!config.streams.acc) Note(stringResource(R.string.wake_trigger_movement_off))
      if (!wake.triggers) Note(stringResource(R.string.wake_trigger_none))
    }
  }
}

/** A time of day the window starts or ends at; the picker is one dialog and one value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(label: String, minute: Int, onChange: (Int) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  Row(
      modifier = Modifier.fillMaxWidth().clickable { editing = true },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.weight(1f))
    Text(text = timeText(minute), style = MaterialTheme.typography.bodyLarge)
  }
  if (editing) {
    val state =
        rememberTimePickerState(
            initialHour = minute / 60,
            initialMinute = minute % 60,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = { editing = false },
        text = { TimeInput(state = state) },
        confirmButton = {
          TextButton(
              onClick = {
                onChange(state.hour * 60 + state.minute)
                editing = false
              }
          ) {
            Text(stringResource(R.string.rec_done))
          }
        },
    )
  }
}

@Composable
private fun Note(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
  )
}

@Composable
private fun timeText(minute: Int): String =
    stringResource(R.string.wake_time, minute / 60, minute % 60)

/** Turning the window on starts from a time most people wake at; the user moves it from there. */
private val DEFAULT_WAKE = WakeChoice(startMinute = 6 * 60 + 30, endMinute = 7 * 60)

@Composable
private fun ModeRow(label: String, selected: Boolean, enabled: Boolean, onChange: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange() },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = if (enabled) onChange else null, enabled = enabled)
    Spacer(Modifier.width(8.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color =
            if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )
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
