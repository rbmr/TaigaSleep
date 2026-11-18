package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.AlarmTestResult
import com.rbmr.taigasleep.recording.WakeAlarmWarning
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The reasons the phone would keep the smart alarm from being heard. Each is a separate line and
 * none of them blocks the alarm: the phone's own choices are reported, never overridden.
 */
@Composable
internal fun WakeWarningNotes(warnings: Set<WakeAlarmWarning>) {
  if (warnings.isEmpty()) return
  Column(
      modifier = Modifier.padding(horizontal = MaterialTheme.taiga.space.m),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.xs),
  ) {
    Text(
        text = stringResource(R.string.wake_warning_title),
        style = MaterialTheme.taiga.text.row,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (warning in WakeAlarmWarning.entries) {
      if (warning !in warnings) continue
      Text(
          text = stringResource(textOf(warning)),
          style = MaterialTheme.taiga.text.meta,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** What the last test ring reported: whether sound started and at which volume. */
@Composable
internal fun WakeTestNote(result: AlarmTestResult?) {
  val text =
      when {
        result == null -> return
        result.audible ->
            stringResource(R.string.wake_test_started, result.volume, result.maxVolume)
        result.started ->
            stringResource(R.string.wake_test_silent, result.volume, result.maxVolume)
        else -> stringResource(R.string.wake_test_failed)
      }
  Text(
      text = text,
      style = MaterialTheme.taiga.text.meta,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = MaterialTheme.taiga.space.m),
  )
}

private fun textOf(warning: WakeAlarmWarning): Int =
    when (warning) {
      WakeAlarmWarning.TOTAL_SILENCE -> R.string.wake_warning_silence
      WakeAlarmWarning.NOTIFICATIONS_OFF -> R.string.wake_warning_notifications
      WakeAlarmWarning.NO_OUTPUT -> R.string.wake_warning_output
    }
