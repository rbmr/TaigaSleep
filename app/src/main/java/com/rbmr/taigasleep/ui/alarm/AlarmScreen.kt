package com.rbmr.taigasleep.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.WakeAlarmAction
import com.rbmr.taigasleep.recording.WakeReason
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The screen of a ringing alarm: a plain black wake-up screen with white text. It says why it rang
 * and, when it offers two buttons, what each of them does with the recording.
 */
@Composable
fun AlarmScreen(
    reason: WakeReason,
    actions: List<WakeAlarmAction>,
    recordingRunning: Boolean,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  val stop = WakeAlarmAction.STOP in actions
  val dismiss = WakeAlarmAction.DISMISS in actions
  val details =
      when {
        stop && dismiss -> R.string.wake_screen_offer_both
        stop -> R.string.wake_screen_stop_ends
        recordingRunning -> R.string.wake_screen_dismiss_running
        else -> R.string.wake_screen_dismiss_only
      }
  Box(
      modifier =
          Modifier.fillMaxSize()
              .background(Color.Black)
              .systemBarsPadding()
              .padding(horizontal = space.l, vertical = space.l),
      contentAlignment = Alignment.Center,
  ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Text(
          text = stringResource(R.string.wake_screen_heading),
          style = MaterialTheme.taiga.text.hero,
          color = Color.White,
      )
      Text(
          text = stringResource(reason.text),
          style = MaterialTheme.taiga.text.body,
          color = Color.White,
      )
      Text(
          text = stringResource(details),
          style = MaterialTheme.taiga.text.section,
          color = Color.White,
      )
      Column(
          modifier = Modifier.fillMaxWidth().padding(top = space.s),
          verticalArrangement = Arrangement.spacedBy(space.s),
      ) {
        if (stop) {
          PrimaryButton(
              text = stringResource(R.string.wake_action_stop),
              onClick = onStop,
              size = ButtonSize.Large,
          )
        }
        if (dismiss) {
          if (stop) {
            SecondaryButton(
                text = stringResource(R.string.wake_action_dismiss),
                onClick = onDismiss,
                size = ButtonSize.Large,
                borderColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
          } else {
            PrimaryButton(
                text = stringResource(R.string.wake_action_dismiss),
                onClick = onDismiss,
                size = ButtonSize.Large,
            )
          }
        }
      }
    }
  }
}
