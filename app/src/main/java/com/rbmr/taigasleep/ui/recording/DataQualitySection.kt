package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.insights.MetricSpec
import com.rbmr.taigasleep.ui.components.ScoreRing
import com.rbmr.taigasleep.ui.components.TaigaCard
import kotlin.math.roundToInt

/** The data-quality score, what it is made of and how much of the night it covers. */
@Composable
internal fun DataQualityStatsCard(
    analysis: AnalysisResultDto,
    onOpen: () -> Unit,
    onMetric: (MetricSpec) -> Unit,
) {
  TaigaCard {
    val score = analysis.items.firstOrNull { it.id == "data_quality" }?.value
    Row(
        modifier = Modifier.clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            text = stringResource(R.string.night_data_quality_stats),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.night_data_quality_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                if (score == null) stringResource(R.string.night_data_quality_none)
                else stringResource(R.string.night_data_quality_tap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      ScoreRing(
          score = score,
          label = stringResource(R.string.night_data_quality),
          size = 88,
      )
    }
    val items = analysis.items.filter { it.kind == "number" && it.group == "quality" }
    if (items.isNotEmpty()) {
      Spacer(Modifier.height(12.dp))
      MetricGrid(items = items, onMetric = onMetric)
    }
  }
}

/** The data-quality score and its components, opened from the card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataQualitySheet(item: ResultItemDto?, onDismiss: () -> Unit) {
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
          text = stringResource(R.string.night_data_quality_breakdown),
          style = MaterialTheme.typography.headlineSmall,
      )
      val score = item?.value
      Row(verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(
            score = score,
            label = stringResource(R.string.night_data_quality),
            size = 82,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text =
                if (score == null) stringResource(R.string.night_data_quality_none)
                else stringResource(R.string.night_data_quality_score, score.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
        )
      }
      Text(
          text = stringResource(R.string.night_data_quality_explainer),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          text = stringResource(R.string.night_quality_formula),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      ScoreComponentRows(item?.components.orEmpty())
    }
  }
}
