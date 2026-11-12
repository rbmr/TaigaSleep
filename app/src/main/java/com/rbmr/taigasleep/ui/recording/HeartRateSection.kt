package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.ui.components.ChartExpandButton
import com.rbmr.taigasleep.ui.components.HeartRateChart
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.hrSeriesPoints
import com.rbmr.taigasleep.ui.theme.taiga

/** The heart-rate series and every number derived from it, the HRV support figures included. */
@Composable
internal fun HeartRateStatsSection(
    analysis: AnalysisResultDto,
    onExpand: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  val title = stringResource(R.string.night_heart_rate_stats)
  val series = analysis.items.firstOrNull { it.kind == "series" && it.id == "hr" }?.series
  val hasSamples = hrSeriesPoints(series).any { it != null }
  Section(
      title = title,
      info = InfoContent(title, stringResource(R.string.night_heart_rate_info)),
      trailing = {
        if (hasSamples) ChartExpandButton(onClick = onExpand)
      },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(space.m)) {
      when {
        hasSamples && series != null -> HeartRateChart(series = series)
        series != null ->
            Text(
                text = stringResource(R.string.night_heart_rate_no_samples),
                style = MaterialTheme.taiga.text.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else ->
            Text(
                text = stringResource(R.string.night_heart_rate_no_series),
                style = MaterialTheme.taiga.text.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
      }
      val items = numberItems(analysis.items, "heart", "heart_support")
      if (items.isNotEmpty()) MetricGrid(items = items)
    }
  }
}
