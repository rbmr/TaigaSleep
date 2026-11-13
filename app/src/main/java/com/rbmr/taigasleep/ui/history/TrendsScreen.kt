package com.rbmr.taigasleep.ui.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.TrendNight
import com.rbmr.taigasleep.insights.TrendRanges
import com.rbmr.taigasleep.insights.Trends
import com.rbmr.taigasleep.insights.clockMinuteOfNightMinute
import com.rbmr.taigasleep.insights.formatSeconds
import com.rbmr.taigasleep.insights.nightMinuteOfDay
import com.rbmr.taigasleep.ui.components.ChoiceChip
import com.rbmr.taigasleep.ui.components.ClockSteps
import com.rbmr.taigasleep.ui.components.HourSteps
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.TrendChart
import com.rbmr.taigasleep.ui.components.TrendChartKind
import com.rbmr.taigasleep.ui.components.TrendPoint
import com.rbmr.taigasleep.ui.components.TrendReference
import com.rbmr.taigasleep.ui.components.TrendSeries
import com.rbmr.taigasleep.ui.components.TrendSpanPoint
import com.rbmr.taigasleep.ui.components.TrendSummary
import com.rbmr.taigasleep.ui.components.WholeSteps
import com.rbmr.taigasleep.ui.components.totalSleepHours
import com.rbmr.taigasleep.ui.components.trendClock
import com.rbmr.taigasleep.ui.components.trendWhole
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt

/**
 * The Trends side of History: the range chips and two groups of charts. It draws only what its
 * state holder computed from the recordings on this phone, so it needs no network.
 */
@Composable
fun TrendsScreen(
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val factory = remember { TrendsViewModel.Factory(context) }
  val viewModel: TrendsViewModel = viewModel(factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()

  TrendsContent(
      state = state,
      onRangeChange = viewModel::setRange,
      onOpenRecording = onOpenRecording,
      modifier = modifier,
  )
}

@Composable
fun TrendsContent(
    state: TrendsUiState,
    onRangeChange: (Int) -> Unit,
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
  val space = MaterialTheme.taiga.space
  val data = state.data
  val open: (TrendNight) -> Unit = { night ->
    onOpenRecording(night.localId, night.remoteId, night.analysisId)
  }
  LazyColumn(
      modifier.fillMaxWidth(),
      contentPadding = PaddingValues(bottom = space.m),
      verticalArrangement = Arrangement.spacedBy(space.l),
  ) {
    item { RangeChips(rangeDays = state.rangeDays, onRangeChange = onRangeChange) }
    item {
      Section(title = stringResource(R.string.trends_group_sleep)) {
        Column(verticalArrangement = Arrangement.spacedBy(space.m)) {
          ScoreChart(data, onOpen = open)
          TotalSleepChart(data, onOpen = open)
          ScheduleChart(data, onOpen = open)
          EfficiencyChart(data, onOpen = open)
        }
      }
    }
    item {
      Section(title = stringResource(R.string.trends_group_heart)) {
        Column(verticalArrangement = Arrangement.spacedBy(space.m)) {
          HeartRateChart(data, onOpen = open)
          HrvChart(data, onOpen = open)
        }
      }
    }
  }
}

/** The three ranges the charts can show; one is always chosen. */
@Composable
private fun RangeChips(rangeDays: Int, onRangeChange: (Int) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.s)) {
    for (days in TrendRanges) {
      ChoiceChip(
          label = stringResource(R.string.trends_range_days, days),
          selected = days == rangeDays,
          onClick = { onRangeChange(days) },
      )
    }
  }
}

@Composable
private fun ScoreChart(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_score_title)
  val average = data.score
  val points = data.points { it.score }
  val value = (average.value ?: 0.0).roundToInt()
  TrendChart(
      title = title,
      days = data.days,
      kind = TrendChartKind.Line(listOf(TrendSeries(averageLabel(), points, averageColor()))),
      summary = listOf(TrendSummary(averageLabel(), value.toString())),
      description = stringResource(R.string.trends_description_whole, title, value, average.nights),
      onOpen = onOpen,
      emptyMessage = moreRecordings(average.enough),
      axisFormat = ::trendWhole,
      steps = WholeSteps,
  )
}

@Composable
private fun TotalSleepChart(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_total_sleep_title)
  val average = data.totalSleep
  val points = data.points { totalSleepHours(it.totalSleepSeconds) }
  val value = average.value ?: 0.0
  val intended = data.intendedSleepMinutes
  TrendChart(
      title = title,
      days = data.days,
      kind =
          TrendChartKind.Bars(
              points = points,
              average = totalSleepHours(average.value),
          ),
      summary = listOf(TrendSummary(averageLabel(), formatSeconds(value))),
      description = stringResource(R.string.trends_description_time, title, formatSeconds(value), average.nights),
      onOpen = onOpen,
      emptyMessage = moreRecordings(average.enough),
      references =
          listOfNotNull(
              intended?.let {
                TrendReference(
                    label = stringResource(R.string.trends_reference_intended),
                    value = it / 60.0,
                )
              }
          ),
      topLabel = { "$it h" },
      steps = HourSteps,
      minStep = 1.0,
  )
}

@Composable
private fun ScheduleChart(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_schedule_title)
  val bedtime = data.schedule.bedtime
  val wake = data.schedule.wake
  val enough = data.schedule.enough && bedtime != null && wake != null
  val spans =
      data.nights.map { night ->
        night?.let {
          TrendSpanPoint(
              night = it,
              start = nightMinuteOfDay(it.startMinuteOfDay).toDouble(),
              end = nightMinuteOfDay(it.endMinuteOfDay).toDouble(),
          )
        }
      }
  val summary =
      listOfNotNull(
          bedtime?.let {
            TrendSummary(stringResource(R.string.trends_summary_average_bedtime), trendClock(it.meanMinuteOfDay))
          },
          wake?.let {
            TrendSummary(stringResource(R.string.trends_summary_average_wake), trendClock(it.meanMinuteOfDay))
          },
      )
  val description =
      if (enough && bedtime != null && wake != null) {
        stringResource(
            R.string.trends_description_schedule,
            trendClock(bedtime.meanMinuteOfDay),
            trendClock(wake.meanMinuteOfDay),
            data.schedule.nights,
        )
      } else {
        stringResource(R.string.trends_description_more, title)
      }
  TrendChart(
      title = title,
      days = data.days,
      kind = TrendChartKind.FloatingBars(spans),
      summary = summary,
      description = description,
      onOpen = onOpen,
      emptyMessage = moreRecordings(enough),
      references =
          listOfNotNull(
              data.usualBedtimeMinutes?.let {
                TrendReference(
                    label = stringResource(R.string.trends_reference_usual_bedtime),
                    value = nightMinuteOfDay(it).toDouble(),
                )
              },
              data.usualWakeMinutes?.let {
                TrendReference(
                    label = stringResource(R.string.trends_reference_usual_wake),
                    value = nightMinuteOfDay(it).toDouble(),
                )
              },
          ),
      axisFormat = { trendClock(clockMinuteOfNightMinute(it.roundToInt())) },
      steps = ClockSteps,
      minStep = 60.0,
  )
}

@Composable
private fun EfficiencyChart(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_efficiency_title)
  val average = data.efficiency
  val points = data.points { it.efficiency }
  val value = (average.value ?: 0.0).roundToInt()
  TrendChart(
      title = title,
      days = data.days,
      kind = TrendChartKind.Line(listOf(TrendSeries(averageLabel(), points, averageColor()))),
      summary = listOf(TrendSummary(averageLabel(), "$value%")),
      description =
          stringResource(R.string.trends_description_percent, title, value, average.nights),
      onOpen = onOpen,
      emptyMessage = moreRecordings(average.enough),
      axisFormat = ::trendWhole,
      topLabel = { "$it%" },
      steps = WholeSteps,
  )
}

@Composable
private fun HeartRateChart(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_heart_rate_title)
  val minimum = data.heartRateMin
  val mean = data.heartRateMean
  val minimumPoints = data.points { it.heartRateMinBpm }
  val meanPoints = data.points { it.heartRateMeanBpm }
  val nights = minOf(minimum.nights, mean.nights)
  val average = (mean.value ?: 0.0).roundToInt()
  val lowest = (minimum.value ?: 0.0).roundToInt()
  val color = MaterialTheme.taiga.chartHr
  TrendChart(
      title = title,
      days = data.days,
      kind =
          TrendChartKind.Line(
              listOf(
                  TrendSeries(averageLabel(), meanPoints, color),
                  TrendSeries(lowestLabel(), minimumPoints, color, dashed = true),
              )
          ),
      summary =
          listOf(
              TrendSummary(averageLabel(), "$average bpm"),
              TrendSummary(lowestLabel(), "$lowest bpm"),
          ),
      description =
          stringResource(
              R.string.trends_description_heart_rate,
              title,
              average,
              lowest,
              nights,
          ),
      onOpen = onOpen,
      emptyMessage = moreRecordings(minimum.enough && mean.enough),
      axisFormat = ::trendWhole,
      topLabel = { "$it bpm" },
      steps = WholeSteps,
  )
}

@Composable
private fun HrvChart(data: Trends, onOpen: (TrendNight) -> Unit) {
  val title = stringResource(R.string.trends_hrv_title)
  val average = data.hrv
  val points = data.points { it.hrvRmssdMs }
  val value = (average.value ?: 0.0).roundToInt()
  TrendChart(
      title = title,
      days = data.days,
      kind = TrendChartKind.Line(listOf(TrendSeries(averageLabel(), points, averageColor()))),
      summary = listOf(TrendSummary(averageLabel(), "$value ms")),
      description = stringResource(R.string.trends_description_hrv, title, value, average.nights),
      onOpen = onOpen,
      emptyMessage = moreRecordings(average.enough),
      axisFormat = ::trendWhole,
      topLabel = { "$it ms" },
      steps = WholeSteps,
  )
}

@Composable
private fun averageLabel(): String = stringResource(R.string.trends_summary_average)

@Composable
private fun lowestLabel(): String = stringResource(R.string.trends_summary_lowest)

@Composable
private fun averageColor() = MaterialTheme.colorScheme.primary

@Composable
private fun moreRecordings(enough: Boolean): String? =
    if (enough) null else stringResource(R.string.trends_more_nights)

/** One point per calendar day; a day without a recording or without the value stays empty. */
private fun Trends.points(value: (TrendNight) -> Double?): List<TrendPoint?> =
    nights.map { night ->
      if (night == null) null
      else value(night)?.let { TrendPoint(it, night.poorData, night) }
    }
