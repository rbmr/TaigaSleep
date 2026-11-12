package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.ShareBar
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
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
        StatusBanner(
            kind = StatusKind.Warn,
            title = stringResource(R.string.night_warnings),
            details = analysis.warnings,
        )
      }
    }
  }
}
