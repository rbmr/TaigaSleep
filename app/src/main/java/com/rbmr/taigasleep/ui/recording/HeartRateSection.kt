package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.ui.components.ChartExpandButton
import com.rbmr.taigasleep.ui.components.HeartRateChart
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.hrSeriesPoints
import kotlin.math.roundToInt

/** The heart-rate series and the numbers derived from it. */
@Composable
internal fun HeartRateStatsCard(
    analysis: AnalysisResultDto,
    onExpand: () -> Unit,
) {
  TaigaCard {
    val series = analysis.items.firstOrNull { it.kind == "series" && it.id == "hr" }?.series
    val points = hrSeriesPoints(series)
    val hasSamples = points.any { it != null }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = stringResource(R.string.night_heart_rate_stats),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
      )
      if (hasSamples) ChartExpandButton(onClick = onExpand)
    }
    Spacer(Modifier.height(6.dp))
    when {
      hasSamples && series != null -> HeartRateChart(series = series)
      series != null ->
          Text(
              text = stringResource(R.string.night_heart_rate_no_samples),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
      else ->
          Text(
              text = stringResource(R.string.night_heart_rate_no_series),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
    }
    val items = analysis.items.filter { it.kind == "number" && it.group == "heart" }
    if (items.isNotEmpty()) {
      Spacer(Modifier.height(12.dp))
      MetricGrid(items = items)
    }
    val rrCount = analysis.items.firstOrNull { it.id == "hrv_rr_count" }?.value?.roundToInt()
    if (rrCount != null && rrCount > 0) {
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.night_hrv_supporting, rrCount),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
