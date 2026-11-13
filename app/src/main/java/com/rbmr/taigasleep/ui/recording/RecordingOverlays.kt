package com.rbmr.taigasleep.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.sleepScoreCardState
import com.rbmr.taigasleep.ui.components.ChartFullscreenDialog
import com.rbmr.taigasleep.ui.components.ConfirmDialog
import com.rbmr.taigasleep.ui.components.ConfirmDialogStyle
import com.rbmr.taigasleep.ui.components.HeartRateChart
import com.rbmr.taigasleep.ui.components.SleepStagesChart

/** Every sheet and dialog of the recording screen, drawn over the body it belongs to. */
@Composable
internal fun RecordingOverlays(
    state: RecordingDetailUiState,
    showSleepScore: Boolean,
    fullscreenChart: String?,
    showAnalysis: Boolean,
    showExport: Boolean,
    showDelete: Boolean,
    showRawDelete: Boolean,
    onDismissSleepScore: () -> Unit,
    onDismissChart: () -> Unit,
    onDismissAnalysis: () -> Unit,
    onSelectAnalysis: (String) -> Unit,
    onRunAnalysis: (String) -> Unit,
    onDismissExport: () -> Unit,
    onExportRaw: (Boolean) -> Unit,
    onExportMetadata: (Boolean) -> Unit,
    onExportResults: (Boolean) -> Unit,
    onExport: () -> Unit,
    onDismissDeleteRaw: () -> Unit,
    onConfirmDeleteRaw: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onConfirmUploadElsewhere: () -> Unit,
    onDismissUploadElsewhere: () -> Unit,
) {
  val result = state.result?.result

  state.uploadConfirmation?.let { target ->
    ConfirmDialog(
        title = stringResource(R.string.night_upload_other_account_title),
        body =
            stringResource(
                R.string.night_upload_other_account_body,
                target.server,
                target.account,
            ),
        confirmLabel = stringResource(R.string.night_upload_other_account_confirm),
        onConfirm = onConfirmUploadElsewhere,
        onDismiss = onDismissUploadElsewhere,
    )
  }

  if (showSleepScore) {
    SleepScoreSheet(
        item = result?.items?.firstOrNull { it.id == "sleep_quality" },
        state = result?.let(::sleepScoreCardState),
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

  if (showAnalysis && state.recording != null) {
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
  if (showExport) {
    ExportSheet(
        state = state,
        onExportRaw = onExportRaw,
        onExportMetadata = onExportMetadata,
        onExportResults = onExportResults,
        onExport = onExport,
        onDismiss = onDismissExport,
    )
  }
  if (showRawDelete) {
    ConfirmDialog(
        title = stringResource(R.string.night_raw_delete_title),
        body = stringResource(R.string.night_raw_delete_body),
        confirmLabel = stringResource(R.string.night_raw_delete_confirm),
        onConfirm = onConfirmDeleteRaw,
        onDismiss = onDismissDeleteRaw,
        style = ConfirmDialogStyle.Destructive,
    )
  }
  if (showDelete) {
    val uploaded = state.remoteId != null
    ConfirmDialog(
        title = stringResource(R.string.night_delete_title),
        body =
            stringResource(
                if (uploaded) R.string.night_delete_body else R.string.night_delete_local_body
            ),
        confirmLabel = stringResource(R.string.night_delete_confirm),
        onConfirm = onConfirmDelete,
        onDismiss = onDismissDelete,
        style = ConfirmDialogStyle.Destructive,
    )
  }
}
