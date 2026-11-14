package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.ui.components.BulletList
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.ShareBar
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaSheet
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt

/**
 * The data-quality score, what it is made of, the time it covers and anything the analysis warned
 * about.
 */
@Composable
internal fun DataQualityStatsSection(analysis: AnalysisResultDto) {
  val space = MaterialTheme.taiga.space
  val title = stringResource(R.string.night_data_quality_stats)
  val info =
      InfoContent(
          title,
          stringResource(R.string.night_data_quality_explainer) +
              "\n\n" +
              stringResource(R.string.night_quality_formula),
      )
  var showNotes by rememberSaveable { mutableStateOf(false) }
  if (showNotes) {
    TaigaSheet(onDismiss = { showNotes = false }) {
      Column(
          modifier = Modifier.padding(horizontal = space.m).padding(bottom = space.l),
          verticalArrangement = Arrangement.spacedBy(space.s),
      ) {
        Text(
            text = stringResource(R.string.night_notes),
            style = MaterialTheme.taiga.text.section,
        )
        BulletList(analysis.warnings)
      }
    }
  }
  Section(title = title, info = info) {
    Column(verticalArrangement = Arrangement.spacedBy(space.m)) {
      val score = analysis.items.firstOrNull { it.id == "data_quality" }
      score?.value?.let { value ->
        Text(
            text = stringResource(R.string.night_data_quality_score, value.roundToInt()),
            style = MaterialTheme.taiga.text.section,
        )
      }
      score?.components.orEmpty().forEach { component ->
        ShareBar(
            label = component.label,
            value = stringResource(R.string.night_percent, component.value.roundToInt()),
            fraction = (component.value / 100.0).toFloat(),
            caption = stringResource(R.string.night_quality_weight, component.weight.roundToInt()),
        )
      }
      val items = numberItems(analysis.items, "quality")
      if (items.isNotEmpty()) MetricGrid(items = items)
      if (analysis.warnings.isNotEmpty()) {
        TaigaCard(contentPadding = PaddingValues(0.dp)) {
          ListRow(
              title = stringResource(R.string.night_notes_row, analysis.warnings.size),
              onClick = { showNotes = true },
          )
        }
      }
    }
  }
}
