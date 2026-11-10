package com.rbmr.taigasleep.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
 * The unit labels and gridlines a chart asks for, as a fraction from the top and its text. The two
 * ends of the axis are labelled as well, so a range that does not fall on a round step still reads.
 */
fun trendTicks(min: Double, max: Double, format: (Double) -> String): List<Pair<Float, String>> {
  if (!(max > min)) return listOf(0f to format(min))
  val step = niceStep((max - min) / 3.0)
  val values = mutableListOf<Double>()
  var value = ceil(min / step) * step
  while (value <= max + step * 0.001) {
    values += value
    value += step
  }
  if (values.none { abs(it - max) <= step * 0.001 }) values += max
  if (values.none { abs(it - min) <= step * 0.001 }) values += min
  return values.distinct().sortedDescending().map { tick ->
    ((max - tick) / (max - min)).toFloat() to format(tick)
  }
}

private fun niceStep(raw: Double): Double {
  if (!(raw > 0)) return 1.0
  val base = 10.0.pow(floor(log10(raw)))
  val normalized = raw / base
  val nice =
      when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
      }
  return nice * base
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
  val leftLabelWidth = if (large) LargeLeftLabelWidth else LeftLabelWidth
  val leftPx = with(density) { leftLabelWidth.toPx() }
  val topPx = with(density) { (if (large) 10.dp else 8.dp).toPx() }
  val bottomPx = with(density) { (if (large) 28.dp else 22.dp).toPx() }
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
  val ticks = remember(min, max) { trendTicks(min, max, yFormat) }
  TrendFrame(
      days = days,
      selected = selected,
      yTicks = ticks,
      description = description,
      large = large,
      modifier = if (large) modifier.fillMaxSize() else modifier.fillMaxWidth().height(176.dp),
      onTap = { index -> selected = index.takeIf { points.getOrNull(it) != null } },
      drawMarks = { plot, _ ->
        drawTrendSeries(plot, points, color, large, min, max)
        selected?.let { index ->
          points.getOrNull(index)?.let { point ->
            drawCircle(
                color,
                radius = (if (large) 5.dp else 4.dp).toPx(),
                center = Offset(plot.column(index, days.size), plot.y(fraction(point.value, min, max))),
            )
          }
        }
      },
      overlay = {
        trendTooltip(selected, days, points.map { it?.night }, tooltip, onOpen, large)
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
  val ticks = remember(min, max) { trendTicks(min, max, yFormat) }
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
        yTicks = ticks,
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
          drawTrendSeries(plot, second, secondColor, large, min, max)
          drawTrendSeries(plot, first, firstColor, large, min, max)
        },
        overlay = {
          trendTooltip(
              selected,
              days,
              first.zip(second) { a, b -> a ?: b }.map { it?.night },
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
    min: Double,
    max: Double,
    yFormat: (Double) -> String,
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
  val ticks = remember(min, max) { trendTicks(min, max, yFormat) }
  TrendFrame(
      days = days,
      selected = selected,
      yTicks = ticks,
      description = description,
      large = large,
      modifier = if (large) modifier.fillMaxSize() else modifier.fillMaxWidth().height(176.dp),
      onTap = { index -> selected = index.takeIf { points.getOrNull(it) != null } },
      drawMarks = { plot, paint ->
        val width = (plot.width / days.size.toFloat()) * if (large) 0.5f else 0.55f
        val baseline = plot.y(fraction(min, min, max))
        for ((index, point) in points.withIndex()) {
          if (point == null) continue
          val center = plot.column(index, days.size)
          val top = plot.y(fraction(point.value, min, max))
          drawRoundRect(
              color = color.copy(alpha = if (point.poor) 0.4f else 1f),
              topLeft = Offset(center - width / 2f, min(top, baseline)),
              size = Size(width, abs(top - baseline).coerceAtLeast(1f)),
              cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
          )
        }
        if (target != null && target in min..max) {
          val y = plot.y(fraction(target, min, max))
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
        trendTooltip(selected, days, points.map { it?.night }, tooltip, onOpen, large)
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
        trendTooltip(selected, days, nights, tooltip, onOpen, large)
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

@Composable
private fun BoxScope.trendTooltip(
    selected: Int?,
    days: List<LocalDate>,
    nights: List<TrendNight?>,
    tooltip: @Composable (TrendNight) -> String,
    onOpen: (TrendNight) -> Unit,
    large: Boolean,
) {
  val night = selected?.let { nights.getOrNull(it) } ?: return
  val align = if (selected < days.size / 2) Alignment.TopEnd else Alignment.TopStart
  ChartTooltip(
      time = trendDate(night.date),
      value = tooltip(night),
      modifier =
          Modifier.align(align)
              .padding(
                  top = 4.dp,
                  start = (if (large) LargeLeftLabelWidth else LeftLabelWidth) + 8.dp,
                  end = 8.dp,
              )
              .clickable { onOpen(night) },
  )
}

private fun DrawScope.drawTrendSeries(
    plot: TrendPlot,
    points: List<TrendPoint?>,
    color: Color,
    large: Boolean,
    min: Double,
    max: Double,
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
                plot.y(fraction(from.value, min, max)),
            ),
        end =
            Offset(
                plot.column(index, points.size),
                plot.y(fraction(to.value, min, max)),
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
        center = Offset(plot.column(index, points.size), plot.y(fraction(point.value, min, max))),
    )
  }
}

private fun fraction(value: Double, min: Double, max: Double): Float =
    if (max <= min) 0.5f else ((max - value) / (max - min)).toFloat()

private fun dayLabels(days: List<LocalDate>, maxLabels: Int = 4): Map<Int, String> {
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

/** The hours axis of the total sleep chart, at least one hour and a whole ceiling. */
fun trendHoursAxis(points: List<TrendPoint?>, targetHours: Double?): Pair<Double, Double> {
  val highest =
      maxOf(
          points.filterNotNull().maxOfOrNull { it.value } ?: 0.0,
          targetHours ?: 0.0,
      )
  val max = ceil(highest).coerceAtLeast(1.0)
  return 0.0 to max
}

/** The percent axis of the efficiency chart, kept inside 0 and 100. */
fun trendPercentAxis(points: List<TrendPoint?>): Pair<Double, Double> {
  val values = points.filterNotNull().map { it.value }
  if (values.isEmpty()) return 0.0 to 100.0
  var min = (floor((values.min() - 5) / 5) * 5).coerceAtLeast(0.0)
  var max = (ceil((values.max() + 5) / 5) * 5).coerceAtMost(100.0)
  if (max - min < 10) {
    min = (min - 5).coerceAtLeast(0.0)
    max = (max + 5).coerceAtMost(100.0)
  }
  return min to max
}

/** The bpm axis of a heart-rate chart, rounded to tens and never empty. */
fun trendHeartRateAxis(points: List<TrendPoint?>): Pair<Double, Double> {
  val values = points.filterNotNull().map { it.value }
  if (values.isEmpty()) return 0.0 to 100.0
  val min = (floor(values.min() / 10) * 10 - 5).coerceAtLeast(0.0)
  var max = ceil(values.max() / 10) * 10 + 5
  if (max - min < 20) max = min + 20
  return min to max
}

/** The ms axis of the HRV chart: from zero to a whole rounded top. */
fun trendMillisecondsAxis(points: List<TrendPoint?>): Pair<Double, Double> {
  val highest = points.filterNotNull().maxOfOrNull { it.value } ?: 0.0
  return 0.0 to ceil(highest / 20).coerceAtLeast(1.0) * 20
}

/** The compact integer a score or a percent tick shows. */
fun trendWhole(value: Double): String = value.roundToInt().toString()

/** A whole number, or a dash when the night has no value for it. */
fun trendWholeOrDash(value: Double?): String = value?.let { it.roundToInt().toString() } ?: "—"
