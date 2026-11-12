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
import com.rbmr.taigasleep.ui.components.SleepStagesChart
import com.rbmr.taigasleep.ui.components.StageBreakdown
import com.rbmr.taigasleep.ui.components.TaigaCard

/** The stages of the recording, the time spent in each and the sleep numbers. */
@Composable
internal fun SleepStatsCard(
    state: RecordingDetailUiState,
    analysis: AnalysisResultDto,
    onExpand: () -> Unit,
) {
  TaigaCard {
    Text(
        text = stringResource(R.string.night_sleep_stats),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = stringResource(R.string.night_sleep_stages),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
      )
      ChartExpandButton(onClick = onExpand)
    }
    Spacer(Modifier.height(6.dp))
    SleepStagesChart(
        segments = state.segments,
        epochSeconds = state.epochSeconds,
        startUnixMs = state.start,
        capabilities = analysis.capabilities,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.night_stage_breakdown),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(10.dp))
    StageBreakdown(
        segments = state.segments,
        epochSeconds = state.epochSeconds,
        capabilities = analysis.capabilities,
    )
    val items = analysis.items.filter { it.kind == "number" && it.group == "sleep" }
    if (items.isNotEmpty()) {
      Spacer(Modifier.height(16.dp))
      MetricGrid(items = items)
    }
  }
}
