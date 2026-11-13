package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.TrendNight
import com.rbmr.taigasleep.insights.clockMinuteOfNightMinute
import com.rbmr.taigasleep.ui.theme.taiga
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One recording as a chart mark: its value, whether the night's data is poor, and where it opens. */
data class TrendPoint(val value: Double, val poor: Boolean, val night: TrendNight)

/** One floating bar: the recording's sleep window on a clock axis, in minutes since noon. */
data class TrendSpanPoint(val night: TrendNight, val start: Double, val end: Double)

/** One line of a [TrendChart]. Two series share the axis and are told apart by the legend. */
data class TrendSeries(
    val label: String,
    val points: List<TrendPoint?>,
    val color: Color,
    /** A dashed series is the second, comparable value of the same recording. */
    val dashed: Boolean = false,
)

/** A named value above a chart, such as `Average` and `7h 52m`. */
data class TrendSummary(val name: String, val value: String)

/**
 * A thin dotted reference line across a [TrendChart], such as the usual bedtime or the intended
 * sleep duration. [value] is in the chart's own axis units; the line is drawn only when it is set.
 */
data class TrendReference(val label: String, val value: Double)

/** How a [TrendChart] draws the recordings of its range. */
sealed interface TrendChartKind {
  /** One line per series; a day without a value stays a gap. */
  data class Line(val series: List<TrendSeries>) : TrendChartKind

  /** One bar per recording from the bottom of the axis, with an optional dashed average line. */
  data class Bars(val points: List<TrendPoint?>, val average: Double? = null) : TrendChartKind

  /** One floating bar per recording, from [TrendSpanPoint.start] to [TrendSpanPoint.end]. */
  data class FloatingBars(val spans: List<TrendSpanPoint?>) : TrendChartKind
}

/** Minutes since noon draw as a clock time, crossing midnight. */
fun trendClock(minuteOfDay: Int): String {
  val minute = (minuteOfDay % 1440 + 1440) % 1440
  return String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)
}

/**
 * A y axis that ends on its outer ticks, so every label is a round, evenly spaced value. [min] and
 * [max] are the data's own bounds; the axis is widened to the ticks that cover them and the raw
 * bounds are not added as labels of their own.
 */
data class TrendAxis(
    val min: Double,
    val max: Double,
    val ticks: List<Pair<Float, String>>,
    /** The smallest value is at the top, as on a clock that runs down the page. */
    val inverted: Boolean = false,
) {
  /** Where [value] sits from the top of the plot, 0 at the top and 1 at the bottom. */
  fun fraction(value: Double): Float =
      when {
        max <= min -> 0.5f
        inverted -> ((value - min) / (max - min)).toFloat()
        else -> ((max - value) / (max - min)).toFloat()
      }
}

/** The round steps the metric axes use, times a power of ten. */
val MetricSteps = doubleArrayOf(1.0, 2.0, 2.5, 5.0)

/** A metric that only ever reads as a whole number, such as a score or a heart rate. */
val WholeSteps = doubleArrayOf(1.0, 2.0, 5.0)

/** The total-sleep axis counts whole hours, so its steps are whole hours as well. */
val HourSteps = doubleArrayOf(1.0, 2.0, 3.0, 5.0)

/** The schedule axis counts clock minutes, so its steps are whole hours too. */
val ClockSteps = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0)

/**
 * The shared axis of every trend chart: the smallest round step that leaves at most five ticks,
 * with the range extended to the outer ticks. Close values still get at least three ticks, and
 * nothing is added for the raw minimum or maximum. [minStep] keeps the steps at or above a unit
 * such as one hour.
 */
fun trendAxis(
    min: Double,
    max: Double,
    format: (Double) -> String,
    steps: DoubleArray = MetricSteps,
    minStep: Double = 0.0,
    inverted: Boolean = false,
): TrendAxis {
  val lowValue = if (min.isFinite()) min else 0.0
  val highValue = if (max.isFinite()) max else lowValue
  val low: Double
  val high: Double
  if (highValue > lowValue) {
    low = lowValue
    high = highValue
  } else {
    // A flat line still gets an axis around its value instead of a single label.
    low = lowValue - 1.0
    high = lowValue + 1.0
  }
  val step = chooseStep(low, high, steps, minStep)
  val axisMin = floor(low / step) * step
  var axisMax = ceil(high / step) * step
  // A span narrower than the round steps still gets three labels instead of two.
  while (tickCount(axisMin, axisMax, step) < 3) axisMax += step
  val axis = TrendAxis(axisMin, axisMax, emptyList(), inverted)
  val ticks = mutableListOf<Pair<Float, String>>()
  // The first tick is the one at the top of the plot.
  var value = if (inverted) axisMin else axisMax
  while (value >= axisMin - step * 1e-6 && value <= axisMax + step * 1e-6) {
    ticks += axis.fraction(value) to format(value)
    value += if (inverted) step else -step
  }
  return axis.copy(ticks = ticks)
}

/** The smallest step from [steps] times a power of ten that gives an axis of at most five ticks. */
private fun chooseStep(
    low: Double,
    high: Double,
    steps: DoubleArray,
    minStep: Double,
): Double {
  val span = (high - low).coerceAtLeast(1e-9)
  var scale = maxOf(10.0.pow(floor(log10(span)) - 1), minStep)
  repeat(14) {
    for (multiple in steps) {
      val step = multiple * scale
      if (step > 0 &&
          tickCount(floor(low / step) * step, ceil(high / step) * step, step) <= 5) {
        return step
      }
    }
    scale *= 10
  }
  return scale
}

private fun tickCount(axisMin: Double, axisMax: Double, step: Double): Int =
    ((axisMax - axisMin) / step).roundToInt() + 1

/** A tick number without a needless decimal, so a 2.5 step still reads as such. */
fun trendNumber(value: Double): String {
  val whole = value.roundToLong()
  if (abs(value - whole) < 1e-6) return whole.toString()
  return String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.')
}

/** Total sleep arrives in seconds; the charts draw it in hours. */
fun totalSleepHours(seconds: Double?): Double? = seconds?.div(3600.0)

/** The total-sleep axis in hours, covering the values and the intended duration. */
fun hoursAxis(values: List<Double?>, targetHours: Double?): TrendAxis {
  val highest = maxOf(values.filterNotNull().maxOfOrNull { it } ?: 0.0, targetHours ?: 0.0, 1.0)
  return trendAxis(
      min = 0.0,
      max = highest,
      format = { "${trendNumber(it)} h" },
      steps = HourSteps,
      minStep = 1.0,
  )
}

private val ChartHeight = 176.dp
private val LeftLabelWidth = 48.dp
private val LargeLeftLabelWidth = 64.dp

private data class TrendPlot(
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

/**
 * One metrics trend: the title, the named summary values, the legend of its series and reference
 * lines, and the chart itself, with the expand button that opens the same chart full screen. A
 * range without enough recordings shows [emptyMessage] instead.
 *
 * The y axis is labelled with its round values and the unit on the top label; the grid is dashed at
 * the top and the middle; the x axis names the first, middle and last date. Reference lines are thin
 * and dotted, so they never read as data. Touching or dragging over the chart shows a crosshair
 * and the date with the value or values of that day, like the charts of a recording. Nothing opens
 * on a tap.
 */
@Composable
fun TrendChart(
    title: String,
    days: List<LocalDate>,
    kind: TrendChartKind,
    summary: List<TrendSummary>,
    description: String,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
    references: List<TrendReference> = emptyList(),
    axisFormat: (Double) -> String = ::trendNumber,
    valueFormat: (Double) -> String = axisFormat,
    topLabel: ((String) -> String)? = null,
    steps: DoubleArray = MetricSteps,
    minStep: Double = 0.0,
) {
  val space = MaterialTheme.taiga.space
  var expanded by rememberSaveable { mutableStateOf(false) }
  val axis = trendChartAxis(kind, axisFormat, steps, minStep, references)
  val series = (kind as? TrendChartKind.Line)?.series.orEmpty()
  TaigaCard(modifier, contentPadding = PaddingValues(top = space.xs)) {
    ChartHeader(title = title) {
      if (axis != null && emptyMessage == null) {
        ChartExpandButton(onClick = { expanded = true })
      }
    }
    Column(Modifier.padding(start = space.m, end = space.m, bottom = space.m)) {
      if (emptyMessage != null) {
        Text(
            text = emptyMessage,
            style = MaterialTheme.taiga.text.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else if (axis != null) {
        if (summary.isNotEmpty()) TrendSummaryRow(summary)
        if (series.size > 1 || references.isNotEmpty()) {
          TrendLegend(series, references, Modifier.padding(top = space.xs))
        }
        TrendPlotCanvas(
            days = days,
            kind = kind,
            axis = axis,
            references = references,
            description = description,
            topLabel = topLabel,
            valueFormat = valueFormat,
            large = false,
            modifier = Modifier.fillMaxWidth().padding(top = space.s).height(ChartHeight),
        )
      }
    }
  }
  if (expanded && axis != null && emptyMessage == null) {
    ChartFullscreenDialog(title = title, onDismiss = { expanded = false }) { contentModifier ->
      Column(contentModifier) {
        if (summary.isNotEmpty()) TrendSummaryRow(summary)
        if (series.size > 1 || references.isNotEmpty()) {
          TrendLegend(series, references, Modifier.padding(top = space.xs))
        }
        TrendPlotCanvas(
            days = days,
            kind = kind,
            axis = axis,
            references = references,
            description = description,
            topLabel = topLabel,
            valueFormat = valueFormat,
            large = true,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = space.s),
        )
      }
    }
  }
}

/**
 * The axis that covers every value of [kind] and every reference line; bars start at zero so their
 * heights stay honest.
 */
private fun trendChartAxis(
    kind: TrendChartKind,
    format: (Double) -> String,
    steps: DoubleArray,
    minStep: Double,
    references: List<TrendReference>,
): TrendAxis? {
  val values =
      when (kind) {
        is TrendChartKind.Line ->
            kind.series.flatMap { series -> series.points.mapNotNull { it?.value } }
        is TrendChartKind.Bars ->
            kind.points.mapNotNull { it?.value } + listOfNotNull(kind.average)
        is TrendChartKind.FloatingBars ->
            kind.spans.flatMap { span -> span?.let { listOf(it.start, it.end) } ?: emptyList() }
      }
  if (values.isEmpty()) return null
  val covered = values + references.map { it.value }
  val low = if (kind is TrendChartKind.Bars) 0.0 else covered.min()
  // A clock axis reads like the day does: earlier at the top, later below.
  return trendAxis(low, covered.max(), format, steps, minStep, inverted = kind is TrendChartKind.FloatingBars)
}

/** The lines of the tooltip for the day at [index]: its values, or none when the day has no mark. */
private fun TrendChartKind.valueLines(
    index: Int,
    valueFormat: (Double) -> String,
): List<String> =
    when (this) {
      is TrendChartKind.Line -> {
        val named = series.size > 1
        series.mapNotNull { line ->
          line.points.getOrNull(index)?.let { point ->
            val value = valueFormat(point.value)
            if (named) "${line.label}: $value" else value
          }
        }
      }
      is TrendChartKind.Bars -> listOfNotNull(points.getOrNull(index)?.let { valueFormat(it.value) })
      is TrendChartKind.FloatingBars ->
          listOfNotNull(
              spans.getOrNull(index)?.let { "${valueFormat(it.start)} – ${valueFormat(it.end)}" }
          )
    }

@Composable
private fun TrendSummaryRow(summary: List<TrendSummary>, modifier: Modifier = Modifier) {
  Row(
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.l),
      verticalAlignment = Alignment.Top,
  ) {
    for (item in summary) {
      Column {
        Text(
            text = item.name,
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = item.value, style = MaterialTheme.taiga.text.row)
      }
    }
  }
}

/**
 * The legend of a chart: the line style and colour of each series, then a dotted swatch for every
 * reference line.
 */
@Composable
private fun TrendLegend(
    series: List<TrendSeries>,
    references: List<TrendReference>,
    modifier: Modifier = Modifier,
) {
  val referenceColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
  Row(
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.m),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    for (item in series) {
      LegendEntry(label = item.label, color = item.color, dashed = item.dashed, dotted = false)
    }
    for (item in references) {
      LegendEntry(label = item.label, color = referenceColor, dashed = false, dotted = true)
    }
  }
}

@Composable
private fun LegendEntry(label: String, color: Color, dashed: Boolean, dotted: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.width(16.dp).height(2.dp)) {
      drawLine(
          color = color,
          start = Offset(0f, size.height / 2f),
          end = Offset(size.width, size.height / 2f),
          strokeWidth = 2.dp.toPx(),
          cap = StrokeCap.Round,
          pathEffect =
              when {
                dotted -> dotEffect()
                dashed -> dashEffect()
                else -> null
              },
      )
    }
    Spacer(Modifier.width(MaterialTheme.taiga.space.s))
    Text(
        text = label,
        style = MaterialTheme.taiga.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun DrawScope.dashEffect(): PathEffect =
    PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))

/** The reference lines are thinner and dotted, so they stay clearly apart from the data. */
private fun DrawScope.dotEffect(): PathEffect =
    PathEffect.dashPathEffect(
        floatArrayOf(1.5.dp.toPx(), 3.dp.toPx()),
        phase = 0f,
    )

/**
 * The frame and the marks of a trend chart. The labels stay clear of the plot. Touching or
 * dragging selects the day under the finger: a crosshair marks its column and a tooltip gives the
 * date and its values.
 */
@Composable
private fun TrendPlotCanvas(
    days: List<LocalDate>,
    kind: TrendChartKind,
    axis: TrendAxis,
    references: List<TrendReference>,
    description: String,
    topLabel: ((String) -> String)?,
    valueFormat: (Double) -> String,
    large: Boolean,
    modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  // A large system font enlarges the axis labels too, so the room reserved for them follows it.
  val fontScale = density.fontScale.coerceIn(1f, 1.6f)
  val leftLabelWidth = (if (large) LargeLeftLabelWidth else LeftLabelWidth) * fontScale
  val leftPx = with(density) { leftLabelWidth.toPx() }
  val topPx = with(density) { ((if (large) 18.dp else 16.dp) * fontScale).toPx() }
  val bottomPx = with(density) { ((if (large) 28.dp else 24.dp) * fontScale).toPx() }
  val labelSp = if (large) 13.sp else 12.sp
  val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
  val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val markColor = MaterialTheme.colorScheme.primary
  val referenceColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
  val labels = dayLabels(days)
  var selected by remember { mutableStateOf<Int?>(null) }
  val selectedColor = MaterialTheme.colorScheme.primary

  Box(modifier) {
    Canvas(
        modifier =
            Modifier.fillMaxSize().semantics { contentDescription = description }.pointerInput(
                days,
                leftPx,
            ) {
              awaitEachGesture {
                fun indexAt(x: Float): Int {
                  val width = (size.width.toFloat() - leftPx).coerceAtLeast(1f)
                  return (((x - leftPx) / width) * days.size).toInt().coerceIn(0, days.lastIndex)
                }

                val down = awaitFirstDown(requireUnconsumed = false)
                if (days.isEmpty() || down.position.x < leftPx) return@awaitEachGesture
                selected = indexAt(down.position.x)
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                  event.changes.lastOrNull()?.let { selected = indexAt(it.position.x) }
                  event = awaitPointerEvent()
                }
                selected = null
              }
            }
    ) {
      if (size.width <= leftPx || size.height <= topPx + bottomPx) return@Canvas
      val plot =
          TrendPlot(
              left = leftPx,
              top = topPx,
              width = size.width - leftPx,
              height = size.height - topPx - bottomPx,
          )
      val paint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = labelSp.toPx()
          }
      val dash = dashEffect()
      val dot = dotEffect()

      for (fraction in floatArrayOf(0f, 0.5f)) {
        val y = plot.y(fraction)
        drawLine(
            color = gridColor,
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
        )
      }

      for ((index, label) in labels) {
        val x = plot.column(index, days.size)
        paint.textAlign =
            when {
              days.size == 1 -> Paint.Align.CENTER
              index == 0 -> Paint.Align.LEFT
              index == days.lastIndex -> Paint.Align.RIGHT
              else -> Paint.Align.CENTER
            }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            x,
            plot.top + plot.height + paint.textSize + 4.dp.toPx(),
            paint,
        )
      }

      when (kind) {
        is TrendChartKind.Line -> {
          for (series in kind.series) {
            drawTrendLine(plot, series, axis, large, dash)
          }
        }
        is TrendChartKind.Bars -> {
          drawTrendBars(plot, kind.points, axis, large, markColor)
          kind.average?.let { average ->
            val y = plot.y(axis.fraction(average))
            drawLine(
                color = referenceColor,
                start = Offset(plot.left, y),
                end = Offset(plot.right, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = dash,
            )
          }
        }
        is TrendChartKind.FloatingBars -> {
          drawTrendSpans(plot, kind.spans, axis, large, markColor)
        }
      }

      // The reference lines sit above the marks: thin, dotted, and labelled at their right end.
      paint.textAlign = Paint.Align.RIGHT
      for (reference in references) {
        val y = plot.y(axis.fraction(reference.value))
        drawLine(
            color = referenceColor,
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dot,
        )
        // A label that would sit above the plot goes under its line instead.
        val above = y - paint.textSize - 2.dp.toPx() >= plot.top
        drawContext.canvas.nativeCanvas.drawText(
            reference.label,
            plot.right - 2.dp.toPx(),
            if (above) y - 2.dp.toPx() else y + paint.textSize + 2.dp.toPx(),
            paint,
        )
      }

      drawLine(
          axisColor,
          Offset(plot.left, plot.top),
          Offset(plot.left, plot.top + plot.height),
          1.dp.toPx(),
      )
      drawLine(
          axisColor,
          Offset(plot.left, plot.top + plot.height),
          Offset(plot.right, plot.top + plot.height),
          1.dp.toPx(),
      )

      selected?.takeIf { it < days.size }?.let { index ->
        val x = plot.column(index, days.size)
        drawLine(selectedColor, Offset(x, plot.top), Offset(x, plot.top + plot.height), 1.5.dp.toPx())
      }

      paint.textAlign = Paint.Align.RIGHT
      for ((tickIndex, tick) in axis.ticks.withIndex()) {
        val label =
            if (tickIndex == 0 && topLabel != null) topLabel(tick.second) else tick.second
        drawContext.canvas.nativeCanvas.drawText(
            label,
            plot.left - 6.dp.toPx(),
            plot.y(tick.first) + paint.textSize / 3f,
            paint,
        )
      }
    }
    val index = selected
    if (index != null && index < days.size) {
      val lines = kind.valueLines(index, valueFormat)
      ChartTooltip(
          time = days[index].format(tooltipDate()),
          value = lines.joinToString("\n").ifEmpty { stringResource(R.string.trends_no_value) },
          fraction = (index + 0.5f) / days.size,
          labelWidth = leftLabelWidth,
      )
    }
  }
}

private fun tooltipDate(): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** One line with a point per recording; a day without a value leaves a gap in the line. */
private fun DrawScope.drawTrendLine(
    plot: TrendPlot,
    series: TrendSeries,
    axis: TrendAxis,
    large: Boolean,
    dash: PathEffect,
) {
  val stroke = (if (large) 2.5.dp else 2.dp).toPx()
  val radius = (if (large) 3.5.dp else 2.5.dp).toPx()
  for (index in 1 until series.points.size) {
    val from = series.points[index - 1] ?: continue
    val to = series.points[index] ?: continue
    val alpha = if (from.poor || to.poor) 0.4f else 1f
    drawLine(
        color = series.color.copy(alpha = alpha),
        start = Offset(plot.column(index - 1, series.points.size), plot.y(axis.fraction(from.value))),
        end = Offset(plot.column(index, series.points.size), plot.y(axis.fraction(to.value))),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
        pathEffect = if (series.dashed) dash else null,
    )
  }
  for ((index, point) in series.points.withIndex()) {
    if (point == null) continue
    drawCircle(
        color = series.color.copy(alpha = if (point.poor) 0.4f else 1f),
        radius = radius,
        center = Offset(plot.column(index, series.points.size), plot.y(axis.fraction(point.value))),
    )
  }
}

/** One bar per recording, from the bottom of the axis. */
private fun DrawScope.drawTrendBars(
    plot: TrendPlot,
    points: List<TrendPoint?>,
    axis: TrendAxis,
    large: Boolean,
    color: Color,
) {
  val width = (plot.width / points.size.toFloat()) * if (large) 0.5f else 0.55f
  val baseline = plot.y(1f)
  for ((index, point) in points.withIndex()) {
    if (point == null) continue
    val center = plot.column(index, points.size)
    val top = plot.y(axis.fraction(point.value))
    drawRoundRect(
        color = color.copy(alpha = if (point.poor) 0.4f else 1f),
        topLeft = Offset(center - width / 2f, min(top, baseline)),
        size = Size(width, abs(top - baseline).coerceAtLeast(1f)),
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
    )
  }
}

/** One floating bar per recording, from bedtime to wake time on the clock axis. */
private fun DrawScope.drawTrendSpans(
    plot: TrendPlot,
    spans: List<TrendSpanPoint?>,
    axis: TrendAxis,
    large: Boolean,
    color: Color,
) {
  val width = (plot.width / spans.size.toFloat()) * if (large) 0.5f else 0.6f
  for ((index, span) in spans.withIndex()) {
    if (span == null) continue
    val center = plot.column(index, spans.size)
    val start = plot.y(axis.fraction(span.start))
    val end = plot.y(axis.fraction(span.end))
    drawRoundRect(
        color = color.copy(alpha = if (span.night.poorData) 0.4f else 1f),
        topLeft = Offset(center - width / 2f, min(start, end)),
        size = Size(width, abs(end - start).coerceAtLeast(2.dp.toPx())),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
  }
}

/** The first, middle and last date of the range, which are the only x labels a chart needs. */
private fun dayLabels(days: List<LocalDate>): Map<Int, String> {
  if (days.isEmpty()) return emptyMap()
  val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
  val indexes = linkedSetOf(0, days.size / 2, days.lastIndex)
  return indexes.associateWith { days[it].format(formatter) }.toSortedMap()
}

/** The compact integer a score or a percent tick shows. */
fun trendWhole(value: Double): String = value.roundToInt().toString()

private fun previewNight(date: LocalDate): TrendNight =
    TrendNight(
        key = date.toString(),
        localId = null,
        remoteId = null,
        analysisId = null,
        date = date,
        startMinuteOfDay = 23 * 60 + 12,
        endMinuteOfDay = 7 * 60 + 4,
        onsetMinuteOfDay = 23 * 60 + 30,
        finalAwakeningMinuteOfDay = 6 * 60 + 50,
        durationSeconds = 8 * 3_600,
        dataQuality = 88.0,
        poorData = false,
        totalSleepSeconds = 7.0 * 3_600,
        score = 78.0,
        efficiency = 88.0,
        heartRateMinBpm = 48.0,
        heartRateMeanBpm = 56.0,
        hrvRmssdMs = 52.0,
    )

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrendChartPreview() {
  PreviewTheme {
    val days = List(7) { LocalDate.of(2026, 11, 5).plusDays(it.toLong()) }
    val nights = days.map(::previewNight)
    val primary = MaterialTheme.colorScheme.primary
    TrendChart(
        title = "Sleep score",
        days = days,
        kind =
            TrendChartKind.Line(
                listOf(
                    TrendSeries(
                        label = "Average",
                        points =
                            nights.mapIndexed { index, night ->
                              TrendPoint(68.0 + index * 2, false, night)
                            },
                        color = primary,
                    )
                )
            ),
        summary = listOf(TrendSummary("Average", "74")),
        description = "Sleep score over seven recordings",
        axisFormat = ::trendWhole,
    )
    TrendChart(
        title = "Total sleep",
        days = days,
        kind =
            TrendChartKind.Bars(
                points =
                    nights.mapIndexed { index, night ->
                      TrendPoint(6.5 + index * 0.3, index == 3, night)
                    },
                average = 7.1,
            ),
        summary = listOf(TrendSummary("Average", "7h 6m")),
        description = "Total sleep over seven recordings",
        references = listOf(TrendReference("Intended", 7.5)),
        topLabel = { "$it h" },
        steps = HourSteps,
        minStep = 1.0,
    )
    TrendChart(
        title = "Sleep schedule",
        days = days,
        kind =
            TrendChartKind.FloatingBars(
                nights.map { TrendSpanPoint(it, 400.0, 1120.0) }
            ),
        summary =
            listOf(
                TrendSummary("Average bedtime", "23:12"),
                TrendSummary("Average wake time", "07:04"),
            ),
        description = "Sleep schedule over seven recordings",
        references =
            listOf(
                TrendReference("Usual bedtime", 400.0),
                TrendReference("Usual wake time", 1120.0),
            ),
        axisFormat = { trendClock(clockMinuteOfNightMinute(it.roundToInt())) },
        steps = ClockSteps,
        minStep = 60.0,
    )
  }
}
