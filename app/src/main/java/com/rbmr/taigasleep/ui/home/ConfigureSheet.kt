package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.TaigaSheet
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The configuration sheet, in up to three sections: the data to record, the recording mode and the
 * wake-up window. A section appears only when the connected tracker offers a choice in it. Every
 * choice is stored as it is made.
 *
 * Rows fill the sheet width and carry their own 16 dp inset, so headings and notes use the same
 * inset and every text starts on one edge.
 */
@Composable
internal fun ConfigureSheet(
    config: StartConfig,
    onEcg: (Boolean) -> Unit,
    onAcc: (Boolean) -> Unit,
    onMode: (RecordingMode) -> Unit,
    onWake: (WakeChoice?) -> Unit,
    onDismiss: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  TaigaSheet(onDismiss = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.l),
    ) {
      if (config.canRecordEcg || config.canRecordAcc) DataSection(config, onEcg, onAcc)
      if (config.offeredModes.size > 1) ModeSection(config, onMode)
      if (RecordingMode.STREAM in config.offeredModes) WakeSection(config, onWake)
    }
  }
}

/** A section title, inset like the rows under it. */
@Composable
private fun SectionTitle(text: String) {
  Text(
      text = text,
      style = MaterialTheme.taiga.text.section,
      modifier = Modifier.padding(horizontal = MaterialTheme.taiga.space.m),
  )
}

/** A line of explanation under a section's rows, on the rows' text edge. */
@Composable
private fun SectionNote(text: String) {
  Text(
      text = text,
      style = MaterialTheme.taiga.text.meta,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = MaterialTheme.taiga.space.m),
  )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
  ListRow(
      title = title,
      subtitle = subtitle,
      trailing = ListRowTrailing.Switch(checked),
      enabled = enabled,
      onClick = { onChange(!checked) },
  )
}

@Composable
private fun DataSection(config: StartConfig, onEcg: (Boolean) -> Unit, onAcc: (Boolean) -> Unit) {
  Column {
    SectionTitle(stringResource(R.string.rec_data_title))
    SwitchRow(
        title = stringResource(R.string.rec_config_hr),
        checked = true,
        enabled = false,
        subtitle = stringResource(R.string.rec_config_hr_note),
    ) {}
    if (config.canRecordEcg) {
      SwitchRow(stringResource(R.string.rec_config_ecg), config.streams.ecg, onChange = onEcg)
    }
    if (config.canRecordAcc) {
      SwitchRow(stringResource(R.string.rec_config_acc), config.streams.acc, onChange = onAcc)
    }
  }
}

@Composable
private fun ModeSection(config: StartConfig, onMode: (RecordingMode) -> Unit) {
  val onTracker = config.onTracker
  Column {
    SectionTitle(stringResource(R.string.rec_mode_title))
    ModeRow(
        label = stringResource(R.string.rec_mode_stream),
        selected = !onTracker,
        enabled = RecordingMode.STREAM in config.modes,
    ) {
      onMode(RecordingMode.STREAM)
    }
    ModeRow(
        label = stringResource(R.string.rec_mode_on_device),
        selected = onTracker,
        enabled = RecordingMode.ON_DEVICE in config.modes,
    ) {
      onMode(RecordingMode.ON_DEVICE)
    }
    SectionNote(
        stringResource(
            if (onTracker) R.string.rec_mode_on_device_note else R.string.rec_mode_stream_note
        )
    )
  }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
  ListRow(
      title = label,
      trailing = ListRowTrailing.Radio(selected),
      enabled = enabled,
      onClick = onSelect,
  )
}

/**
 * The wake-up window: off until the user turns it on, and not usable for a recording the tracker
 * stores itself, because nothing streams to the phone then.
 */
@Composable
private fun WakeSection(config: StartConfig, onWake: (WakeChoice?) -> Unit) {
  val onTracker = config.onTracker
  val wake = config.wake?.takeIf { !onTracker }
  Column {
    SectionTitle(stringResource(R.string.wake_title))
    SwitchRow(
        title = stringResource(R.string.wake_enable),
        checked = wake != null,
        enabled = !onTracker,
        subtitle =
            stringResource(
                when {
                  onTracker -> R.string.wake_on_device_note
                  wake == null -> R.string.wake_off_note
                  else -> R.string.wake_on_note
                }
            ),
    ) {
      onWake(if (wake == null) DEFAULT_WAKE else null)
    }
    if (wake != null) {
      TimeRow(stringResource(R.string.wake_from), wake.startMinute) { picked ->
        if (picked != wake.endMinute) onWake(wake.copy(startMinute = picked))
      }
      TimeRow(stringResource(R.string.wake_to), wake.endMinute) { picked ->
        if (picked != wake.startMinute) onWake(wake.copy(endMinute = picked))
      }
      SwitchRow(stringResource(R.string.wake_trigger_hr), wake.heartRate) {
        onWake(wake.copy(heartRate = it))
      }
      SwitchRow(
          title = stringResource(R.string.wake_trigger_movement),
          checked = wake.movement,
          enabled = config.streams.acc,
          subtitle =
              if (config.streams.acc) null else stringResource(R.string.wake_trigger_movement_off),
      ) {
        onWake(wake.copy(movement = it))
      }
      if (!wake.triggers) SectionNote(stringResource(R.string.wake_trigger_none))
    }
  }
}

/** A time of day the window starts or ends at; the picker is one dialog and one value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(label: String, minute: Int, onChange: (Int) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  ListRow(
      title = label,
      value = stringResource(R.string.wake_time, minute / 60, minute % 60),
      trailing = ListRowTrailing.None,
      onClick = { editing = true },
  )
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

/** Turning the window on starts from a time most people wake at; the user moves it from there. */
private val DEFAULT_WAKE = WakeChoice(startMinute = 6 * 60 + 30, endMinute = 7 * 60)
