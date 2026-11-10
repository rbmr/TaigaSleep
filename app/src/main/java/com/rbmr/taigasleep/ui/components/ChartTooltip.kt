package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one tooltip of a chart: the time of the mark it belongs to above that mark's value. It sits in
 * the top corner the mark and its finger are not under, clear of the axis labels. With [onClick] it
 * is also a way into what the mark shows; without one it only reports.
 *
 * [fraction] is where the mark sits across the plot, 0 at the left edge and 1 at the right, and
 * [labelWidth] the width of the labels beside the plot, which the tooltip must not cover.
 */
@Composable
internal fun BoxScope.ChartTooltip(
    time: String,
    value: String,
    fraction: Float,
    labelWidth: Dp,
    onClick: (() -> Unit)? = null,
) {
  Surface(
      modifier =
          Modifier.align(if (fraction < 0.5f) Alignment.TopEnd else Alignment.TopStart)
              .padding(top = 4.dp, start = labelWidth + 8.dp, end = 8.dp)
              .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      tonalElevation = 3.dp,
      shadowElevation = 3.dp,
  ) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
      Text(
          text = time,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(text = value, style = MaterialTheme.typography.labelLarge)
    }
  }
}

/** Where the mark at [index] of [count] sits across the plot. */
internal fun markFraction(index: Int, count: Int): Float =
    if (count <= 1) 0f else index.toFloat() / (count - 1).toFloat()
