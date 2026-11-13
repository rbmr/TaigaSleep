package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.FinishingState
import com.rbmr.taigasleep.ui.components.BearDen
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.DenSize
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StepItem
import com.rbmr.taigasleep.ui.components.StepList
import com.rbmr.taigasleep.ui.components.StepProgress
import com.rbmr.taigasleep.ui.components.StepState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * After Stop: the steps that apply to this recording, and the card that says the recording is
 * safely on the phone. The tracker fetch has no byte progress from the SDK, so it shows what the
 * import knows instead: which entry of how many, and how many samples are stored already.
 */
@Composable
internal fun FinishingHome(
    state: HomeUiState.Finishing,
    onOpenRecording: () -> Unit,
    onDismiss: () -> Unit,
) {
  val finishing = state.finishing
  val space = MaterialTheme.taiga.space
  val steps = finishing.steps.filter { it != FinishingState.Step.DONE }
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surface)
              .verticalScroll(rememberScrollState())
  ) {
    BearDen(
        size = DenSize.Compact,
        variant = LogoVariant.Sleeping,
        contentDescription = stringResource(R.string.home_logo_sleeping),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = space.m).padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
        Text(
            text =
                stringResource(
                    if (finishing.done) R.string.rec_finishing_done_title
                    else R.string.rec_finishing_title
                ),
            style = MaterialTheme.taiga.text.screenTitle,
        )
        Text(
            text =
                stringResource(
                    if (finishing.done) R.string.rec_finishing_done
                    else R.string.rec_finishing_subtitle
                ),
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TaigaCard {
        StepList(
            steps =
                steps.map { step ->
                  val stepState = stepState(finishing, step)
                  val fetching = step == FinishingState.Step.FETCHING
                  StepItem(
                      label = stringResource(label(step)),
                      state = stepState,
                      detail = if (fetching) fetchText(finishing, stepState) else null,
                      progress =
                          if (fetching && stepState == StepState.Active) {
                            StepProgress.Indeterminate
                          } else {
                            null
                          },
                  )
                }
        )
      }
      if (finishing.done) {
        PrimaryButton(
            text = stringResource(R.string.rec_finishing_open),
            onClick = onOpenRecording,
            size = ButtonSize.Large,
        )
        SecondaryButton(
            text = stringResource(R.string.rec_finishing_close),
            onClick = onDismiss,
        )
      }
    }
  }
}

private fun stepState(finishing: FinishingState, step: FinishingState.Step): StepState =
    when {
      finishing.done -> StepState.Done
      finishing.step == step -> StepState.Active
      step.ordinal < finishing.step.ordinal -> StepState.Done
      else -> StepState.Waiting
    }

private fun label(step: FinishingState.Step): Int =
    when (step) {
      FinishingState.Step.STOPPING_TRACKER -> R.string.rec_finishing_step_tracker
      FinishingState.Step.FETCHING -> R.string.rec_finishing_step_fetch
      FinishingState.Step.SAVING -> R.string.rec_finishing_step_save
      FinishingState.Step.DONE -> R.string.rec_finishing_step_done
    }

@Composable
private fun fetchText(finishing: FinishingState, stepState: StepState): String? {
  if (stepState != StepState.Active) return null
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
