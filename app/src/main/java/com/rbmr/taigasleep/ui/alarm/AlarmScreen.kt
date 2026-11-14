package com.rbmr.taigasleep.ui.alarm

import androidx.annotation.StringRes
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
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The screen of a ringing alarm: a plain black wake-up screen with white text. It says why it rang
 * and what its buttons do with the recording.
 */
@Composable
fun AlarmScreen(
    @StringRes reason: Int,
    @StringRes details: Int?,
    showStop: Boolean,
    showDismiss: Boolean,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
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
          text = stringResource(reason),
          style = MaterialTheme.taiga.text.body,
          color = Color.White,
      )
      if (details != null) {
        Text(
            text = stringResource(details),
            style = MaterialTheme.taiga.text.body,
            color = Color.White,
        )
      }
      Column(
          modifier = Modifier.fillMaxWidth().padding(top = space.l),
          verticalArrangement = Arrangement.spacedBy(space.s),
      ) {
        if (showStop) {
          PrimaryButton(
              text = stringResource(R.string.wake_action_stop),
              onClick = onStop,
              size = ButtonSize.Large,
          )
        }
        if (showDismiss) {
          if (showStop) {
            SecondaryButton(
                text = stringResource(R.string.wake_action_dismiss),
                onClick = onDismiss,
                size = ButtonSize.Large,
                borderColor = Color.White,
                contentColor = Color.White,
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
