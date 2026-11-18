package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.AlarmTestResult
import com.rbmr.taigasleep.recording.WakeAlarmWarning
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.SegmentedControl
import com.rbmr.taigasleep.ui.components.TaigaSheet
import com.rbmr.taigasleep.ui.components.TimePickerDialog
import com.rbmr.taigasleep.ui.components.TimePickerText
import com.rbmr.taigasleep.ui.theme.LocalFormats
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The configuration sheet, in up to three sections: the recording mode, the data to record and the
 * smart alarm. A section appears only when the connected tracker offers a choice in it. Every
 * choice is stored as it is made. Only the mode drives the others: rows never appear, disappear or
 * move, they enable and disable, and the choices made for streaming come back with it.
 *
 * Rows fill the sheet width and carry their own 16 dp inset, so headings and notes use the same
 * inset and every text starts on one edge.
 */
@Suppress("LongParameterList")
@Composable
internal fun ConfigureSheet(
    config: StartConfig,
    onStream: (String, Boolean) -> Unit,
    onMode: (RecordingMode) -> Unit,
    onWake: (WakeChoice?) -> Unit,
    wakeWarnings: Set<WakeAlarmWarning>,
    alarmTest: AlarmTestResult?,
    onTestAlarm: () -> Unit,
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
      if (config.modes.size > 1) ModeSection(config, onMode)
      DataSection(config, onStream)
      if (RecordingMode.STREAM in config.modes) {
        WakeSection(config, onWake, wakeWarnings, alarmTest, onTestAlarm)
      }
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
private fun DataSection(
    config: StartConfig,
    onStream: (String, Boolean) -> Unit,
) {
  val streaming = !config.onTracker
  Column {
    SectionTitle(stringResource(R.string.rec_data_title))
    // Only the streams this tracker offers are shown; a choice the mode rules out stays in place
    // and is disabled, so the sheet says what is available rather than what is missing.
    for (option in config.options) {
      val chosen = option.id in config.streams.chosen
      ListRow(
          title = stringResource(labelOf(option.type)),
          trailing = ListRowTrailing.Switch(chosen),
          enabled =
              streaming ||
                  option.type == StreamType.HEART_RATE ||
                  option.type == StreamType.RR_INTERVAL,
          onClick = { onStream(option.id, !chosen) },
      )
    }
  }
}

/** The name the sheet uses for a stream type, with the detail that tells its options apart. */
private fun labelOf(type: StreamType): Int =
    when (type) {
      StreamType.HEART_RATE -> R.string.rec_config_hr
      StreamType.RR_INTERVAL -> R.string.rec_config_rr
      StreamType.ECG -> R.string.rec_config_ecg
      StreamType.ACCELEROMETER -> R.string.rec_config_acc
      StreamType.PPG -> R.string.rec_config_ppg
      StreamType.PULSE_INTERVAL -> R.string.rec_config_ppi
      StreamType.GYROSCOPE -> R.string.rec_config_gyro
      StreamType.MAGNETOMETER -> R.string.rec_config_mag
    }

@Composable
private fun ModeSection(config: StartConfig, onMode: (RecordingMode) -> Unit) {
  val onTracker = config.onTracker
  Column {
    SectionTitle(stringResource(R.string.rec_mode_title))
    ModeRow(
        label = stringResource(R.string.rec_mode_stream),
        selected = !onTracker,
    ) {
      onMode(RecordingMode.STREAM)
    }
    ModeRow(
        label = stringResource(R.string.rec_mode_on_device),
        selected = onTracker,
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
private fun ModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
  ListRow(
      title = label,
      trailing = ListRowTrailing.Radio(selected),
      onClick = onSelect,
  )
}

/**
 * The smart alarm: off until the user turns it on, and not usable for a recording the tracker
 * stores itself, because nothing streams to the phone then. Its rows are always there and are
 * disabled until the alarm can be used. It opens at a time and stays open for a number of minutes,
 * so moving it takes one field.
 */
@Composable
private fun WakeSection(
    config: StartConfig,
    onWake: (WakeChoice?) -> Unit,
    wakeWarnings: Set<WakeAlarmWarning>,
    alarmTest: AlarmTestResult?,
    onTestAlarm: () -> Unit,
) {
  val wake = config.wake
  val shown = wake ?: DEFAULT_WAKE
  val on = wake != null
  Column {
    SectionTitle(stringResource(R.string.wake_title))
    ListRow(
        title = stringResource(R.string.wake_enable),
        trailing = ListRowTrailing.Switch(on),
        enabled = !config.onTracker,
        onClick = { onWake(if (on) null else DEFAULT_WAKE) },
    )
    TimeRow(stringResource(R.string.wake_start), shown.startMinute, on) { picked ->
      onWake(shown.copy(startMinute = picked))
    }
    LengthRow(shown.lengthMinutes, on) { onWake(shown.copy(lengthMinutes = it)) }
    val heartRateRecorded = config.records(StreamType.HEART_RATE)
    val heartRateOn = on && shown.heartRate && heartRateRecorded
    ListRow(
        title = stringResource(R.string.wake_trigger_hr),
        trailing = ListRowTrailing.Switch(heartRateOn),
        enabled = on && heartRateRecorded,
        onClick = { onWake(shown.copy(heartRate = !heartRateOn)) },
    )
    if (config.canRecordAcc) {
      val movementRecorded = config.records(StreamType.ACCELEROMETER)
      val movementOn = on && shown.movement && movementRecorded
      ListRow(
          title = stringResource(R.string.wake_trigger_movement),
          trailing = ListRowTrailing.Switch(movementOn),
          enabled = on && movementRecorded,
          onClick = { onWake(shown.copy(movement = !movementOn)) },
      )
    }
    if (on && !shown.triggers) SectionNote(stringResource(R.string.wake_trigger_none))
    ListRow(
        title = stringResource(R.string.wake_test),
        trailing = ListRowTrailing.None,
        enabled = !config.onTracker,
        onClick = onTestAlarm,
    )
    WakeTestNote(alarmTest)
    if (on) WakeWarningNotes(wakeWarnings)
  }
}

/**
 * The smart alarm of a running recording: the same rows as before the start. A signal can only be
 * chosen when the recording captures its data, so the other one stays in place, disabled. A change
 * takes effect at once and is also the tracker's saved window.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun LiveWakeSheet(
    wake: LiveWake,
    wakeWarnings: Set<WakeAlarmWarning>,
    alarmTest: AlarmTestResult?,
    onWake: (WakeChoice?) -> Unit,
    onTestAlarm: () -> Unit,
    onDismiss: () -> Unit,
) {
  val on = wake.choice != null
  val shown = wake.choice ?: wake.fallback
  TaigaSheet(onDismiss = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = MaterialTheme.taiga.space.l)) {
      SectionTitle(stringResource(R.string.wake_title))
      ListRow(
          title = stringResource(R.string.wake_enable),
          trailing = ListRowTrailing.Switch(on),
          onClick = { onWake(if (on) null else shown) },
      )
      TimeRow(stringResource(R.string.wake_start), shown.startMinute, on) { picked ->
        onWake(shown.copy(startMinute = picked))
      }
      LengthRow(shown.lengthMinutes, on) { onWake(shown.copy(lengthMinutes = it)) }
      val heartRateOn = on && shown.heartRate && wake.heartRateRecorded
      ListRow(
          title = stringResource(R.string.wake_trigger_hr),
          trailing = ListRowTrailing.Switch(heartRateOn),
          enabled = on && wake.heartRateRecorded,
          onClick = { onWake(shown.copy(heartRate = !heartRateOn)) },
      )
      if (wake.movementSupported) {
        val movementOn = on && shown.movement && wake.movementRecorded
        ListRow(
            title = stringResource(R.string.wake_trigger_movement),
            trailing = ListRowTrailing.Switch(movementOn),
            enabled = on && wake.movementRecorded,
            onClick = { onWake(shown.copy(movement = !movementOn)) },
        )
      }
      val triggers =
          (shown.heartRate && wake.heartRateRecorded) || (shown.movement && wake.movementRecorded)
      if (on && !triggers) SectionNote(stringResource(R.string.wake_trigger_none))
      ListRow(
          title = stringResource(R.string.wake_test),
          trailing = ListRowTrailing.None,
          onClick = onTestAlarm,
      )
      WakeTestNote(alarmTest)
      if (on) WakeWarningNotes(wakeWarnings)
    }
  }
}

/** How long the window stays open: one choice out of a few lengths, the current one included. */
@Composable
private fun LengthRow(minutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
  val space = MaterialTheme.taiga.space
  val lengths = (WakeChoice.LENGTH_CHOICES + minutes).distinct().sorted()
  Column(
      modifier = Modifier.padding(horizontal = space.m, vertical = space.xs),
      verticalArrangement = Arrangement.spacedBy(space.xs),
  ) {
    Text(
        text = stringResource(R.string.wake_length),
        style = MaterialTheme.taiga.text.row,
        color =
            if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.taiga.disabled,
    )
    SegmentedControl(
        options = lengths.map { stringResource(R.string.wake_minutes, it) },
        selectedIndex = lengths.indexOf(minutes),
        onSelect = { onChange(lengths[it]) },
        modifier = Modifier.fillMaxWidth(),
        fillWidth = true,
        enabled = enabled,
    )
  }
}

/** The time of day the window opens at; the picker is one dialog and one value. */
@Composable
internal fun TimeRow(label: String, minute: Int, enabled: Boolean, onChange: (Int) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  val formats = LocalFormats.current
  ListRow(
      title = label,
      value = formats.minuteOfDay(minute),
      trailing = ListRowTrailing.None,
      enabled = enabled,
      onClick = { editing = true },
  )
  if (editing) {
    TimePickerDialog(
        minuteOfDay = minute,
        is24Hour = formats.is24Hour,
        text = TimePickerText(title = label, confirm = stringResource(R.string.rec_done)),
        onConfirm = onChange,
        onDismiss = { editing = false },
    )
  }
}
