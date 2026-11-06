package com.rbmr.taigasleep.ui.recording

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.MetricSpec
import com.rbmr.taigasleep.ui.components.ChartFullscreenDialog
import com.rbmr.taigasleep.ui.components.HeartRateChart
import com.rbmr.taigasleep.ui.components.SleepStagesChart

/** Every sheet and dialog of the recording screen, drawn over the body it belongs to. */
@Composable
internal fun NightOverlays(
    state: RecordingDetailUiState,
    showQuality: Boolean,
    showSleepScore: Boolean,
    fullscreenChart: String?,
    metric: MetricSpec?,
    showAnalysis: Boolean,
    showDelete: Boolean,
    showRawDelete: Boolean,
    onDismissQuality: () -> Unit,
    onDismissSleepScore: () -> Unit,
    onDismissChart: () -> Unit,
    onDismissMetric: () -> Unit,
    onDismissAnalysis: () -> Unit,
    onSelectAnalysis: (String) -> Unit,
    onRunAnalysis: (String) -> Unit,
    onCloseDetails: () -> Unit,
    onExport: () -> Unit,
    onDownloadRaw: () -> Unit,
    onRequestDeleteRaw: () -> Unit,
    onDismissDeleteRaw: () -> Unit,
    onConfirmDeleteRaw: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
  val result = state.result?.result

  if (showQuality) {
    DataQualitySheet(
        item = result?.items?.firstOrNull { it.id == "data_quality" },
        onDismiss = onDismissQuality,
    )
  }
  if (showSleepScore) {
    SleepScoreSheet(
        item = result?.items?.firstOrNull { it.id == "sleep_quality" },
        onDismiss = onDismissSleepScore,
    )
  }
  fullscreenChart?.let { chart ->
    val title =
        stringResource(
            if (chart == "sleep") R.string.night_sleep_stages else R.string.night_heart_rate_stats
        )
    ChartFullscreenDialog(title = title, onDismiss = onDismissChart) { chartModifier ->
      when (chart) {
        "sleep" ->
            SleepStagesChart(
                segments = state.segments,
                epochSeconds = state.epochSeconds,
                startUnixMs = state.start,
                modifier = chartModifier,
                capabilities = result?.capabilities,
                fullscreen = true,
            )
        else ->
            result?.items
                ?.firstOrNull { it.kind == "series" && it.id == "hr" }
                ?.series
                ?.let { series ->
                  HeartRateChart(series = series, modifier = chartModifier, fullscreen = true)
                }
      }
    }
  }
  metric?.let { spec -> MetricSheet(spec = spec, onDismiss = onDismissMetric) }

  if (showAnalysis && state.night != null) {
    AnalysisSheet(
        runs = state.analyses,
        selectedId = state.selectedAnalysisId,
        algorithms = state.algorithms,
        loading = state.algorithmsLoading,
        error = state.algorithmsError,
        busy = state.analysisBusy,
        onSelect = onSelectAnalysis,
        onRun = onRunAnalysis,
        onDismiss = onDismissAnalysis,
    )
  }
  if (state.showDetails) {
    RecordingDetailsSheet(
        state = state,
        onDismiss = onCloseDetails,
        onExport = onExport,
        onDownloadRaw = onDownloadRaw,
        onDeleteRaw = onRequestDeleteRaw,
        onDelete = onRequestDelete,
    )
  }
  if (showRawDelete) {
    AlertDialog(
        onDismissRequest = onDismissDeleteRaw,
        title = { Text(stringResource(R.string.night_raw_delete_title)) },
        text = { Text(stringResource(R.string.night_raw_delete_body)) },
        confirmButton = {
          TextButton(onClick = onConfirmDeleteRaw) {
            Text(stringResource(R.string.night_raw_delete_confirm))
          }
        },
        dismissButton = {
          TextButton(onClick = onDismissDeleteRaw) {
            Text(stringResource(R.string.night_cancel))
          }
        },
    )
  }
  if (showDelete) {
    val uploaded = state.remoteId != null
    AlertDialog(
        onDismissRequest = onDismissDelete,
        title = {
          Text(
              stringResource(
                  if (uploaded) R.string.night_delete_title else R.string.night_discard_title
              )
          )
        },
        text = {
          Text(
              stringResource(
                  if (uploaded) R.string.night_delete_body else R.string.night_discard_body
              )
          )
        },
        confirmButton = {
          TextButton(onClick = onConfirmDelete) {
            Text(
                stringResource(
                    if (uploaded) R.string.night_delete_confirm else R.string.night_discard_confirm
                )
            )
          }
        },
        dismissButton = {
          TextButton(onClick = onDismissDelete) {
            Text(stringResource(R.string.night_cancel))
          }
        },
    )
  }
}
