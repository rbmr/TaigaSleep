package com.rbmr.taigasleep.ui.recording

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.MetricSpec

/**
 * The night screen: one scaffold that draws [RecordingDetailUiState] and forwards the user's
 * actions to [RecordingDetailViewModel]. The drawing lives in the files around this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
  // One view model per night, so opening another night starts from its own state.
  val nightKey = localId ?: remoteId?.let { "remote-$it" } ?: analysisId ?: "night"
  val viewModel: RecordingDetailViewModel = viewModel(key = nightKey, factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()

  var showQuality by rememberSaveable { mutableStateOf(false) }
  var showSleepScore by rememberSaveable { mutableStateOf(false) }
  var fullscreenChart by rememberSaveable { mutableStateOf<String?>(null) }
  var metric by remember { mutableStateOf<MetricSpec?>(null) }
  var showAnalysis by rememberSaveable { mutableStateOf(false) }
  var showDelete by remember { mutableStateOf(false) }
  var showRawDelete by remember { mutableStateOf(false) }

  // Every time the screen is shown, including when a stored view model is reused for the same
  // night: the night may have changed while it was closed.
  LaunchedEffect(Unit) { viewModel.refresh() }
  LaunchedEffect(state.closeRequested) { if (state.closeRequested) onBack() }
  LaunchedEffect(state.loginRequested) {
    if (state.loginRequested) {
      viewModel.loginShown()
      onLogin()
    }
  }

  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.night_title)) },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.night_back),
                )
              }
            },
            actions = {
              IconButton(onClick = viewModel::openDetails) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.night_details),
                )
              }
            },
        )
      },
  ) { padding ->
    NightBody(
        state = state,
        padding = padding,
        onUpload = viewModel::upload,
        onRefresh = viewModel::refresh,
        onRetryAnalysis = viewModel::retryAnalysis,
        onLogin = onLogin,
        onOpenAnalysis = {
          showAnalysis = true
          viewModel.loadAlgorithms()
        },
        onOpenQuality = { showQuality = true },
        onOpenSleepScore = { showSleepScore = true },
        onMetric = { metric = it },
        onExpand = { fullscreenChart = it },
    )

    NightOverlays(
        state = state,
        showQuality = showQuality,
        showSleepScore = showSleepScore,
        fullscreenChart = fullscreenChart,
        metric = metric,
        showAnalysis = showAnalysis,
        showDelete = showDelete,
        showRawDelete = showRawDelete,
        onDismissQuality = { showQuality = false },
        onDismissSleepScore = { showSleepScore = false },
        onDismissChart = { fullscreenChart = null },
        onDismissMetric = { metric = null },
        onDismissAnalysis = { showAnalysis = false },
        onSelectAnalysis = { id ->
          viewModel.selectAnalysis(id)
          showAnalysis = false
        },
        onRunAnalysis = viewModel::requestAnalysis,
        onCloseDetails = viewModel::closeDetails,
        onExport = viewModel::export,
        onDownloadRaw = viewModel::downloadRaw,
        onRequestDeleteRaw = { showRawDelete = true },
        onDismissDeleteRaw = { showRawDelete = false },
        onConfirmDeleteRaw = {
          showRawDelete = false
          viewModel.deleteRaw()
        },
        onRequestDelete = { showDelete = true },
        onDismissDelete = { showDelete = false },
        onConfirmDelete = {
          showDelete = false
          viewModel.delete()
        },
    )
  }
}
