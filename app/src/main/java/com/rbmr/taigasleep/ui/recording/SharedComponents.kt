package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.insights.formatItemValue
import com.rbmr.taigasleep.insights.metricSpec as itemSpec
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.StatTile

/** The typed numbers of one group, two to a row; each tile opens its own explanation. */
@Composable
internal fun MetricGrid(items: List<ResultItemDto>) {
  val specs = items.mapNotNull { it.itemSpec() }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    specs.chunked(2).forEach { pair ->
      Row(
          modifier = Modifier.height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        pair.forEach { spec ->
          val value = formatItemValue(spec.kind, spec.itemValue)
          StatTile(
              label = spec.label,
              value = value ?: stringResource(R.string.night_value_none),
              info = InfoContent(spec.label, spec.explanation, spec.source),
              modifier = Modifier.weight(1f).fillMaxHeight(),
          )
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}
