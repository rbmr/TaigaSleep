package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.insights.formatItemValue
import com.rbmr.taigasleep.insights.metricSpec as itemSpec
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.StatTile
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The number items of the given groups, in the order the result carries them. A section draws its
 * own groups: the heart section takes `heart` and `heart_support` together, because the supporting
 * figures explain the heart-rate numbers beside them.
 */
internal fun numberItems(items: List<ResultItemDto>, vararg groups: String): List<ResultItemDto> =
    items.filter { it.kind == "number" && it.group in groups }

/**
 * The typed numbers of one group, two to a row. Every tile takes exactly half of the row and the
 * tiles of one row share its height, so the grid reads as one table however long the values are.
 * The label, the unit and the explanation all come from the item, and every tile opens its own
 * explanation.
 */
@Composable
internal fun MetricGrid(items: List<ResultItemDto>) {
  val space = MaterialTheme.taiga.space
  val specs = items.mapNotNull { it.itemSpec() }
  Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
    specs.chunked(2).forEach { pair ->
      Row(
          modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(space.s),
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
