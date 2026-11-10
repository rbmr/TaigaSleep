package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.FinishingState
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TertiaryButton

/**
 * After Stop: the steps that apply to this recording, and the card that says the night is safely
 * on the phone. The tracker fetch has no byte progress from the SDK, so it shows what the import
 * knows instead: which entry of how many, and how many samples are stored already.
 */
@Composable
internal fun FinishingHome(
    state: HomeUiState.Finishing,
    onOpenRecording: () -> Unit,
    onDismiss: () -> Unit,
) {
  val finishing = state.finishing
  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      ScreenHeader(
          title = stringResource(R.string.rec_finishing_title),
          subtitle = stringResource(R.string.rec_finishing_subtitle),
      )
    }
    item { HomeLogo(LogoVariant.Sleeping, glow = false) }
    item {
      TaigaCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          finishing.steps.forEach { step ->
            val stepState = stepState(finishing, step)
            FinishStepRow(label = stringResource(label(step)), state = stepState)
            if (step == FinishingState.Step.FETCHING && stepState == StepState.CURRENT) {
              // No byte progress from the tracker, so the bar does not pretend to know a percentage.
              LinearProgressIndicator(Modifier.fillMaxWidth())
              Text(
                  text = fetchText(finishing),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
    if (finishing.done) {
      item {
        Text(
            text = stringResource(R.string.rec_finishing_done),
            style = MaterialTheme.typography.bodyLarge,
        )
      }
      item {
        PrimaryButton(
            text = stringResource(R.string.rec_finishing_open),
            onClick = onOpenRecording,
            size = ButtonSize.Large,
        )
      }
      item {
        TertiaryButton(
            text = stringResource(R.string.rec_finishing_close),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/** Done steps are checked, the step in progress spins and the rest wait. */
private enum class StepState {
  DONE,
  CURRENT,
  PENDING,
}

private fun stepState(finishing: FinishingState, step: FinishingState.Step): StepState =
    when {
      finishing.step == step && !finishing.done -> StepState.CURRENT
      step.ordinal < finishing.step.ordinal || finishing.done -> StepState.DONE
      else -> StepState.PENDING
    }

private fun label(step: FinishingState.Step): Int =
    when (step) {
      FinishingState.Step.STOPPING_TRACKER -> R.string.rec_finishing_step_tracker
      FinishingState.Step.FETCHING -> R.string.rec_finishing_step_fetch
      FinishingState.Step.SAVING -> R.string.rec_finishing_step_save
      FinishingState.Step.DONE -> R.string.rec_finishing_step_done
    }

@Composable
private fun fetchText(finishing: FinishingState): String {
  val fetch = finishing.fetch
  if (fetch == null || fetch.entry == 0) {
    return stringResource(R.string.rec_finishing_fetch_reading)
  }
  val entry = stringResource(R.string.rec_finishing_fetch_entry, fetch.entry, fetch.entries)
  return if (fetch.samples > 0) {
    "$entry · " + pluralStringResource(R.plurals.rec_finishing_samples, fetch.samples, fetch.samples)
  } else {
    entry
  }
}

@Composable
private fun FinishStepRow(label: String, state: StepState) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    when (state) {
      StepState.DONE ->
          Icon(
              imageVector = Icons.Outlined.Check,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
          )
      StepState.CURRENT ->
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
      StepState.PENDING ->
          Icon(
              imageVector = Icons.Outlined.RadioButtonUnchecked,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.outlineVariant,
              modifier = Modifier.size(18.dp),
          )
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(
          text = label,
          style = MaterialTheme.typography.bodyLarge,
          color =
              if (state == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
