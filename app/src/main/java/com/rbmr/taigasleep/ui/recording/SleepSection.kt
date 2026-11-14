package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.ui.components.ChartExpandButton
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SleepStagesChart
import com.rbmr.taigasleep.ui.components.StageBar
import com.rbmr.taigasleep.ui.components.StageBreakdown
import com.rbmr.taigasleep.ui.theme.taiga

/** The stages of the recording in time, how long each lasted, and the sleep numbers. */
@Composable
internal fun SleepStatsSection(
    state: RecordingDetailUiState,
    analysis: AnalysisResultDto,
    onExpand: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  val title = stringResource(R.string.night_sleep_stages)
  Section(
      title = title,
      info = InfoContent(title, stringResource(R.string.night_sleep_stages_info)),
      trailing = { ChartExpandButton(onClick = onExpand) },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(space.m)) {
      SleepStagesChart(
          segments = state.segments,
          epochSeconds = state.epochSeconds,
          startUnixMs = state.start,
          capabilities = analysis.capabilities,
      )
      StageBar(segments = state.segments)
      StageBreakdown(
          segments = state.segments,
          epochSeconds = state.epochSeconds,
          capabilities = analysis.capabilities,
      )
      val items = numberItems(analysis.items, "sleep")
      if (items.isNotEmpty()) MetricGrid(items = items)
    }
  }
}
