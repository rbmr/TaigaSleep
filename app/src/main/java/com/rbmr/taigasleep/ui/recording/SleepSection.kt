package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.StageSegmentDto
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
    segments: List<StageSegmentDto>,
    epochSeconds: Int,
    startUnixMs: Long,
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
          segments = segments,
          epochSeconds = epochSeconds,
          startUnixMs = startUnixMs,
          capabilities = analysis.capabilities,
      )
      StageBar(segments = segments)
      StageBreakdown(
          segments = segments,
          epochSeconds = epochSeconds,
          capabilities = analysis.capabilities,
      )
      val items = numberItems(analysis.items, "sleep")
      if (items.isNotEmpty()) MetricGrid(items = items)
    }
  }
}
