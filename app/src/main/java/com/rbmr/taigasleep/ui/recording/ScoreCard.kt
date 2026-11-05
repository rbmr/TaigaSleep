package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.ResultScoreComponentDto
import com.rbmr.taigasleep.ui.components.ScoreRing
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt

/** The head of the night: whether it is processed, and its sleep score as a ring. */
@Composable
internal fun RecordingHeaderCard(
    sleepScore: Double?,
    processed: Boolean,
    onScoreClick: () -> Unit,
) {
  TaigaCard(
      modifier = if (processed) Modifier.clickable(onClick = onScoreClick) else Modifier
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text =
                if (processed) stringResource(R.string.night_processed)
                else stringResource(R.string.night_not_processed),
            style = MaterialTheme.typography.labelLarge,
            color =
                if (processed) MaterialTheme.taiga.ok
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                if (processed) stringResource(R.string.night_sleep_score_tap)
                else stringResource(R.string.night_header_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (processed) {
        ScoreRing(
            score = sleepScore,
            label = stringResource(R.string.night_sleep_score),
        )
      }
    }
  }
}

/** The sleep score and what it is made of. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepScoreSheet(item: ResultItemDto?, onDismiss: () -> Unit) {
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
      val score = item?.value
      Row(verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(
            score = score,
            label = stringResource(R.string.night_sleep_score),
            size = 82,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text =
                if (score == null) stringResource(R.string.night_sleep_score_none)
                else stringResource(R.string.night_sleep_score_value, score.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
        )
      }
      if (item == null) {
        Text(
            text = stringResource(R.string.night_sleep_score_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
            text = stringResource(R.string.night_sleep_score_formula),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ScoreComponentRows(item.components)
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
          text = "${component.value.roundToInt()}%",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
