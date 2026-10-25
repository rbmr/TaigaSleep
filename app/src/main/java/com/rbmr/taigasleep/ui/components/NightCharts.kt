package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.analysis.applicableStages
import com.rbmr.taigasleep.analysis.formatSeconds
import com.rbmr.taigasleep.analysis.stageLabelRes
import com.rbmr.taigasleep.analysis.stageSeconds
import com.rbmr.taigasleep.remote.MetricSupportDto
import com.rbmr.taigasleep.remote.StageSegmentDto
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
      "nrem" -> MaterialTheme.taiga.stageLight.copy(alpha = 0.8f)
      "sleep" -> MaterialTheme.taiga.stageLight.copy(alpha = 0.65f)
      "unknown" -> MaterialTheme.colorScheme.outlineVariant
      else -> MaterialTheme.taiga.stageLight
    }

@Composable
fun QualityBadge(score: Double?, modifier: Modifier = Modifier) {
  val band = qualityBand(score)
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(50),
      color = qualityContainer(band),
      contentColor = qualityColor(band),
  ) {
    Text(
        text =
            if (score == null) stringResource(R.string.history_no_quality)
            else stringResource(R.string.history_quality_badge, score.roundToInt()),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun QualityRing(
    score: Double?,
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
          text = score?.roundToInt()?.toString() ?: "—",
          style = MaterialTheme.typography.headlineSmall,
          color = ink,
      )
      Text(
          text = stringResource(R.string.night_quality),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
fun SleepStagesChart(
    segments: List<StageSegmentDto>,
    epochSeconds: Int,
    startUnixMs: Long,
    modifier: Modifier = Modifier,
    chartHeight: Int = 176,
    support: Map<String, MetricSupportDto>? = null,
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
  val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
  val colors = mutableMapOf<String, Color>()
  for (segment in segments) {
    if (!colors.containsKey(segment.stage)) colors[segment.stage] = stageColor(segment.stage)
  }
  Column(modifier) {
    Row(Modifier.fillMaxWidth().height(chartHeight.dp)) {
      Column(Modifier.width(58.dp).fillMaxHeight()) {
        lanes.forEach { lane ->
          Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Text(
                text = stringResource(stageLabelRes(lane)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
          }
        }
      }
      Spacer(Modifier.width(6.dp))
      Canvas(
          Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }
      ) {
        val laneHeight = size.height / lanes.size
        if (totalSeconds > 0) {
          var hour = 3600L
          while (hour < totalSeconds) {
            val x = (hour.toDouble() / totalSeconds.toDouble()).toFloat() * size.width
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            hour += 3600L
          }
        }
        var cursor = 0
        for (segment in segments) {
          if (segment.count <= 0) continue
          val lane = lanes.indexOf(segment.stage)
          if (lane < 0) {
            cursor += segment.count
            continue
          }
          val x0 = (cursor.toFloat() / total.toFloat()) * size.width
          val x1 = ((cursor + segment.count).toFloat() / total.toFloat()) * size.width
          val top = lane * laneHeight + laneHeight * 0.17f
          val height = laneHeight * 0.66f
          drawRoundRect(
              color = colors[segment.stage] ?: grid,
              topLeft = Offset(x0, top),
              size = Size((x1 - x0).coerceAtLeast(1f), height),
              cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
          )
          cursor += segment.count
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(start = 64.dp, top = 6.dp)) {
      Text(
          text = clock(startUnixMs),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.weight(1f))
      Text(
          text = clock(endUnixMs),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
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
        "time_in_${it}_s" to com.rbmr.taigasleep.remote.MetricSupportDto("supported")
      } +
          listOf("nrem", "sleep").associate {
            "time_in_${it}_s" to
                com.rbmr.taigasleep.remote.MetricSupportDto(
                    "unsupported_algorithm",
                    "algorithm_does_not_distinguish_$it",
                )
          }
  PreviewTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      QualityBadge(82.0)
      QualityRing(82.0, size = 72)
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
  }
}
