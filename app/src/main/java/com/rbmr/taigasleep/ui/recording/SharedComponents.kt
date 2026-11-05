package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.insights.MetricSpec
import com.rbmr.taigasleep.insights.formatItemValue
import com.rbmr.taigasleep.insights.metricSpec as itemSpec

/** The typed numbers of one group, two to a row. */
@Composable
internal fun MetricGrid(items: List<ResultItemDto>, onMetric: (MetricSpec) -> Unit) {
  val specs = items.mapNotNull { it.itemSpec() }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    specs.chunked(2).forEach { pair ->
      Row(
          modifier = Modifier.height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        pair.forEach { spec ->
          val value = formatItemValue(spec.kind, spec.itemValue)
          MetricTile(
              spec = spec,
              value = value ?: "—",
              onClick = { onMetric(spec) },
              modifier = Modifier.weight(1f).fillMaxHeight(),
          )
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun MetricTile(
    spec: MetricSpec,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier.heightIn(min = 92.dp).clickable(onClick = onClick),
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
      Text(
          text = spec.label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(4.dp))
      Text(
          text = value,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

/** What one number means and where it came from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MetricSheet(spec: MetricSpec, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(spec.label, style = MaterialTheme.typography.headlineSmall)
      Text(
          text = spec.explanation,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      spec.source?.let { source ->
        Text(
            text = "Source: $source",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
      }
    }
  }
}
