package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.ui.components.TaigaCard

/** The movement numbers of the recording. */
@Composable
internal fun MovementStatsCard(items: List<ResultItemDto>) {
  TaigaCard {
    Text(
        text = stringResource(R.string.night_movement),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(10.dp))
    MetricGrid(items = items)
  }
}
