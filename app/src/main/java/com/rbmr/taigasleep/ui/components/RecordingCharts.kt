package com.rbmr.taigasleep.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.HrSeriesDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.insights.Formats
import com.rbmr.taigasleep.insights.applicableStages
import com.rbmr.taigasleep.insights.stageLabelRes
import com.rbmr.taigasleep.insights.stageSeconds
import com.rbmr.taigasleep.ui.theme.LocalFormats
import com.rbmr.taigasleep.ui.theme.taiga
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.roundToInt

enum class QualityBand {
  EXCELLENT,
  GOOD,
  FAIR,
  POOR,
  NONE,
}

fun qualityBand(score: Double?): QualityBand =
    when {
      score == null -> QualityBand.NONE
      score >= 80 -> QualityBand.EXCELLENT
      score >= 60 -> QualityBand.GOOD
      score >= 40 -> QualityBand.FAIR
      else -> QualityBand.POOR
    }

@Composable
fun qualityColor(band: QualityBand): Color =
    when (band) {
      QualityBand.EXCELLENT,
      QualityBand.GOOD -> MaterialTheme.taiga.ok
      QualityBand.FAIR -> MaterialTheme.taiga.warn
      QualityBand.POOR -> MaterialTheme.taiga.bad
      QualityBand.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
fun stageColor(stage: String): Color =
    when (stage) {
      "wake" -> MaterialTheme.taiga.stageWake
      "rem" -> MaterialTheme.taiga.stageRem
      "deep" -> MaterialTheme.taiga.stageDeep
      "nrem" -> MaterialTheme.taiga.stageLight
      "sleep" -> MaterialTheme.taiga.stageLight
      "unknown" -> MaterialTheme.colorScheme.outlineVariant
      else -> MaterialTheme.taiga.stageLight
    }

@Composable
fun ScoreRing(
    score: Double?,
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 104,
) {
  val band = qualityBand(score)
  val track = MaterialTheme.colorScheme.outlineVariant
  val ink = qualityColor(band)
  Box(modifier = modifier.size(size.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
      val stroke = 8.dp.toPx()
      val inset = stroke / 2
      drawArc(
          color = track,
          startAngle = -90f,
          sweepAngle = 360f,
          useCenter = false,
          topLeft = Offset(inset, inset),
          size = Size(this.size.width - stroke, this.size.height - stroke),
          style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
      if (score != null) {
        drawArc(
            color = ink,
            startAngle = -90f,
            sweepAngle = (score.coerceIn(0.0, 100.0).toFloat() / 100f) * 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
      }
    }
    Column(
        modifier = Modifier.width((size - 26).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          text = score?.roundToInt()?.toString() ?: "-",
          style = MaterialTheme.typography.headlineSmall,
          color = ink,
      )
      Text(
          text = label,
          style =
              MaterialTheme.typography.labelSmall.copy(
                  fontSize = if (size < 90) 9.sp else 11.sp,
                  lineHeight = if (size < 90) 11.sp else 13.sp,
              ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          maxLines = 2,
      )
    }
  }
}

/** One run of the stage bar: one stage and the share of the bar it takes. */
private data class StageRun(val stage: String, val start: Float, val end: Float)

/**
 * The stages of a night as one bar: the segments in order, each as wide as its share of the night.
 * Two neighbours of the same stage are one run, and the runs are remembered for the segment list,
 * so drawing a row again draws the same bar instead of one box per segment.
 */
@Composable
fun StageBar(
    segments: List<StageSegmentDto>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    description: String = stringResource(R.string.history_stage_bar_description),
) {
  val runs = remember(segments) { stageRuns(segments) }
  val colors = mutableMapOf<String, Color>()
  for (run in runs) {
    if (!colors.containsKey(run.stage)) colors[run.stage] = stageColor(run.stage)
  }
  Canvas(
      modifier
          .fillMaxWidth()
          .height(height)
          .clip(RoundedCornerShape(50))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .semantics { contentDescription = description }
  ) {
    for (run in runs) {
      drawRect(
          color = colors.getValue(run.stage),
          topLeft = Offset(size.width * run.start, 0f),
          size = Size(size.width * (run.end - run.start), size.height),
      )
    }
  }
}

/**
 * The runs of one stage bar: neighbours of the same stage merged, empty segments dropped, and each
 * run placed by its share of the counted segments. A run ends where the next one starts, so the
 * bar has no seams and its last run ends exactly at the right edge.
 */
private fun stageRuns(segments: List<StageSegmentDto>): List<StageRun> {
  val merged = mutableListOf<Pair<String, Long>>()
  for (segment in segments) {
    if (segment.count <= 0) continue
    val last = merged.lastOrNull()
    if (last != null && last.first == segment.stage) {
      merged[merged.lastIndex] = last.first to last.second + segment.count.toLong()
    } else {
      merged += segment.stage to segment.count.toLong()
    }
  }
  val total = merged.sumOf { it.second }
  if (total <= 0L) return emptyList()
  var counted = 0L
  return merged.map { (stage, count) ->
    val start = counted.toFloat() / total
    counted += count
    StageRun(stage, start, counted.toFloat() / total)
  }
}

/** The smallest y span a series axis gets, in the unit of the series, so flat data has room. */
internal const val SERIES_MIN_SPAN = 10.0

/**
 * The y-axis of a series chart: the data's range inside a span of at least [SERIES_MIN_SPAN],
 * centred on the data. A nearly flat series therefore spreads over a readable axis instead of
 * collapsing onto one value.
 */
internal fun seriesAxis(low: Double, high: Double): ClosedFloatingPointRange<Double> {
  val centre = (low + high) / 2
  val span = max(high - low, SERIES_MIN_SPAN)
  return (centre - span / 2)..(centre + span / 2)
}

/**
 * The five tick values of [axis] from its top to its bottom, rounded to whole numbers. The axis is
 * at least [SERIES_MIN_SPAN] wide, so no two labels of one axis can round to the same value.
 */
internal fun seriesAxisTicks(axis: ClosedFloatingPointRange<Double>): List<Int> {
  val span = axis.endInclusive - axis.start
  return (0..4).map { index -> (axis.endInclusive - span * index / 4.0).roundToInt() }
}

private data class HourTick(val timeMs: Long, val fraction: Float)

private data class PlotBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
  fun x(fraction: Float): Float = left + fraction * width

  fun y(fraction: Float): Float = top + fraction * height
}

private fun hourTicks(startUnixMs: Long, totalSeconds: Long): List<HourTick> {
  if (totalSeconds <= 0) return emptyList()
  val ticks = mutableListOf<HourTick>()
  if (startUnixMs <= 0) {
    var hour = 3600L
    while (hour < totalSeconds) {
      ticks += HourTick(hour, hour.toFloat() / totalSeconds)
      hour += 3600L
    }
    return ticks
  }
  val end = startUnixMs + totalSeconds * 1000L
  var tick =
      if (startUnixMs % 3_600_000L == 0L) startUnixMs
      else ((startUnixMs / 3_600_000L) + 1L) * 3_600_000L
  while (tick < end) {
    ticks +=
        HourTick(
            tick,
            ((tick - startUnixMs).toDouble() / (totalSeconds * 1000.0)).toFloat(),
        )
    tick += 3_600_000L
  }
  return ticks
}

private fun epochTimeMs(startUnixMs: Long, totalSeconds: Long, pointCount: Int, index: Int): Long {
  if (startUnixMs <= 0 || pointCount <= 0) return 0
  val epochMs = totalSeconds * 1000.0 / pointCount
  return startUnixMs + (index * epochMs).toLong()
}

/**
 * The title line of a chart card with an optional trailing action. The card gives it no top
 * padding: the title's own inset and the 48 dp action decide the line's height.
 */
@Composable
fun ChartHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
  val space = MaterialTheme.taiga.space
  Row(
      modifier = modifier.fillMaxWidth().padding(start = space.m, end = space.m),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = title,
        style = MaterialTheme.taiga.text.row,
        modifier = Modifier.weight(1f).padding(vertical = space.s),
    )
    trailing()
  }
}

@Composable
fun ChartExpandButton(onClick: () -> Unit) {
  // The glyph sits in a 48 dp target; pull the target out by its inset so the glyph lines up
  // with the right edge of the content.
  Pressable(
      onClick = onClick,
      shape = CircleShape,
      role = Role.Button,
      modifier = Modifier.offset(x = 12.dp),
  ) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
      Icon(
          imageVector = Icons.Outlined.Fullscreen,
          contentDescription = stringResource(R.string.night_chart_fullscreen),
      )
    }
  }
}

private fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }

@Composable
fun ChartFullscreenDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  DisposableEffect(activity) {
    val target = activity ?: return@DisposableEffect onDispose {}
    // Whatever the activity asked for before, including unspecified, is what it asks for again.
    val previous = target.requestedOrientation
    target.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    onDispose { target.requestedOrientation = previous }
  }
  Dialog(
      onDismissRequest = onDismiss,
      properties =
          DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      HideDialogSystemBars()
      Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              text = title,
              style = MaterialTheme.typography.titleLarge,
              modifier = Modifier.weight(1f),
          )
          Pressable(onClick = onDismiss, shape = CircleShape, role = Role.Button) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
              Icon(
                  imageVector = Icons.Outlined.Close,
                  contentDescription = stringResource(R.string.night_chart_close_fullscreen),
              )
            }
          }
        }
        content(Modifier.weight(1f).fillMaxWidth())
      }
    }
  }
}

@Composable
private fun HideDialogSystemBars() {
  val view = LocalView.current
  val window = remember(view) { (view.parent as? DialogWindowProvider)?.window }
  DisposableEffect(window) {
    val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
    if (window != null) {
      WindowCompat.setDecorFitsSystemWindows(window, false)
      controller?.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      controller?.hide(WindowInsetsCompat.Type.systemBars())
    }
    onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
  }
}

/** The time a time chart covers: its start, its length in seconds and how many points it has. */
private class TimeChartSpan(val startUnixMs: Long, val totalSeconds: Long, val pointCount: Int)

/** The value axis of a time chart: its labels from the top, its lane lines and its label width. */
private class TimeChartAxis(
    val labels: List<Pair<Float, String>>,
    val lines: List<Float>,
    val labelWidth: Dp,
)

/** What a time chart draws that does not change while the finger moves. */
private class TimeChartSpec(
    val span: TimeChartSpan,
    val axis: TimeChartAxis,
    val description: String,
    val valueAt: (Int) -> String,
    val timeLabel: (Long) -> String,
    val large: Boolean,
)

/** The room a time chart keeps clear of its plot, in pixels. */
private class TimeChartSpace(val leftPx: Float, val topPx: Float, val bottomPx: Float)

/** The colours and the label paint of a time chart, read once from the theme. */
private class TimeChartStyle(
    val paint: Paint,
    val gridColor: Color,
    val axisColor: Color,
    val selectedColor: Color,
)

private fun timeChartSpace(spec: TimeChartSpec, density: Density) =
    TimeChartSpace(
        leftPx = with(density) { spec.axis.labelWidth.toPx() },
        topPx = with(density) { (if (spec.large) 2.dp else 8.dp).toPx() },
        bottomPx = with(density) { (if (spec.large) 26.dp else 22.dp).toPx() },
    )

@Composable
private fun timeChartStyle(density: Density, large: Boolean): TimeChartStyle {
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val labelSp = if (large) 13.sp else 12.sp
  val paint =
      remember(labelColor, labelSp, density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = labelColor.toArgb()
          textSize = with(density) { labelSp.toPx() }
        }
      }
  return TimeChartStyle(
      paint = paint,
      gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f),
      axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
      selectedColor = MaterialTheme.colorScheme.primary,
  )
}

private fun timePlot(width: Float, height: Float, space: TimeChartSpace): PlotBounds? {
  if (width <= space.leftPx || height <= space.topPx + space.bottomPx) return null
  return PlotBounds(
      left = space.leftPx,
      top = space.topPx,
      width = width - space.leftPx,
      height = height - space.topPx - space.bottomPx,
  )
}

/**
 * A chart of one night over its clock time: the hour grid, the axis labels and the plot the caller
 * draws, with a crosshair the finger moves.
 *
 * The chart is drawn in two layers. The data layer is one canvas that reads no selection, so a drag
 * never draws it again; the crosshair and the mark of the selected point are a canvas of their own
 * above it.
 */
@Composable
private fun TimeChart(
    spec: TimeChartSpec,
    modifier: Modifier = Modifier,
    drawPlot: DrawScope.(PlotBounds) -> Unit,
    drawMark: DrawScope.(PlotBounds, Int) -> Unit = { _, _ -> },
) {
  val density = LocalDensity.current
  val space =
      remember(density.density, density.fontScale, spec.large, spec.axis.labelWidth) {
        timeChartSpace(spec, density)
      }
  val style = timeChartStyle(density, spec.large)
  val ticks =
      remember(spec.span.startUnixMs, spec.span.totalSeconds) {
        hourTicks(spec.span.startUnixMs, spec.span.totalSeconds)
      }
  val selection = remember { mutableStateOf<Int?>(null) }

  Box(modifier) {
    Canvas(
        Modifier.fillMaxSize()
            .graphicsLayer()
            .semantics { contentDescription = spec.description }
            .pointerInput(spec.span.pointCount, space.leftPx) {
              fun indexAt(x: Float): Int {
                val width = (size.width.toFloat() - space.leftPx).coerceAtLeast(1f)
                val fraction = ((x - space.leftPx) / width).coerceIn(0f, 1f)
                return (fraction * (spec.span.pointCount - 1).coerceAtLeast(0)).roundToInt()
              }

              scrubChart(
                  onSelect = { x -> selection.value = indexAt(x) },
                  onClear = { selection.value = null },
              )
            }
    ) {
      val plot = timePlot(size.width, size.height, space) ?: return@Canvas
      drawTimeGrid(plot, spec, style)
      drawTimeLabels(plot, spec, ticks, style)
      drawPlot(plot)
      drawTimeAxes(plot, style)
      drawTimeValues(plot, spec, style)
    }
    Canvas(Modifier.fillMaxSize()) {
      val index = selection.value ?: return@Canvas
      val plot = timePlot(size.width, size.height, space) ?: return@Canvas
      val x = plot.x(markFraction(index, spec.span.pointCount))
      drawLine(
          style.selectedColor,
          Offset(x, plot.top),
          Offset(x, plot.top + plot.height),
          1.5.dp.toPx(),
      )
      drawMark(plot, index)
    }
    TimeTooltip(selection, spec)
  }
}

/** The time and value of the selected point, which is the only part a drag recomposes. */
@Composable
private fun BoxScope.TimeTooltip(selection: State<Int?>, spec: TimeChartSpec) {
  val index = selection.value ?: return
  ChartTooltip(
      time =
          spec.timeLabel(
              epochTimeMs(
                  spec.span.startUnixMs,
                  spec.span.totalSeconds,
                  spec.span.pointCount,
                  index,
              )
          ),
      value = spec.valueAt(index),
      fraction = markFraction(index, spec.span.pointCount),
      labelWidth = spec.axis.labelWidth,
  )
}

/** The lane lines: dashed, so they never read as data. */
private fun DrawScope.drawTimeGrid(plot: PlotBounds, spec: TimeChartSpec, style: TimeChartStyle) {
  val dash = dashEffect()
  for (line in spec.axis.lines) {
    val y = plot.y(line)
    drawLine(
        color = style.gridColor,
        start = Offset(plot.left, y),
        end = Offset(plot.left + plot.width, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = dash,
    )
  }
}

/** The hour grid and the clock labels under the plot. */
private fun DrawScope.drawTimeLabels(
    plot: PlotBounds,
    spec: TimeChartSpec,
    ticks: List<HourTick>,
    style: TimeChartStyle,
) {
  val paint = style.paint
  val labelY = plot.top + plot.height + paint.textSize + 4.dp.toPx()
  if (ticks.isEmpty()) {
    if (spec.span.startUnixMs <= 0) return
    paint.textAlign = Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(
        spec.timeLabel(spec.span.startUnixMs),
        plot.left,
        labelY,
        paint,
    )
    paint.textAlign = Paint.Align.RIGHT
    drawContext.canvas.nativeCanvas.drawText(
        spec.timeLabel(spec.span.startUnixMs + spec.span.totalSeconds * 1000L),
        plot.left + plot.width,
        labelY,
        paint,
    )
    return
  }
  // Every hour gets a grid line, but a label only when it clears the one before it, so a narrow
  // chart names every second hour instead of printing labels over each other.
  val gap = 8.dp.toPx()
  var lastRight = Float.NEGATIVE_INFINITY
  for (tick in ticks) {
    val x = plot.x(tick.fraction)
    drawLine(style.gridColor, Offset(x, plot.top), Offset(x, plot.top + plot.height), 1.dp.toPx())
    val text = spec.timeLabel(tick.timeMs)
    val textWidth = paint.measureText(text)
    val align =
        when {
          tick.fraction <= 0.02f -> Paint.Align.LEFT
          tick.fraction >= 0.98f -> Paint.Align.RIGHT
          else -> Paint.Align.CENTER
        }
    val textLeft =
        when (align) {
          Paint.Align.LEFT -> x
          Paint.Align.RIGHT -> x - textWidth
          else -> x - textWidth / 2f
        }
    if (textLeft < lastRight + gap || textLeft + textWidth > size.width) continue
    lastRight = textLeft + textWidth
    paint.textAlign = align
    drawContext.canvas.nativeCanvas.drawText(text, x, labelY, paint)
  }
}

/** The two axes of the plot. */
private fun DrawScope.drawTimeAxes(plot: PlotBounds, style: TimeChartStyle) {
  drawLine(
      style.axisColor,
      Offset(plot.left, plot.top),
      Offset(plot.left, plot.top + plot.height),
      1.dp.toPx(),
  )
  drawLine(
      style.axisColor,
      Offset(plot.left, plot.top + plot.height),
      Offset(plot.left + plot.width, plot.top + plot.height),
      1.dp.toPx(),
  )
}

/** The values of the y axis, right-aligned beside the plot. */
private fun DrawScope.drawTimeValues(plot: PlotBounds, spec: TimeChartSpec, style: TimeChartStyle) {
  val paint = style.paint
  paint.textAlign = Paint.Align.RIGHT
  for ((fraction, label) in spec.axis.labels) {
    drawContext.canvas.nativeCanvas.drawText(
        label,
        plot.left - 6.dp.toPx(),
        plot.y(fraction) + paint.textSize / 3f,
        paint,
    )
  }
}

private fun stageAtEpoch(segments: List<StageSegmentDto>, epoch: Int): String {
  var cursor = 0
  for (segment in segments) {
    if (segment.count <= 0) continue
    if (epoch < cursor + segment.count) return segment.stage
    cursor += segment.count
  }
  return "unknown"
}

/** What a screen reader is told a stage chart spans: its start and its end. */
@Composable
private fun stagesDescription(startUnixMs: Long, endUnixMs: Long, formats: Formats): String =
    stringResource(
        R.string.night_sleep_stages_description,
        formats.clockTime(startUnixMs),
        formats.clockTime(endUnixMs),
    )

@Composable
fun SleepStagesChart(
    segments: List<StageSegmentDto>,
    epochSeconds: Int,
    startUnixMs: Long,
    modifier: Modifier = Modifier,
    chartHeight: Int = 176,
    capabilities: JsonObject? = null,
    fullscreen: Boolean = false,
) {
  // Everything the chart draws is derived from the segments once, not on every recomposition.
  val lanes =
      remember(segments, capabilities) {
        applicableStages(segments, capabilities).ifEmpty { listOf("unknown") }
      }
  val total = remember(segments) { segments.sumOf { it.count }.coerceAtLeast(1) }
  val totalSeconds = total.toLong() * epochSeconds.coerceAtLeast(1)
  val endUnixMs = if (startUnixMs > 0) startUnixMs + totalSeconds * 1000L else 0L
  val formats = LocalFormats.current
  val description = stagesDescription(startUnixMs, endUnixMs, formats)
  val colors = mutableMapOf<String, Color>()
  for (stage in segments.map { it.stage }.distinct()) colors[stage] = stageColor(stage)
  val labels = lanes.associateWith { stringResource(stageLabelRes(it)) }
  val unknownLabel = stringResource(R.string.night_stage_unknown)
  val fallbackColor = MaterialTheme.colorScheme.outlineVariant
  val yLabels =
      lanes.mapIndexed { index, lane ->
        ((index + 0.5f) / lanes.size) to (labels[lane] ?: unknownLabel)
      }
  val horizontalLines = remember(lanes) { (1 until lanes.size).map { it.toFloat() / lanes.size } }
  val spec =
      remember(
          segments,
          capabilities,
          startUnixMs,
          total,
          yLabels,
          horizontalLines,
          description,
          formats,
          fullscreen,
      ) {
        TimeChartSpec(
            span = TimeChartSpan(startUnixMs, totalSeconds, total),
            axis =
                TimeChartAxis(
                    labels = yLabels,
                    lines = horizontalLines,
                    labelWidth = if (fullscreen) 96.dp else 68.dp,
                ),
            description = description,
            valueAt = { index -> labels[stageAtEpoch(segments, index)] ?: unknownLabel },
            timeLabel = { formats.clockTime(it) },
            large = fullscreen,
        )
      }
  TimeChart(
      spec = spec,
      modifier =
          if (fullscreen) modifier.fillMaxSize()
          else modifier.fillMaxWidth().height(chartHeight.dp),
      drawPlot = { plot ->
        drawStageLanes(plot, segments, StageLanes(lanes, total, colors), fallbackColor)
      },
  )
}

/** The lanes of a stage chart: their order, how many segments the night has and their colours. */
private class StageLanes(val stages: List<String>, val total: Int, val colors: Map<String, Color>)

/** The stages of the night as one lane per stage, in the order they happened. */
private fun DrawScope.drawStageLanes(
    plot: PlotBounds,
    segments: List<StageSegmentDto>,
    lanes: StageLanes,
    fallback: Color,
) {
  val laneHeight = plot.height / lanes.stages.size
  var cursor = 0
  for (segment in segments) {
    if (segment.count <= 0) continue
    val lane = lanes.stages.indexOf(segment.stage)
    if (lane >= 0) {
      val x0 = plot.left + (cursor.toFloat() / lanes.total.toFloat()) * plot.width
      val x1 = plot.left + ((cursor + segment.count).toFloat() / lanes.total.toFloat()) * plot.width
      val top = plot.top + lane * laneHeight + laneHeight * 0.17f
      drawRoundRect(
          color = lanes.colors[segment.stage] ?: fallback,
          topLeft = Offset(x0, top),
          size = Size((x1 - x0).coerceAtLeast(1f), laneHeight * 0.66f),
          cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
      )
    }
    cursor += segment.count
  }
}

/** The epoch-aligned HR points; null entries stay gaps instead of being bridged. */
fun hrSeriesPoints(series: HrSeriesDto?): List<Double?> {
  if (series == null || series.count <= 0) return emptyList()
  return List(series.count) { index ->
    series.mean_bpm.getOrNull(index)?.takeIf { it.isFinite() }
  }
}

/** Contiguous runs of valid HR points. A run of one point is drawn as a dot. */
fun hrSeriesRuns(points: List<Double?>): List<List<IndexedValue<Double>>> {
  val runs = mutableListOf<List<IndexedValue<Double>>>()
  var current = mutableListOf<IndexedValue<Double>>()
  points.forEachIndexed { index, value ->
    if (value == null) {
      if (current.isNotEmpty()) {
        runs += current
        current = mutableListOf()
      }
    } else {
      current += IndexedValue(index, value)
    }
  }
  if (current.isNotEmpty()) runs += current
  return runs
}

@Composable
fun HeartRateChart(
    series: HrSeriesDto,
    modifier: Modifier = Modifier,
    chartHeight: Int = 176,
    fullscreen: Boolean = false,
) {
  // A long night is thousands of points: the points, their range, the runs and the axis are what
  // the chart draws, and all of them are read once for the series they belong to.
  val formats = LocalFormats.current
  val points = remember(series) { hrSeriesPoints(series) }
  val values = remember(points) { points.filterNotNull() }
  if (values.isEmpty()) return
  val low = remember(values) { values.min() }
  val high = remember(values) { values.max() }
  val runs = remember(points) { hrSeriesRuns(points) }
  val axis = remember(low, high) { seriesAxis(low, high) }
  val totalSeconds = series.count.toLong() * series.epoch_seconds.coerceAtLeast(1)
  val endUnixMs = if (series.start_unix_ms > 0) series.start_unix_ms + totalSeconds * 1000L else 0L
  val description =
      stringResource(
          R.string.night_heart_rate_chart_description,
          formats.clockTime(series.start_unix_ms),
          formats.clockTime(endUnixMs),
          low.roundToInt(),
          high.roundToInt(),
      )
  val line = MaterialTheme.taiga.chartHr
  val noValue = stringResource(R.string.night_chart_no_value)
  // A flat series still gets a readable axis: at least 10 bpm, centred on the samples, with whole
  // tick values that never repeat. The unit sits on the top label, so the others stay plain numbers.
  val yLabels =
      seriesAxisTicks(axis).mapIndexed { index, value ->
        val label = value.toString()
        (index / 4f) to
            if (index == 0) formats.beatsPerMinute(label) else label
      }
  val spec =
      remember(series, axis, points, yLabels, description, formats, fullscreen, noValue) {
        TimeChartSpec(
            span = TimeChartSpan(series.start_unix_ms, totalSeconds, series.count),
            axis =
                TimeChartAxis(
                    labels = yLabels,
                    lines = HeartRateGridLines,
                    labelWidth = if (fullscreen) 84.dp else 60.dp,
                ),
            description = description,
            valueAt = { index ->
              points.getOrNull(index)?.let { formats.beatsPerMinute(it) } ?: noValue
            },
            timeLabel = { formats.clockTime(it) },
            large = fullscreen,
        )
      }
  TimeChart(
      spec = spec,
      modifier =
          if (fullscreen) modifier.fillMaxSize()
          else modifier.fillMaxWidth().height(chartHeight.dp),
      drawPlot = { plot -> drawHeartRateLine(plot, runs, series.count, axis, line) },
      drawMark = { plot, index ->
        points.getOrNull(index)?.let { value ->
          drawCircle(
              line,
              radius = 3.dp.toPx(),
              center = heartRatePoint(plot, index, value, series.count, axis),
          )
        }
      },
  )
}

/** The five dashed lanes of the heart-rate axis, from the top of the plot to the bottom. */
private val HeartRateGridLines = (0..4).map { it / 4f }

/** Where the HR point at [index] sits in the plot: its time across, its value down. */
private fun heartRatePoint(
    plot: PlotBounds,
    index: Int,
    value: Double,
    count: Int,
    axis: ClosedFloatingPointRange<Double>,
): Offset {
  val span = axis.endInclusive - axis.start
  val y = plot.y(((axis.endInclusive - value) / span).toFloat())
  val x = if (count <= 1) plot.x(0.5f) else plot.x(index.toFloat() / (count - 1).toFloat())
  return Offset(x, y)
}

/**
 * The heart-rate line as one path and its lone points as one batch of dots: a night of a thousand
 * points is two draw calls, not a thousand.
 */
private fun DrawScope.drawHeartRateLine(
    plot: PlotBounds,
    runs: List<List<IndexedValue<Double>>>,
    count: Int,
    axis: ClosedFloatingPointRange<Double>,
    color: Color,
) {
  val path = Path()
  val dots = mutableListOf<Offset>()
  for (run in runs) {
    val first = run.first()
    if (run.size == 1) {
      dots += heartRatePoint(plot, first.index, first.value, count, axis)
      continue
    }
    val start = heartRatePoint(plot, first.index, first.value, count, axis)
    path.moveTo(start.x, start.y)
    for (position in 1 until run.size) {
      val at = heartRatePoint(plot, run[position].index, run[position].value, count, axis)
      path.lineTo(at.x, at.y)
    }
  }
  drawPath(
      path,
      color,
      style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
  drawPoints(
      points = dots,
      pointMode = PointMode.Points,
      color = color,
      strokeWidth = 3.dp.toPx(),
      cap = StrokeCap.Round,
  )
}

/**
 * One row per stage of the timeline: its swatch, its name, how long it lasted and its share of the
 * recording. The stacked bar above it shows the same timeline in order.
 */
@Composable
fun StageBreakdown(
    segments: List<StageSegmentDto>,
    epochSeconds: Int,
    modifier: Modifier = Modifier,
    capabilities: JsonObject? = null,
) {
  val space = MaterialTheme.taiga.space
  val formats = LocalFormats.current
  val durations = remember(segments, epochSeconds) { stageSeconds(segments, epochSeconds) }
  val total = durations.values.sum().coerceAtLeast(1)
  val stages = remember(segments, capabilities) { applicableStages(segments, capabilities) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(space.s)) {
    for (stage in stages) {
      val seconds = durations[stage] ?: 0L
      val share = (seconds.toDouble() / total.toDouble() * 100).roundToInt()
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(stageColor(stage), CircleShape))
        Spacer(Modifier.width(space.s))
        Text(
            text = stringResource(stageLabelRes(stage)),
            style = MaterialTheme.taiga.text.body,
            modifier = Modifier.weight(1f),
        )
        Text(text = formats.duration(seconds * 1000L), style = MaterialTheme.taiga.text.body)
        Spacer(Modifier.width(space.m))
        Text(
            text = formats.percent(share.toDouble()),
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecordingChartsPreview() {
  val segments =
      listOf(
          StageSegmentDto("wake", 2),
          StageSegmentDto("light", 8),
          StageSegmentDto("deep", 6),
          StageSegmentDto("rem", 4),
          StageSegmentDto("light", 5),
          StageSegmentDto("unknown", 1),
      )
  PreviewTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ScoreRing(82.0, "Sleep score", size = 72)
    }
    StageBar(segments)
    SleepStagesChart(
        segments = segments,
        epochSeconds = 30,
        startUnixMs = 1_790_000_000_000,
        chartHeight = 140,
    )
    StageBreakdown(segments = segments, epochSeconds = 30)
    HeartRateChart(
        series =
            HrSeriesDto(
                epoch_seconds = 30,
                start_unix_ms = 1_790_000_000_000,
                count = 16,
                mean_bpm =
                    listOf(
                        62.0, 60.0, 58.0, 57.0, null, 61.0, 63.0, 66.0,
                        64.0, 62.0, 60.0, 59.0, 58.0, 57.0, 56.0, 58.0,
                    ),
                sample_count = List(16) { 30 },
            ),
        chartHeight = 140,
    )
  }
}
