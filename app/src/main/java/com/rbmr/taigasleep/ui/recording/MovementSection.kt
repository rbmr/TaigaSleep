package com.rbmr.taigasleep.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.Section

/** The movement numbers of the recording. */
@Composable
internal fun MovementStatsSection(items: List<ResultItemDto>) {
  val title = stringResource(R.string.night_movement)
  Section(
      title = title,
      info = InfoContent(title, stringResource(R.string.night_movement_info)),
  ) {
    MetricGrid(items = items)
  }
}
