package com.rbmr.taigasleep.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rbmr.taigasleep.insights.TrendNight
import com.rbmr.taigasleep.insights.clockMinuteOfNightMinute
import com.rbmr.taigasleep.insights.nightMinuteOfDay
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

/** One night as a chart mark: its value, whether the night's data is poor, and where it opens. */
data class TrendPoint(val value: Double, val poor: Boolean, val night: TrendNight)

/** Minutes since noon draw as a clock time, crossing midnight. */
fun trendClock(minuteOfDay: Int): String {
  val minute = (minuteOfDay % 1440 + 1440) % 1440
  return String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)
}

/** The calendar date under a mark, for the tooltip and the accessibility description. */
fun trendDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

/**
 * A y axis that ends on its outer ticks, so every label is a round, evenly spaced value. [min] and
 * [max] are the data's own bounds; the axis is widened to the ticks that cover them and the raw
 * bounds are not added as labels of their own.
 */
data class TrendAxis(val min: Double, val max: Double, val ticks: List<Pair<Float, String>>) {
  /** Where [value] sits from the top of the plot, 0 at the top and 1 at the bottom. */
  fun fraction(value: Double): Float =
      if (max <= min) 0.5f else ((max - value) / (max - min)).toFloat()
}

/** The round steps the metric axes use, times a power of ten. */
private val MetricSteps = doubleArrayOf(1.0, 2.0, 2.5, 5.0)

/** The total-sleep axis counts whole hours, so its steps are whole hours as well. */
private val HourSteps = doubleArrayOf(1.0, 2.0, 3.0, 5.0)

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
  val ticks = mutableListOf<Pair<Float, String>>()
  var value = axisMax
  while (value >= axisMin - step * 1e-6) {
    ticks += ((axisMax - value) / (axisMax - axisMin)).toFloat() to format(value)
    value -= step
  }
  return TrendAxis(axisMin, axisMax, ticks)
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

private val LeftLabelWidth = 54.dp
private val LargeLeftLabelWidth = 84.dp

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
 * The common frame of a trend chart: the y labels, a few day gridlines, the tap area and the
 * selected column. The chart itself draws its marks inside [drawMarks].
 */
@Composable
private fun TrendFrame(
    days: List<LocalDate>,
    selected: Int?,
    yTicks: List<Pair<Float, String>>,
    description: String,
    large: Boolean,
    modifier: Modifier,
    onTap: (Int) -> Unit,
    drawMarks: DrawScope.(TrendPlot, Paint) -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
  val density = LocalDensity.current
  // A large system font enlarges the axis labels too, so the room reserved for them follows it.
  val fontScale = density.fontScale.coerceIn(1f, 1.6f)
  val leftLabelWidth = (if (large) LargeLeftLabelWidth else LeftLabelWidth) * fontScale
  val leftPx = with(density) { leftLabelWidth.toPx() }
  val topPx = with(density) { (if (large) 10.dp else 8.dp).toPx() }
  val bottomPx = with(density) { ((if (large) 28.dp else 22.dp) * fontScale).toPx() }
  val labelSp = if (large) 13.sp else 8.sp
  val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
  val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val selectedColor = MaterialTheme.colorScheme.primary
  val labels = remember(days) { dayLabels(days) }

  Box(modifier) {
    Canvas(
        modifier =
            Modifier.fillMaxSize().semantics { contentDescription = description }.pointerInput(
                days.size,
                leftPx,
            ) {
              detectTapGestures { position ->
                if (days.isEmpty() || position.x < leftPx) return@detectTapGestures
                val plotWidth = (size.width - leftPx).coerceAtLeast(1f)
                val index =
                    ((position.x - leftPx) / plotWidth * days.size)
                        .toInt()
                        .coerceIn(0, days.lastIndex)
                onTap(index)
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
            textSize = with(density) { labelSp.toPx() }
          }

      for ((fraction, _) in yTicks) {
        val y = plot.y(fraction)
        drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
      }
      for ((index, label) in labels) {
        val x = plot.column(index, days.size)
        drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.top + plot.height), 1.dp.toPx())
        paint.textAlign =
            when (index) {
              0 -> Paint.Align.LEFT
              days.lastIndex -> Paint.Align.RIGHT
              else -> Paint.Align.CENTER
            }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            x,
            plot.top + plot.height + paint.textSize + 4.dp.toPx(),
            paint,
        )
      }

      drawMarks(plot, paint)

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

      paint.textAlign = Paint.Align.RIGHT
      for ((fraction, label) in yTicks) {
        drawContext.canvas.nativeCanvas.drawText(
            label,
            plot.left - 6.dp.toPx(),
            plot.y(fraction) + paint.textSize / 3f,
            paint,
        )
      }

      if (selected != null) {
        val x = plot.column(selected, days.size)
        drawLine(
            selectedColor,
            Offset(x, plot.top),
            Offset(x, plot.top + plot.height),
            1.5.dp.toPx(),
        )
      }
    }
    overlay()
  }
}

/** One line with a point per night; a day without a value leaves a gap in the line. */
@Composable
fun TrendLineChart(
    days: List<LocalDate>,
    points: List<TrendPoint?>,
    min: Double,
    max: Double,
    yFormat: (Double) -> String,
    description: String,
    tooltip: @Composable (TrendNight) -> String,
    onOpen: (TrendNight) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
  var selected by remember(days, points) { mutableStateOf<Int?>(null) }
  val color = MaterialTheme.colorScheme.primary
  val axis = remember(min, max) { trendAxis(min, max, yFormat) }
  TrendFrame(
      days = days,
      selected = selected,
      yTicks = axis.ticks,
      description = description,
      large = large,
      modifier = if (large) modifier.fillMaxSize() else modifier.fillMaxWidth().height(176.dp),
      onTap = { index -> selected = index.takeIf { points.getOrNull(it) != null } },
      drawMarks = { plot, _ ->
        drawTrendSeries(plot, points, color, large, axis)
        selected?.let { index ->
          points.getOrNull(index)?.let { point ->
            drawCircle(
                color,
                radius = (if (large) 5.dp else 4.dp).toPx(),
                center = Offset(plot.column(index, days.size), plot.y(axis.fraction(point.value))),
            )
          }
        }
      },
      overlay = {
        val night = selected?.let { points.getOrNull(it)?.night }
        nightTooltip(selected, days.size, night, tooltip, onOpen, large)
      },
  )
}

/** Two lines of the same y axis, with a small legend under the title. */
@Composable
fun TrendDoubleLineChart(
    days: List<LocalDate>,
    first: List<TrendPoint?>,
    second: List<TrendPoint?>,
    firstLabel: String,
    secondLabel: String,
    min: Double,
    max: Double,
    yFormat: (Double) -> String,
    description: String,
    tooltip: @Composable (TrendNight) -> String,
    onOpen: (TrendNight) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
  var selected by remember(days, first, second) { mutableStateOf<Int?>(null) }
  val firstColor = MaterialTheme.taiga.chartHr
  val secondColor = MaterialTheme.colorScheme.primary
  val axis = remember(min, max) { trendAxis(min, max, yFormat) }
  Column(modifier) {
    TrendLegend(
        first = firstLabel,
        firstColor = firstColor,
        second = secondLabel,
        secondColor = secondColor,
    )
    Spacer(Modifier.height(6.dp))
    TrendFrame(
        days = days,
        selected = selected,
        yTicks = axis.ticks,
        description = description,
        large = large,
        modifier =
            if (large) Modifier.weight(1f).fillMaxWidth()
            else Modifier.fillMaxWidth().height(176.dp),
        onTap = { index ->
          selected =
              index.takeIf { first.getOrNull(it) != null || second.getOrNull(it) != null }
        },
        drawMarks = { plot, _ ->
          drawTrendSeries(plot, second, secondColor, large, axis)
          drawTrendSeries(plot, first, firstColor, large, axis)
        },
        overlay = {
          val nights = first.zip(second) { a, b -> a ?: b }.map { it?.night }
          nightTooltip(
              selected,
              days.size,
              selected?.let { nights.getOrNull(it) },
              tooltip,
              onOpen,
              large,
          )
        },
    )
  }
}

/** One bar per night, from the bottom of the axis, with an optional dotted target line. */
@Composable
fun TrendBarChart(
    days: List<LocalDate>,
    points: List<TrendPoint?>,
    axis: TrendAxis,
    target: Double?,
    targetLabel: String?,
    description: String,
    tooltip: @Composable (TrendNight) -> String,
    onOpen: (TrendNight) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
  var selected by remember(days, points) { mutableStateOf<Int?>(null) }
  val color = MaterialTheme.colorScheme.primary
  TrendFrame(
      days = days,
      selected = selected,
      yTicks = axis.ticks,
      description = description,
      large = large,
      modifier = if (large) modifier.fillMaxSize() else modifier.fillMaxWidth().height(176.dp),
      onTap = { index -> selected = index.takeIf { points.getOrNull(it) != null } },
      drawMarks = { plot, paint ->
        val width = (plot.width / days.size.toFloat()) * if (large) 0.5f else 0.55f
        val baseline = plot.y(1f)
        for ((index, point) in points.withIndex()) {
          if (point == null) continue
          val center = plot.column(index, days.size)
          val top = plot.y(axis.fraction(point.value))
          drawRoundRect(
              color = color.copy(alpha = if (point.poor) 0.4f else 1f),
              topLeft = Offset(center - width / 2f, min(top, baseline)),
              size = Size(width, abs(top - baseline).coerceAtLeast(1f)),
              cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
          )
        }
        if (target != null && target >= axis.min && target <= axis.max) {
          val y = plot.y(axis.fraction(target))
          drawLine(
              color = color,
              start = Offset(plot.left, y),
              end = Offset(plot.right, y),
              strokeWidth = 1.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
          )
          if (targetLabel != null) {
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                targetLabel,
                plot.right - 2.dp.toPx(),
                y - 3.dp.toPx(),
                paint,
            )
          }
        }
      },
      overlay = {
        val night = selected?.let { points.getOrNull(it)?.night }
        nightTooltip(selected, days.size, night, tooltip, onOpen, large)
      },
  )
}

/**
 * The sleep schedule: a light bar from recording start to end, a darker one from sleep onset to
 * final awakening, and dotted usual times when they are set. The clock axis crosses midnight.
 */
@Composable
fun TrendScheduleChart(
    days: List<LocalDate>,
    nights: List<TrendNight?>,
    usualBedtimeMinutes: Int?,
    usualWakeMinutes: Int?,
    description: String,
    tooltip: @Composable (TrendNight) -> String,
    onOpen: (TrendNight) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
  var selected by remember(days, nights) { mutableStateOf<Int?>(null) }
  val nightMinutes = mutableListOf<Int>()
  for (night in nights) {
    if (night == null) continue
    nightMinutes += nightMinuteOfDay(night.startMinuteOfDay)
    nightMinutes += nightMinuteOfDay(night.endMinuteOfDay)
    night.onsetMinuteOfDay?.let { nightMinutes += nightMinuteOfDay(it) }
    night.finalAwakeningMinuteOfDay?.let { nightMinutes += nightMinuteOfDay(it) }
  }
  usualBedtimeMinutes?.let { nightMinutes += nightMinuteOfDay(it) }
  usualWakeMinutes?.let { nightMinutes += nightMinuteOfDay(it) }
  if (nightMinutes.isEmpty()) return
  val min = nightMinutes.min() - 30
  val max = nightMinutes.max() + 30
  val span = (max - min).coerceAtLeast(60)
  val ticks = remember(min, span) { scheduleTicks(min, max) }
  val light = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
  val dark = MaterialTheme.colorScheme.primary
  val usualColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
  val dash = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) }

  fun fractionOf(nightMinute: Int): Float =
      ((nightMinuteOfDay(nightMinute) - min).toFloat() / span).coerceIn(0f, 1f)

  TrendFrame(
      days = days,
      selected = selected,
      yTicks = ticks,
      description = description,
      large = large,
      modifier = if (large) modifier.fillMaxSize() else modifier.fillMaxWidth().height(200.dp),
      onTap = { index -> selected = index.takeIf { nights.getOrNull(it) != null } },
      drawMarks = { plot, paint ->
        val width = (plot.width / days.size.toFloat()) * if (large) 0.5f else 0.55f
        for ((index, night) in nights.withIndex()) {
          if (night == null) continue
          val center = plot.column(index, days.size)
          val alpha = if (night.poorData) 0.4f else 1f
          val start = plot.y(fractionOf(night.startMinuteOfDay))
          val end = plot.y(fractionOf(night.endMinuteOfDay))
          drawRoundRect(
              color = light.copy(alpha = light.alpha * alpha),
              topLeft = Offset(center - width / 2f, min(start, end)),
              size = Size(width, abs(end - start).coerceAtLeast(2f)),
              cornerRadius = CornerRadius(width / 2f, width / 2f),
          )
          val onset = night.onsetMinuteOfDay
          val finalAwakening = night.finalAwakeningMinuteOfDay
          if (onset != null && finalAwakening != null) {
            val onsetMinute = nightMinuteOfDay(onset)
            val awakeningMinute = nightMinuteOfDay(finalAwakening)
            if (awakeningMinute > onsetMinute) {
              val innerWidth = width * 0.5f
              val top = plot.y(fractionOf(onset))
              val bottom = plot.y(fractionOf(finalAwakening))
              drawRoundRect(
                  color = dark.copy(alpha = alpha),
                  topLeft = Offset(center - innerWidth / 2f, top),
                  size = Size(innerWidth, (bottom - top).coerceAtLeast(2f)),
                  cornerRadius = CornerRadius(innerWidth / 2f, innerWidth / 2f),
              )
            }
          }
        }
        paint.textAlign = Paint.Align.RIGHT
        for ((minute, label) in
            listOfNotNull(
                usualBedtimeMinutes?.let { it to trendClock(it) },
                usualWakeMinutes?.let { it to trendClock(it) },
            )) {
          val y = plot.y(fractionOf(minute))
          drawLine(usualColor, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx(), pathEffect = dash)
          drawContext.canvas.nativeCanvas.drawText(
              label,
              plot.right - 2.dp.toPx(),
              y - 3.dp.toPx(),
              paint,
          )
        }
      },
      overlay = {
        val night = selected?.let { nights.getOrNull(it) }
        nightTooltip(selected, days.size, night, tooltip, onOpen, large)
      },
  )
}

/** A small legend for a chart with more than one line. */
@Composable
fun TrendLegend(first: String, firstColor: Color, second: String, secondColor: Color) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(firstColor, CircleShape))
    Spacer(Modifier.width(5.dp))
    Text(first, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.width(14.dp))
    Box(Modifier.size(8.dp).background(secondColor, CircleShape))
    Spacer(Modifier.width(5.dp))
    Text(second, style = MaterialTheme.typography.labelSmall)
  }
}

/** The tooltip of the selected night, which opens that night when tapped. */
@Composable
private fun BoxScope.nightTooltip(
    selected: Int?,
    count: Int,
    night: TrendNight?,
    tooltip: @Composable (TrendNight) -> String,
    onOpen: (TrendNight) -> Unit,
    large: Boolean,
) {
  if (selected == null || night == null) return
  ChartTooltip(
      time = trendDate(night.date),
      value = tooltip(night),
      fraction = markFraction(selected, count),
      labelWidth = if (large) LargeLeftLabelWidth else LeftLabelWidth,
      onClick = { onOpen(night) },
  )
}

private fun DrawScope.drawTrendSeries(
    plot: TrendPlot,
    points: List<TrendPoint?>,
    color: Color,
    large: Boolean,
    axis: TrendAxis,
) {
  val radius = (if (large) 3.5.dp else 2.5.dp).toPx()
  for (index in 1 until points.size) {
    val from = points[index - 1] ?: continue
    val to = points[index] ?: continue
    val alpha = if (from.poor || to.poor) 0.4f else 1f
    drawLine(
        color = color.copy(alpha = alpha),
        start =
            Offset(
                plot.column(index - 1, points.size),
                plot.y(axis.fraction(from.value)),
            ),
        end =
            Offset(
                plot.column(index, points.size),
                plot.y(axis.fraction(to.value)),
            ),
        strokeWidth = (if (large) 2.5.dp else 2.dp).toPx(),
        cap = StrokeCap.Round,
    )
  }
  for ((index, point) in points.withIndex()) {
    if (point == null) continue
    drawCircle(
        color = color.copy(alpha = if (point.poor) 0.4f else 1f),
        radius = radius,
        center = Offset(plot.column(index, points.size), plot.y(axis.fraction(point.value))),
    )
  }
}

private fun dayLabels(days: List<LocalDate>, maxLabels: Int = 3): Map<Int, String> {
  if (days.isEmpty()) return emptyMap()
  val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
  val step = ceil(days.size / maxLabels.toDouble()).toInt().coerceAtLeast(1)
  val labels = linkedMapOf<Int, String>()
  var index = days.lastIndex
  while (index >= 0) {
    labels[index] = days[index].format(formatter)
    index -= step
  }
  return labels.toSortedMap()
}

private fun scheduleTicks(min: Int, max: Int): List<Pair<Float, String>> {
  val span = (max - min).coerceAtLeast(1)
  val step = if (span <= 240) 60 else if (span <= 480) 120 else 180
  val ticks = mutableListOf<Pair<Float, String>>()
  var minute = ceil(min.toDouble() / step).toInt() * step
  while (minute <= max) {
    ticks +=
        ((minute - min).toFloat() / span) to trendClock(clockMinuteOfNightMinute(minute))
    minute += step
  }
  return ticks
}

/** The compact integer a score or a percent tick shows. */
fun trendWhole(value: Double): String = value.roundToInt().toString()

/** A whole number, or a dash when the night has no value for it. */
fun trendWholeOrDash(value: Double?): String = value?.let { it.roundToInt().toString() } ?: "—"
