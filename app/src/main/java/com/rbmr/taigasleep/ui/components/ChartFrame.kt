package com.rbmr.taigasleep.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The room a chart keeps clear of its plot for the axis labels, in dp. It follows the system font
 * scale, so a large font gets the labels the space they need instead of being clipped.
 */
internal data class ChartLabelSpace(val left: Dp, val top: Dp, val bottom: Dp)

/** The label space of a trend chart at [fontScale]. */
internal fun chartLabelSpace(large: Boolean, fontScale: Float): ChartLabelSpace =
    ChartLabelSpace(
        left = (if (large) 64.dp else 48.dp) * fontScale,
        top = (if (large) 18.dp else 16.dp) * fontScale,
        bottom = (if (large) 28.dp else 24.dp) * fontScale,
    )

/**
 * The label space of a recording chart: its label column is the one the chart asks for, grown with
 * the font so long stage names and units keep their room.
 */
internal fun recordingChartLabelSpace(
    large: Boolean,
    left: Dp,
    fontScale: Float,
): ChartLabelSpace =
    ChartLabelSpace(
        left = left * fontScale,
        top = if (large) 2.dp else 8.dp,
        bottom = if (large) 26.dp else 22.dp,
    )

/** The dash of a grid or a second series, in dp so it reads the same at every density. */
internal fun Density.dashEffect(): PathEffect =
    PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))

/** The reference lines are thinner and dotted, so they stay clearly apart from the data. */
internal fun Density.dotEffect(): PathEffect =
    PathEffect.dashPathEffect(
        floatArrayOf(1.5.dp.toPx(), 3.dp.toPx()),
        phase = 0f,
    )

/**
 * The room a chart keeps clear of its plot, in pixels, and the label width it hands to a tooltip.
 * Both layers of the chart measure the plot from here, so they always agree.
 */
private class ChartGeometry(
    val leftPx: Float,
    val topPx: Float,
    val bottomPx: Float,
)

private fun chartGeometry(space: ChartLabelSpace, density: Density): ChartGeometry =
    ChartGeometry(
        leftPx = with(density) { space.left.toPx() },
        topPx = with(density) { space.top.toPx() },
        bottomPx = with(density) { space.bottom.toPx() },
    )

/** The plot of a chart: its left and top edge, its size, and the marks placed inside it. */
internal class ChartPlot(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
  val right: Float
    get() = left + width

  fun x(fraction: Float): Float = left + fraction * width

  fun y(fraction: Float): Float = top + fraction * height

  fun column(index: Int, count: Int): Float =
      if (count <= 0) left else x((index + 0.5f) / count.toFloat())
}

/** The plot of a chart of [width] by [height], or null when there is no room left for one. */
private fun chartPlot(width: Float, height: Float, geometry: ChartGeometry): ChartPlot? {
  if (width <= geometry.leftPx || height <= geometry.topPx + geometry.bottomPx) return null
  return ChartPlot(
      left = geometry.leftPx,
      top = geometry.topPx,
      width = width - geometry.leftPx,
      height = height - geometry.topPx - geometry.bottomPx,
  )
}

/** The colours of a chart: its grid, its axes, its reference lines and its marks. */
internal class ChartColors(val grid: Color, val axis: Color, val reference: Color, val mark: Color)

/** The colours, the label paint and the line effects of a chart, read once from the theme. */
internal class ChartStyle(
    val paint: Paint,
    val colors: ChartColors,
    val dash: PathEffect,
    val dot: PathEffect,
)

@Composable
private fun chartStyle(density: Density, large: Boolean): ChartStyle {
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val labelSp = if (large) 13.sp else 12.sp
  val paint =
      remember(labelColor, labelSp, density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = labelColor.toArgb()
          textSize = with(density) { labelSp.toPx() }
        }
      }
  return ChartStyle(
      paint = paint,
      colors =
          ChartColors(
              grid = labelColor.copy(alpha = 0.30f),
              axis = labelColor.copy(alpha = 0.65f),
              reference = labelColor.copy(alpha = 0.75f),
              mark = MaterialTheme.colorScheme.primary,
          ),
      dash = remember(density.density) { with(density) { dashEffect() } },
      dot = remember(density.density) { with(density) { dotEffect() } },
  )
}

/**
 * The frame and the marks of a chart. The labels stay clear of the plot. Touching or dragging
 * selects the mark under the finger: a crosshair marks its column, the caller's mark draws the
 * selected point, and [tooltip] reports it.
 *
 * The chart is drawn in two layers. Everything the data decides is one canvas, kept as it is while
 * the finger moves; the crosshair and the selected mark are a canvas of their own above it. A drag
 * therefore draws the crosshair and gives the data itself nothing to do.
 *
 * The caller draws the grid, the x labels and the data in [drawPlot], which is what the two chart
 * families do not share. This frame owns the geometry, the plot, the two axes and the y values.
 */
@Suppress("LongParameterList")
@Composable
internal fun ChartFrame(
    space: ChartLabelSpace,
    large: Boolean,
    description: String,
    gestureKey: Any?,
    labels: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    topLabel: ((String) -> String)? = null,
    accept: (x: Float, leftPx: Float) -> Boolean = { _, _ -> true },
    indexAt: (x: Float, leftPx: Float, plotWidth: Float) -> Int,
    fractionAt: (Int) -> Float,
    drawPlot: DrawScope.(ChartPlot, ChartStyle) -> Unit,
    drawMark: DrawScope.(ChartPlot, Int) -> Unit = { _, _ -> },
    tooltip: @Composable BoxScope.(Int) -> Unit,
) {
  val density = LocalDensity.current
  val geometry = remember(space, density.density) { chartGeometry(space, density) }
  val style = chartStyle(density, large)
  val selection = remember { mutableStateOf<Int?>(null) }

  Box(modifier) {
    Canvas(
        Modifier.fillMaxSize()
            .graphicsLayer()
            .semantics { contentDescription = description }
            .pointerInput(gestureKey, geometry.leftPx) {
              fun indexOf(x: Float): Int {
                val width = (size.width.toFloat() - geometry.leftPx).coerceAtLeast(1f)
                return indexAt(x, geometry.leftPx, width)
              }

              scrubChart(
                  accept = { x -> accept(x, geometry.leftPx) },
                  onSelect = { x -> selection.value = indexOf(x) },
                  onClear = { selection.value = null },
              )
            }
    ) {
      // The data layer reads no selection: the finger moves without drawing it again.
      val plot = chartPlot(size.width, size.height, geometry) ?: return@Canvas
      drawPlot(plot, style)
      drawChartAxes(plot, style)
      drawChartValues(plot, labels, topLabel, style)
    }
    Canvas(Modifier.fillMaxSize()) {
      val index = selection.value ?: return@Canvas
      val plot = chartPlot(size.width, size.height, geometry) ?: return@Canvas
      val x = plot.x(fractionAt(index))
      drawLine(
          style.colors.mark,
          Offset(x, plot.top),
          Offset(x, plot.top + plot.height),
          1.5.dp.toPx(),
      )
      drawMark(plot, index)
    }
    selection.value?.let { index -> tooltip(index) }
  }
}

/** The two axes of the plot. */
private fun DrawScope.drawChartAxes(plot: ChartPlot, style: ChartStyle) {
  drawLine(
      style.colors.axis,
      Offset(plot.left, plot.top),
      Offset(plot.left, plot.top + plot.height),
      1.dp.toPx(),
  )
  drawLine(
      style.colors.axis,
      Offset(plot.left, plot.top + plot.height),
      Offset(plot.right, plot.top + plot.height),
      1.dp.toPx(),
  )
}

/** The values of the y axis, right-aligned beside the plot. */
private fun DrawScope.drawChartValues(
    plot: ChartPlot,
    labels: List<Pair<Float, String>>,
    topLabel: ((String) -> String)?,
    style: ChartStyle,
) {
  val paint = style.paint
  paint.textAlign = Paint.Align.RIGHT
  for ((index, tick) in labels.withIndex()) {
    val label = if (index == 0 && topLabel != null) topLabel(tick.second) else tick.second
    drawContext.canvas.nativeCanvas.drawText(
        label,
        plot.left - 6.dp.toPx(),
        plot.y(tick.first) + paint.textSize / 3f,
        paint,
    )
  }
}
