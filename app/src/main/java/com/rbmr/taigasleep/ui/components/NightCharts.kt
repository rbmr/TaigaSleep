package com.rbmr.taigasleep.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import com.rbmr.taigasleep.backend.MetricSupportDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.insights.applicableStages
import com.rbmr.taigasleep.insights.formatSeconds
import com.rbmr.taigasleep.insights.stageLabelRes
import com.rbmr.taigasleep.insights.stageSeconds
import com.rbmr.taigasleep.ui.theme.taiga
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
private fun qualityContainer(band: QualityBand): Color =
    when (band) {
      QualityBand.EXCELLENT,
      QualityBand.GOOD -> MaterialTheme.taiga.okContainer
      QualityBand.FAIR -> MaterialTheme.taiga.warnContainer
      QualityBand.POOR -> MaterialTheme.taiga.badContainer
      QualityBand.NONE -> MaterialTheme.colorScheme.surfaceVariant
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
fun ScoreBadge(
    score: Double?,
    label: String,
    emptyLabel: String,
    modifier: Modifier = Modifier,
) {
  val band = qualityBand(score)
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(50),
      color = qualityContainer(band),
      contentColor = qualityColor(band),
  ) {
    Text(
        text =
            if (score == null) emptyLabel
            else stringResource(R.string.history_score_badge, label, score.roundToInt()),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
  }
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
          text = score?.roundToInt()?.toString() ?: "—",
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

@Composable
fun StageBar(
    segments: List<StageSegmentDto>,
    modifier: Modifier = Modifier,
    description: String = stringResource(R.string.history_stage_bar_description),
) {
  val visible = segments.filter { it.count > 0 }
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .height(10.dp)
              .clip(RoundedCornerShape(50))
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .semantics { contentDescription = description },
  ) {
    if (visible.isEmpty()) {
      Spacer(Modifier.fillMaxWidth().fillMaxHeight())
    } else {
      visible.forEach { segment ->
        Box(
            Modifier.weight(segment.count.toFloat())
                .fillMaxHeight()
                .background(stageColor(segment.stage))
        )
      }
    }
  }
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

@Composable
fun ChartExpandButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(
        imageVector = Icons.Outlined.Fullscreen,
        contentDescription = stringResource(R.string.night_chart_fullscreen),
    )
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
  val restoreOrientation =
      if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      }
  DisposableEffect(activity) {
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    onDispose { activity?.requestedOrientation = restoreOrientation }
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
          IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.night_chart_close_fullscreen),
            )
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

@Composable
private fun ChartTooltip(time: String, value: String, modifier: Modifier = Modifier) {
  Surface(
      modifier = modifier,
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

@Composable
private fun TimeChart(
    startUnixMs: Long,
    totalSeconds: Long,
    pointCount: Int,
    yLabels: List<Pair<Float, String>>,
    horizontalLines: List<Float>,
    leftLabelWidth: Dp,
    description: String,
    valueAt: (Int) -> String,
    large: Boolean,
    modifier: Modifier = Modifier,
    drawPlot: DrawScope.(PlotBounds, Int?) -> Unit,
) {
  val density = LocalDensity.current
  val leftPx = with(density) { leftLabelWidth.toPx() }
  val topPx = with(density) { (if (large) 2.dp else 8.dp).toPx() }
  val bottomPx = with(density) { (if (large) 26.dp else 22.dp).toPx() }
  val labelSp = if (large) 13.sp else 8.sp
  val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
  val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val selectedColor = MaterialTheme.colorScheme.primary
  val ticks = remember(startUnixMs, totalSeconds) { hourTicks(startUnixMs, totalSeconds) }
  var selected by remember { mutableStateOf<Int?>(null) }
  val selectedFraction =
      selected?.let { index ->
        if (pointCount <= 1) 0f else index.toFloat() / (pointCount - 1).toFloat()
      }

  Box(modifier) {
    Canvas(
        Modifier.fillMaxSize().semantics { contentDescription = description }.pointerInput(
            pointCount,
            leftPx,
        ) {
          awaitEachGesture {
            fun indexAt(x: Float): Int {
              val width = (size.width.toFloat() - leftPx).coerceAtLeast(1f)
              val fraction = ((x - leftPx) / width).coerceIn(0f, 1f)
              return (fraction * (pointCount - 1).coerceAtLeast(0)).roundToInt()
            }

            val down = awaitFirstDown(requireUnconsumed = false)
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
          PlotBounds(
              left = leftPx,
              top = topPx,
              width = size.width - leftPx,
              height = size.height - topPx - bottomPx,
          )
      val labelPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = with(density) { labelSp.toPx() }
          }

      for (line in horizontalLines) {
        val y = plot.y(line)
        drawLine(gridColor, Offset(plot.left, y), Offset(plot.left + plot.width, y), 1.dp.toPx())
      }
      if (ticks.isEmpty() && startUnixMs > 0) {
        labelPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(
            clock(startUnixMs),
            plot.left,
            plot.top + plot.height + labelPaint.textSize + 4.dp.toPx(),
            labelPaint,
        )
        labelPaint.textAlign = Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText(
            clock(startUnixMs + totalSeconds * 1000L),
            plot.left + plot.width,
            plot.top + plot.height + labelPaint.textSize + 4.dp.toPx(),
            labelPaint,
        )
      } else if (ticks.isNotEmpty()) {
        for (tick in ticks) {
          val x = plot.x(tick.fraction)
          drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.top + plot.height), 1.dp.toPx())
          labelPaint.textAlign =
              when {
                tick.fraction <= 0.02f -> Paint.Align.LEFT
                tick.fraction >= 0.98f -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
              }
          drawContext.canvas.nativeCanvas.drawText(
              clock(tick.timeMs),
              x,
              plot.top + plot.height + labelPaint.textSize + 4.dp.toPx(),
              labelPaint,
          )
        }
      }
      drawPlot(plot, selected)
      labelPaint.textAlign = Paint.Align.RIGHT
      for ((fraction, label) in yLabels) {
        drawContext.canvas.nativeCanvas.drawText(
            label,
            plot.left - 6.dp.toPx(),
            plot.y(fraction) + labelPaint.textSize / 3f,
            labelPaint,
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
          Offset(plot.left + plot.width, plot.top + plot.height),
          1.dp.toPx(),
      )
      if (selectedFraction != null) {
        val x = plot.x(selectedFraction)
        drawLine(
            selectedColor,
            Offset(x, plot.top),
            Offset(x, plot.top + plot.height),
            1.5.dp.toPx(),
        )
      }
    }
    val selectedIndex = selected
    val fraction = selectedFraction
    if (selectedIndex != null && fraction != null) {
      ChartTooltip(
          time = clock(epochTimeMs(startUnixMs, totalSeconds, pointCount, selectedIndex)),
          value = valueAt(selectedIndex),
          modifier =
              Modifier.align(if (fraction < 0.5f) Alignment.TopEnd else Alignment.TopStart)
                  .padding(top = 4.dp, start = leftLabelWidth + 8.dp, end = 8.dp),
      )
    }
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

@Composable
fun SleepStagesChart(
    segments: List<StageSegmentDto>,
    epochSeconds: Int,
    startUnixMs: Long,
    modifier: Modifier = Modifier,
    chartHeight: Int = 176,
    support: Map<String, MetricSupportDto>? = null,
    fullscreen: Boolean = false,
) {
  val lanes = applicableStages(segments, support).ifEmpty { listOf("unknown") }
  val total = segments.sumOf { it.count }.coerceAtLeast(1)
  val totalSeconds = total.toLong() * epochSeconds.coerceAtLeast(1)
  val endUnixMs = if (startUnixMs > 0) startUnixMs + totalSeconds * 1000L else 0L
  val description =
      stringResource(
          R.string.night_sleep_stages_description,
          clock(startUnixMs),
          clock(endUnixMs),
      )
  val colors = mutableMapOf<String, Color>()
  for (segment in segments) {
    if (!colors.containsKey(segment.stage)) colors[segment.stage] = stageColor(segment.stage)
  }
  val labels = lanes.associateWith { stringResource(stageLabelRes(it)) }
  val unknownLabel = stringResource(R.string.night_stage_unknown)
  val fallbackColor = MaterialTheme.colorScheme.outlineVariant
  val yLabels =
      lanes.mapIndexed { index, lane ->
        ((index + 0.5f) / lanes.size) to (labels[lane] ?: unknownLabel)
      }
  val horizontalLines = (1 until lanes.size).map { it.toFloat() / lanes.size }
  TimeChart(
      startUnixMs = startUnixMs,
      totalSeconds = totalSeconds,
      pointCount = total,
      yLabels = yLabels,
      horizontalLines = horizontalLines,
      leftLabelWidth = if (fullscreen) 88.dp else 48.dp,
      description = description,
      valueAt = { index -> labels[stageAtEpoch(segments, index)] ?: unknownLabel },
      large = fullscreen,
      modifier =
          if (fullscreen) modifier.fillMaxSize()
          else modifier.fillMaxWidth().height(chartHeight.dp),
  ) { plot, _ ->
    var cursor = 0
    for (segment in segments) {
      if (segment.count <= 0) continue
      val lane = lanes.indexOf(segment.stage)
      if (lane < 0) {
        cursor += segment.count
        continue
      }
      val x0 = plot.left + (cursor.toFloat() / total.toFloat()) * plot.width
      val x1 = plot.left + ((cursor + segment.count).toFloat() / total.toFloat()) * plot.width
      val laneHeight = plot.height / lanes.size
      val top = plot.top + lane * laneHeight + laneHeight * 0.17f
      drawRoundRect(
          color = colors[segment.stage] ?: fallbackColor,
          topLeft = Offset(x0, top),
          size = Size((x1 - x0).coerceAtLeast(1f), laneHeight * 0.66f),
          cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
      )
      cursor += segment.count
    }
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
  val points = hrSeriesPoints(series)
  val values = points.filterNotNull()
  if (values.isEmpty()) return
  val low = values.min()
  val high = values.max()
  val totalSeconds = series.count.toLong() * series.epoch_seconds.coerceAtLeast(1)
  val endUnixMs = if (series.start_unix_ms > 0) series.start_unix_ms + totalSeconds * 1000L else 0L
  val description =
      stringResource(
          R.string.night_heart_rate_chart_description,
          clock(series.start_unix_ms),
          clock(endUnixMs),
          low.roundToInt(),
          high.roundToInt(),
      )
  val line = MaterialTheme.taiga.chartHr
  val noValue = stringResource(R.string.night_chart_no_value)
  val yLabels =
      (0..4).map { index ->
        val fraction = index / 4f
        fraction to (high - (high - low) * fraction).roundToInt().toString()
      }
  TimeChart(
      startUnixMs = series.start_unix_ms,
      totalSeconds = totalSeconds,
      pointCount = series.count,
      yLabels = yLabels,
      horizontalLines = (0..4).map { it / 4f },
      leftLabelWidth = if (fullscreen) 64.dp else 46.dp,
      description = description,
      valueAt = { index ->
        points.getOrNull(index)?.let { "${it.roundToInt()} bpm" } ?: noValue
      },
      large = fullscreen,
      modifier =
          if (fullscreen) modifier.fillMaxSize()
          else modifier.fillMaxWidth().height(chartHeight.dp),
  ) { plot, selected ->
    val span = high - low
    fun y(value: Double): Float =
        if (span < 0.001) plot.y(0.5f)
        else plot.y(((high - value) / span).toFloat())
    fun x(index: Int): Float =
        if (series.count <= 1) plot.x(0.5f)
        else plot.x(index.toFloat() / (series.count - 1).toFloat())

    for (run in hrSeriesRuns(points)) {
      if (run.size == 1) {
        val point = run.first()
        drawCircle(line, radius = 1.5.dp.toPx(), center = Offset(x(point.index), y(point.value)))
        continue
      }
      for (position in 1 until run.size) {
        val from = run[position - 1]
        val to = run[position]
        drawLine(
            color = line,
            start = Offset(x(from.index), y(from.value)),
            end = Offset(x(to.index), y(to.value)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
      }
    }
    selected?.let { index ->
      points.getOrNull(index)?.let { value ->
        drawCircle(line, radius = 3.dp.toPx(), center = Offset(x(index), y(value)))
      }
    }
  }
}

@Composable
fun StageBreakdown(
    segments: List<StageSegmentDto>,
    epochSeconds: Int,
    support: Map<String, MetricSupportDto>?,
    modifier: Modifier = Modifier,
) {
  val durations = stageSeconds(segments, epochSeconds)
  val total = durations.values.sum().coerceAtLeast(1)
  val stages = applicableStages(segments, support)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    for (stage in stages) {
      val seconds = durations[stage] ?: 0L
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).background(stageColor(stage), CircleShape)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = stringResource(stageLabelRes(stage)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatSeconds(seconds.toDouble()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${(seconds.toDouble() / total.toDouble() * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
        )
      }
      Box(
          Modifier.fillMaxWidth().padding(start = 19.dp).height(4.dp)
              .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
      ) {
        Box(
            Modifier.fillMaxWidth((seconds.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(stageColor(stage), RoundedCornerShape(50))
        )
      }
    }
  }
}

private fun clock(ms: Long): String =
    if (ms <= 0) "—"
    else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NightChartsPreview() {
  val segments =
      listOf(
          StageSegmentDto("wake", 2),
          StageSegmentDto("light", 8),
          StageSegmentDto("deep", 6),
          StageSegmentDto("rem", 4),
          StageSegmentDto("light", 5),
          StageSegmentDto("unknown", 1),
      )
  val support =
      listOf("wake", "rem", "light", "deep").associate {
        "time_in_${it}_s" to com.rbmr.taigasleep.backend.MetricSupportDto("supported")
      } +
          listOf("nrem", "sleep").associate {
            "time_in_${it}_s" to
                com.rbmr.taigasleep.backend.MetricSupportDto(
                    "unsupported_algorithm",
                    "algorithm_does_not_distinguish_$it",
                )
          }
  PreviewTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ScoreBadge(82.0, "Sleep score", "No sleep score")
      ScoreRing(82.0, "Sleep score", size = 72)
    }
    StageBar(segments)
    SleepStagesChart(
        segments = segments,
        epochSeconds = 30,
        startUnixMs = 1_790_000_000_000,
        chartHeight = 140,
        support = support,
    )
    StageBreakdown(segments = segments, epochSeconds = 30, support = support)
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
