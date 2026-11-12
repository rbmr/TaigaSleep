package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.ResultScoreComponentDto
import com.rbmr.taigasleep.insights.SleepScoreCardState
import com.rbmr.taigasleep.insights.SleepScoreUnavailableReason
import com.rbmr.taigasleep.insights.formatItemValue
import com.rbmr.taigasleep.insights.metricSpec
import com.rbmr.taigasleep.ui.components.InfoPill
import com.rbmr.taigasleep.ui.components.LabeledValue
import com.rbmr.taigasleep.ui.components.Pressable
import com.rbmr.taigasleep.ui.components.ScoreRing
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt

/** The values repeated beside the ring; every one of them is shown in its own section below. */
private val SummaryItemIds = listOf("total_sleep_s", "hr_mean_bpm", "data_quality")

/**
 * The head of the recording: the sleep score as a ring, and beside it the figures the sections
 * below explain. The score itself appears nowhere else on the screen.
 */
@Composable
internal fun RecordingSummary(
    items: List<ResultItemDto>,
    score: Double?,
    onOpenScore: () -> Unit,
    onExplainScore: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  val scoreLabel = stringResource(R.string.night_sleep_score)
  TaigaCard {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(space.s),
      ) {
        Pressable(
            onClick = onOpenScore,
            shape = CircleShape,
            role = Role.Button,
            onClickLabel = scoreLabel,
        ) {
          ScoreRing(score = score, label = scoreLabel)
        }
        if (items.isNotEmpty()) {
          InfoPill(
              label = stringResource(R.string.night_sleep_score_experimental),
              onClick = onExplainScore,
          )
        }
      }
      Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(space.m),
      ) {
        for (id in SummaryItemIds) {
          val item = items.firstOrNull { it.id == id } ?: continue
          val value = summaryValue(item) ?: continue
          LabeledValue(label = item.label, value = value)
        }
      }
    }
  }
}

/** A summary figure in the unit the item carries; the data quality score reads as `86 / 100`. */
@Composable
private fun summaryValue(item: ResultItemDto): String? =
    if (item.kind == "score") {
      item.value?.let { stringResource(R.string.night_data_quality_score, it.roundToInt()) }
    } else {
      item.metricSpec()?.let { spec -> formatItemValue(spec.kind, spec.itemValue) }
    }

/** The reason the result has no sleep score, instead of the number the sheet would show. */
@Composable
private fun sleepScoreUnavailableText(reason: SleepScoreUnavailableReason): String =
    when (reason) {
      SleepScoreUnavailableReason.NOT_ENOUGH_GOOD_DATA ->
          stringResource(R.string.night_sleep_score_not_enough_data)
      SleepScoreUnavailableReason.OLDER_RESULT ->
          stringResource(R.string.night_sleep_score_unavailable)
    }

/** The sleep score and what it is made of, or the reason there is no score. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepScoreSheet(
    item: ResultItemDto?,
    state: SleepScoreCardState?,
    onDismiss: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = space.l)
                .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Text(
          text = stringResource(R.string.night_sleep_score_breakdown),
          style = MaterialTheme.taiga.text.section,
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
          Spacer(Modifier.width(space.m))
          Text(
              text = stringResource(R.string.night_sleep_score_value, score.roundToInt()),
              style = MaterialTheme.taiga.text.row,
          )
        } else {
          Text(
              text =
                  if (reason != null) sleepScoreUnavailableText(reason)
                  else stringResource(R.string.night_sleep_score_none),
              style = MaterialTheme.taiga.text.body,
          )
        }
      }
      if (item != null) {
        Text(
            text = stringResource(R.string.night_sleep_score_formula),
            style = MaterialTheme.taiga.text.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ScoreComponentRows(item.components)
      } else if (reason == null || reason == SleepScoreUnavailableReason.OLDER_RESULT) {
        Text(
            text = stringResource(R.string.night_sleep_score_unavailable),
            style = MaterialTheme.taiga.text.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** One weighted component of a score, with its share. */
@Composable
internal fun ScoreComponentRows(components: List<ResultScoreComponentDto>) {
  val space = MaterialTheme.taiga.space
  components.forEach { component ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(text = component.label, style = MaterialTheme.taiga.text.body)
        Text(
            text =
                stringResource(
                    R.string.night_quality_component_value,
                    component.value.roundToInt(),
                    component.weight.roundToInt(),
                ),
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.width(space.s))
      Text(
          text = stringResource(R.string.night_percent, component.value.roundToInt()),
          style = MaterialTheme.taiga.text.row,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
