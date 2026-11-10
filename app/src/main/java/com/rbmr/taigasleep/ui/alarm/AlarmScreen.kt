package com.rbmr.taigasleep.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.TaigaLogo

/**
 * The screen of a ringing alarm: one thing to read and one way out. It says that the recording goes
 * on, because dismissing the alarm is not stopping the night.
 */
@Composable
fun AlarmScreen(fullScreenAllowed: Boolean, onDismiss: () -> Unit) {
  Box(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.background)
              .systemBarsPadding()
              .padding(horizontal = 32.dp, vertical = 24.dp),
      contentAlignment = Alignment.Center,
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      TaigaLogo(
          modifier = Modifier.width(150.dp),
          variant = LogoVariant.Awake,
          glow = true,
          contentDescription = stringResource(R.string.wake_screen_title),
      )
      Text(
          text = stringResource(R.string.wake_screen_title),
          style = MaterialTheme.typography.displaySmall,
          textAlign = TextAlign.Center,
      )
      Text(
          text = stringResource(R.string.wake_screen_body),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      if (!fullScreenAllowed) {
        Text(
            text = stringResource(R.string.wake_screen_full_screen),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
      }
      PrimaryButton(
          text = stringResource(R.string.wake_screen_dismiss),
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          size = ButtonSize.Large,
      )
    }
  }
}
