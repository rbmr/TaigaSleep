package com.rbmr.taigasleep.ui.recording

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.library.formatNightHeader
import com.rbmr.taigasleep.library.formatRecordingDate
import com.rbmr.taigasleep.ui.components.TaigaScreen

/**
 * The recording screen: one scrolling screen that draws [RecordingDetailUiState] and forwards the
 * user's actions to [RecordingDetailViewModel]. The drawing lives in the files around this one.
 */
@Composable
fun RecordingDetailScreen(
    localId: String?,
    remoteId: String?,
    analysisId: String?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val factory =
      remember(context, localId, remoteId, analysisId) {
        RecordingDetailFactory(context, localId, remoteId, analysisId)
      }
  // One view model per recording, so opening another recording starts from its own state.
  val recordingKey = localId ?: remoteId?.let { "remote-$it" } ?: analysisId ?: "recording"
  val viewModel: RecordingDetailViewModel = viewModel(key = recordingKey, factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()

  var showSleepScore by rememberSaveable { mutableStateOf(false) }
  var fullscreenChart by rememberSaveable { mutableStateOf<String?>(null) }
  var showAnalysis by rememberSaveable { mutableStateOf(false) }
  var showExport by rememberSaveable { mutableStateOf(false) }
  var showDelete by remember { mutableStateOf(false) }
  var showRawDelete by remember { mutableStateOf(false) }

  // Every time the screen is shown, including when a stored view model is reused for the same
  // recording: the recording may have changed while it was closed.
  LaunchedEffect(Unit) { viewModel.refresh() }
  LaunchedEffect(state.closeRequested) { if (state.closeRequested) onBack() }
  LaunchedEffect(state.loginRequested) {
    if (state.loginRequested) {
      viewModel.loginShown()
      onLogin()
    }
  }

  val headerLine =
      formatNightHeader(
          tracker = state.recordingTracker.ifBlank { stringResource(R.string.night_device_unknown) },
          startUtcMs = state.recordingStartUtcMs,
          endUtcMs = state.recordingEndUtcMs,
          lineFormat = stringResource(R.string.night_recording_meta),
          startOnlyFormat = stringResource(R.string.night_recording_meta_start_only),
      )

  TaigaScreen(
      title = formatRecordingDate(state.start),
      subtitle = headerLine,
      onBack = onBack,
      scrollable = false,
  ) {
    RecordingBody(
        state = state,
        onUpload = viewModel::upload,
        onRefresh = viewModel::refresh,
        onRetryAnalysis = viewModel::retryAnalysis,
        onLogin = onLogin,
        onOpenAnalysis = {
          showAnalysis = true
          viewModel.loadAlgorithms()
        },
        onOpenScore = { showSleepScore = true },
        onExpand = { fullscreenChart = it },
        onExport = { showExport = true },
        onDownloadRaw = viewModel::downloadRaw,
        onDeleteRaw = { showRawDelete = true },
        onDelete = { showDelete = true },
        modifier = Modifier.weight(1f),
    )
  }

  RecordingOverlays(
      state = state,
      showSleepScore = showSleepScore,
      fullscreenChart = fullscreenChart,
      showAnalysis = showAnalysis,
      showExport = showExport,
      showDelete = showDelete,
      showRawDelete = showRawDelete,
      onDismissSleepScore = { showSleepScore = false },
      onDismissChart = { fullscreenChart = null },
      onDismissAnalysis = { showAnalysis = false },
      onSelectAnalysis = { id ->
        viewModel.selectAnalysis(id)
        showAnalysis = false
      },
      onRunAnalysis = viewModel::requestAnalysis,
      onDismissExport = { showExport = false },
      onExportRaw = viewModel::setExportRaw,
      onExportMetadata = viewModel::setExportMetadata,
      onExportResults = viewModel::setExportResults,
      onExport = viewModel::export,
      onDismissDeleteRaw = { showRawDelete = false },
      onConfirmDeleteRaw = {
        showRawDelete = false
        viewModel.deleteRaw()
      },
      onDismissDelete = { showDelete = false },
      onConfirmDelete = {
        showDelete = false
        viewModel.delete()
      },
      onConfirmUploadElsewhere = viewModel::confirmUploadElsewhere,
      onDismissUploadElsewhere = viewModel::dismissUploadConfirmation,
  )
}
