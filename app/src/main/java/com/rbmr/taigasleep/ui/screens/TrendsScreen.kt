package com.rbmr.taigasleep.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.TrendNight
import com.rbmr.taigasleep.insights.TrendRanges
import com.rbmr.taigasleep.insights.Trends
import com.rbmr.taigasleep.insights.formatSeconds
import com.rbmr.taigasleep.ui.components.ChartExpandButton
import com.rbmr.taigasleep.ui.components.ChartFullscreenDialog
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TrendBarChart
import com.rbmr.taigasleep.ui.components.TrendDoubleLineChart
import com.rbmr.taigasleep.ui.components.TrendLineChart
import com.rbmr.taigasleep.ui.components.TrendPoint
import com.rbmr.taigasleep.ui.components.TrendScheduleChart
import com.rbmr.taigasleep.ui.components.trendClock
import com.rbmr.taigasleep.ui.components.trendHeartRateAxis
import com.rbmr.taigasleep.ui.components.trendHoursAxis
import com.rbmr.taigasleep.ui.components.trendMillisecondsAxis
import com.rbmr.taigasleep.ui.components.trendPercentAxis
import com.rbmr.taigasleep.ui.components.trendWhole
import com.rbmr.taigasleep.ui.components.trendWholeOrDash
import com.rbmr.taigasleep.ui.history.TrendsUiState
import com.rbmr.taigasleep.ui.history.TrendsViewModel
import kotlin.math.roundToInt

/**
 * The Trends side of History: a range switch and one card per chart. It draws only what its state
 * holder computed from the recordings on this phone, so it needs no network.
 */
@Composable
fun TrendsScreen(
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
) {
  val context = LocalContext.current
  val factory = remember { TrendsViewModel.Factory(context) }
  val viewModel: TrendsViewModel = viewModel(factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()

  TrendsContent(
      state = state,
      onRangeChange = viewModel::setRange,
      onOpenRecording = onOpenRecording,
  )
}

@Composable
fun TrendsContent(
    state: TrendsUiState,
    onRangeChange: (Int) -> Unit,
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
) {
  val data = state.data
  val open: (TrendNight) -> Unit = { night ->
    onOpenRecording(night.localId, night.remoteId, night.analysisId)
  }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    RangeSwitch(rangeDays = state.rangeDays, onRangeChange = onRangeChange)
    ScheduleCard(data, onOpen = open)
    TotalSleepCard(data, onOpen = open)
    ScoreCard(data, onOpen = open)
    EfficiencyCard(data, onOpen = open)
    HeartRateCard(data, onOpen = open)
    HrvCard(data, onOpen = open)
  }
}

@Composable
private fun RangeSwitch(rangeDays: Int, onRangeChange: (Int) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    for (days in TrendRanges) {
      FilterChip(
          selected = days == rangeDays,
          onClick = { onRangeChange(days) },
          label = { Text(stringResource(R.string.trends_range_days, days)) },
      )
    }
  }
}

/**
 * One chart card. When the range has fewer than two nights to average, the chart is replaced by one
 * sentence; the expand action is only there when there is a chart to enlarge.
 */
@Composable
private fun TrendCard(
    title: String,
    description: String,
    enough: Boolean,
    chart: @Composable (large: Boolean, modifier: Modifier) -> Unit,
    summary: @Composable ColumnScope.() -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  TaigaCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
      )
      if (enough) {
        ChartExpandButton(onClick = { expanded = true })
      }
    }
    Spacer(Modifier.height(8.dp))
    if (enough) {
      chart(false, Modifier)
      Spacer(Modifier.height(10.dp))
      summary()
    } else {
      Text(
          text = stringResource(R.string.trends_more_nights),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (expanded && enough) {
    ChartFullscreenDialog(title = title, onDismiss = { expanded = false }) { modifier ->
      chart(true, modifier)
    }
  }
}

@Composable
private fun ScheduleCard(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_schedule_title)
  val bedtime = data.schedule.bedtime
  val wake = data.schedule.wake
  val nights = data.schedule.nights
  val enough = data.schedule.enough && bedtime != null && wake != null
  val description =
      if (bedtime != null && wake != null) {
        stringResource(
            R.string.trends_description_schedule,
            trendClock(bedtime.meanMinuteOfDay),
            trendClock(wake.meanMinuteOfDay),
            nights,
        )
      } else {
        stringResource(R.string.trends_description_more, title)
      }
  TrendCard(
      title = title,
      description = description,
      enough = enough,
      chart = { large, modifier ->
        TrendScheduleChart(
            days = data.days,
            nights = data.nights,
            usualBedtimeMinutes = data.usualBedtimeMinutes,
            usualWakeMinutes = data.usualWakeMinutes,
            description = description,
            tooltip = { night ->
              stringResource(
                  R.string.trends_schedule_tooltip,
                  trendClock(night.startMinuteOfDay),
                  trendClock(night.endMinuteOfDay),
              )
            },
            onOpen = onOpen,
            modifier = modifier,
            large = large,
        )
      },
      summary = {
        if (bedtime != null && wake != null) {
          Text(
              text =
                  stringResource(
                      R.string.trends_schedule_summary,
                      trendClock(bedtime.meanMinuteOfDay),
                      trendClock(wake.meanMinuteOfDay),
                  ),
              style = MaterialTheme.typography.bodyMedium,
          )
          Text(
              text =
                  stringResource(
                      R.string.trends_schedule_spread,
                      bedtime.spreadMinutes,
                      wake.spreadMinutes,
                      nights,
                  ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
  )
}

@Composable
private fun TotalSleepCard(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_total_sleep_title)
  val average = data.totalSleep
  val points = data.points { it.totalSleepSeconds }
  val intendedHours = data.intendedSleepMinutes?.div(60.0)
  val (min, max) = trendHoursAxis(points, intendedHours)
  val value = average.value ?: 0.0
  val description =
      stringResource(
          R.string.trends_description_time,
          title,
          formatSeconds(value),
          average.nights,
      )
  TrendCard(
      title = title,
      description = description,
      enough = average.enough,
      chart = { large, modifier ->
        TrendBarChart(
            days = data.days,
            points = points,
            min = min,
            max = max,
            yFormat = { "${it.roundToInt()} h" },
            target = intendedHours,
            targetLabel = data.intendedSleepMinutes?.let { formatSeconds(it * 60.0) },
            description = description,
            tooltip = { night -> formatSeconds(night.totalSleepSeconds ?: 0.0) },
            onOpen = onOpen,
            modifier = modifier,
            large = large,
        )
      },
      summary = {
        Text(
            text =
                stringResource(
                    R.string.trends_summary_time,
                    formatSeconds(value),
                    average.nights,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )
}

@Composable
private fun ScoreCard(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_score_title)
  val average = data.score
  val points = data.points { it.score }
  val description =
      stringResource(
          R.string.trends_description_whole,
          title,
          (average.value ?: 0.0).roundToInt(),
          average.nights,
      )
  TrendCard(
      title = title,
      description = description,
      enough = average.enough,
      chart = { large, modifier ->
        TrendLineChart(
            days = data.days,
            points = points,
            min = 0.0,
            max = 100.0,
            yFormat = ::trendWhole,
            description = description,
            tooltip = { night -> trendWholeOrDash(night.score) },
            onOpen = onOpen,
            modifier = modifier,
            large = large,
        )
      },
      summary = {
        Text(
            text =
                stringResource(
                    R.string.trends_summary_whole,
                    (average.value ?: 0.0).roundToInt(),
                    average.nights,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )
}

@Composable
private fun EfficiencyCard(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_efficiency_title)
  val average = data.efficiency
  val points = data.points { it.efficiency }
  val (min, max) = trendPercentAxis(points)
  val description =
      stringResource(
          R.string.trends_description_percent,
          title,
          (average.value ?: 0.0).roundToInt(),
          average.nights,
      )
  TrendCard(
      title = title,
      description = description,
      enough = average.enough,
      chart = { large, modifier ->
        TrendLineChart(
            days = data.days,
            points = points,
            min = min,
            max = max,
            yFormat = { "${it.roundToInt()}%" },
            description = description,
            tooltip = { night ->
              night.efficiency?.let { "${it.roundToInt()}%" } ?: "—"
            },
            onOpen = onOpen,
            modifier = modifier,
            large = large,
        )
      },
      summary = {
        Text(
            text =
                stringResource(
                    R.string.trends_summary_percent,
                    (average.value ?: 0.0).roundToInt(),
                    average.nights,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )
}

@Composable
private fun HeartRateCard(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_heart_rate_title)
  val minimum = data.heartRateMin
  val mean = data.heartRateMean
  val minPoints = data.points { it.heartRateMinBpm }
  val meanPoints = data.points { it.heartRateMeanBpm }
  val (min, max) = trendHeartRateAxis(minPoints + meanPoints)
  val nights = minOf(minimum.nights, mean.nights)
  val description =
      stringResource(
          R.string.trends_description_heart_rate,
          title,
          (minimum.value ?: 0.0).roundToInt(),
          (mean.value ?: 0.0).roundToInt(),
          nights,
      )
  TrendCard(
      title = title,
      description = description,
      enough = minimum.enough && mean.enough,
      chart = { large, modifier ->
        TrendDoubleLineChart(
            days = data.days,
            first = minPoints,
            second = meanPoints,
            firstLabel = stringResource(R.string.trends_legend_min),
            secondLabel = stringResource(R.string.trends_legend_mean),
            min = min,
            max = max,
            yFormat = { "${it.roundToInt()} bpm" },
            description = description,
            tooltip = { night ->
              stringResource(
                  R.string.trends_tooltip_heart_rate,
                  trendWholeOrDash(night.heartRateMinBpm),
                  trendWholeOrDash(night.heartRateMeanBpm),
              )
            },
            onOpen = onOpen,
            modifier = modifier,
            large = large,
          )
      },
      summary = {
        Text(
            text =
                stringResource(
                    R.string.trends_summary_heart_rate,
                    (minimum.value ?: 0.0).roundToInt(),
                    (mean.value ?: 0.0).roundToInt(),
                    nights,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )
}

@Composable
private fun HrvCard(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_hrv_title)
  val average = data.hrv
  val points = data.points { it.hrvRmssdMs }
  val (min, max) = trendMillisecondsAxis(points)
  val description =
      stringResource(
          R.string.trends_description_hrv,
          title,
          (average.value ?: 0.0).roundToInt(),
          average.nights,
      )
  TrendCard(
      title = title,
      description = description,
      enough = average.enough,
      chart = { large, modifier ->
        TrendLineChart(
            days = data.days,
            points = points,
            min = min,
            max = max,
            yFormat = { "${it.roundToInt()} ms" },
            description = description,
            tooltip = { night ->
              stringResource(
                  R.string.trends_tooltip_hrv,
                  (night.hrvRmssdMs ?: 0.0).roundToInt(),
              )
            },
            onOpen = onOpen,
            modifier = modifier,
            large = large,
        )
      },
      summary = {
        Text(
            text =
                stringResource(
                    R.string.trends_summary_hrv,
                    (average.value ?: 0.0).roundToInt(),
                    average.nights,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )
}

/** One point per calendar day; a day without a night or without the value stays empty. */
private fun Trends.points(value: (TrendNight) -> Double?): List<TrendPoint?> =
    nights.map { night ->
      if (night == null) null
      else value(night)?.let { TrendPoint(it, night.poorData, night) }
    }
