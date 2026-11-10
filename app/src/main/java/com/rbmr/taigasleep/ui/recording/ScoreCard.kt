package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.ResultScoreComponentDto
import com.rbmr.taigasleep.insights.SleepScoreCardState
import com.rbmr.taigasleep.insights.SleepScoreUnavailableReason
import com.rbmr.taigasleep.ui.components.ScoreRing
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt

/** The head of the recording: whether it is processed, and its sleep score as a ring. */
@Composable
internal fun RecordingHeaderCard(
    state: SleepScoreCardState?,
    processed: Boolean,
    onScoreClick: () -> Unit,
    onExplainExperimental: () -> Unit,
) {
  val score = (state as? SleepScoreCardState.Scored)?.score
  TaigaCard(
      modifier = if (processed) Modifier.clickable(onClick = onScoreClick) else Modifier
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              text =
                  if (processed) stringResource(R.string.night_processed)
                  else stringResource(R.string.night_not_processed),
              style = MaterialTheme.typography.labelLarge,
              color =
                  if (processed) MaterialTheme.taiga.ok
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (processed && score != null) {
            Spacer(Modifier.width(8.dp))
            ExperimentalLabel(onClick = onExplainExperimental)
          }
        }
        Text(
            text =
                when {
                  !processed -> stringResource(R.string.night_header_pending)
                  state is SleepScoreCardState.Unavailable ->
                      scoreCardText(state.reason)
                  else -> stringResource(R.string.night_sleep_score_tap)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (processed && state !is SleepScoreCardState.Unavailable) {
        ScoreRing(
            score = score,
            label = stringResource(R.string.night_sleep_score),
        )
      }
    }
  }
}

/** The reason the result has no sleep score, instead of the number the card would show. */
@Composable
private fun scoreCardText(reason: SleepScoreUnavailableReason): String =
    when (reason) {
      SleepScoreUnavailableReason.NOT_ENOUGH_GOOD_DATA ->
          stringResource(R.string.night_sleep_score_not_enough_data)
      // The card stays short; the sheet says which result this is and what to do about it.
      SleepScoreUnavailableReason.OLDER_RESULT -> stringResource(R.string.night_sleep_score_none)
    }

/** The full reason the result has no sleep score, for the sheet. */
@Composable
private fun sleepScoreUnavailableText(reason: SleepScoreUnavailableReason): String =
    when (reason) {
      SleepScoreUnavailableReason.NOT_ENOUGH_GOOD_DATA ->
          stringResource(R.string.night_sleep_score_not_enough_data)
      SleepScoreUnavailableReason.OLDER_RESULT ->
          stringResource(R.string.night_sleep_score_unavailable)
    }

/**
 * The label that qualifies a sleep score, and the button that explains it. The pill stays small,
 * but the touch target around it is the 48 dp a button needs.
 */
@Composable
private fun ExperimentalLabel(onClick: () -> Unit) {
  val description = stringResource(R.string.night_sleep_score_experimental_description)
  Box(
      modifier =
          Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
              .clickable(role = Role.Button, onClick = onClick)
              .semantics(mergeDescendants = true) { contentDescription = description },
      contentAlignment = Alignment.Center,
  ) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.taiga.warnContainer,
        contentColor = MaterialTheme.taiga.warnInk,
    ) {
      Text(
          text = stringResource(R.string.night_sleep_score_experimental),
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      )
    }
  }
}

/** The sleep score and what it is made of, or the reason there is no score. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepScoreSheet(
    item: ResultItemDto?,
    state: SleepScoreCardState?,
    onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = stringResource(R.string.night_sleep_score_breakdown),
          style = MaterialTheme.typography.headlineSmall,
      )
      val score = (state as? SleepScoreCardState.Scored)?.score
      val reason = (state as? SleepScoreCardState.Unavailable)?.reason
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (score != null) {
          ScoreRing(
              score = score,
              label = stringResource(R.string.night_sleep_score),
              size = 82,
          )
          Spacer(Modifier.width(16.dp))
          Text(
              text = stringResource(R.string.night_sleep_score_value, score.roundToInt()),
              style = MaterialTheme.typography.titleMedium,
          )
        } else {
          Text(
              text =
                  if (reason != null) sleepScoreUnavailableText(reason)
                  else stringResource(R.string.night_sleep_score_none),
              style = MaterialTheme.typography.titleMedium,
          )
        }
      }
      if (item != null) {
        Text(
            text = stringResource(R.string.night_sleep_score_formula),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ScoreComponentRows(item.components)
      } else if (reason == null || reason == SleepScoreUnavailableReason.OLDER_RESULT) {
        Text(
            text = stringResource(R.string.night_sleep_score_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** One weighted component of a score, with its share. */
@Composable
internal fun ScoreComponentRows(components: List<ResultScoreComponentDto>) {
  components.forEach { component ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text = component.label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                stringResource(
                    R.string.night_quality_component_value,
                    component.value.roundToInt(),
                    component.weight.roundToInt(),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          text = stringResource(R.string.night_percent, component.value.roundToInt()),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
