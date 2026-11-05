package com.rbmr.taigasleep.ui.screens

import android.content.Context
import android.text.format.Formatter
import com.rbmr.taigasleep.storage.SessionStore
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.analysis.DataQualityMetricKeys
import com.rbmr.taigasleep.analysis.HeartRateMetricKeys
import com.rbmr.taigasleep.analysis.MetricSpec
import com.rbmr.taigasleep.analysis.MetricSupportState
import com.rbmr.taigasleep.analysis.MovementMetricKeys
import com.rbmr.taigasleep.analysis.SleepMetricKeys
import com.rbmr.taigasleep.analysis.formatMetric
import com.rbmr.taigasleep.analysis.metricNumber
import com.rbmr.taigasleep.analysis.splitMetricExplanation
import com.rbmr.taigasleep.analysis.metricSpec
import com.rbmr.taigasleep.analysis.supportState
import com.rbmr.taigasleep.night.AnalysisOrigin
import com.rbmr.taigasleep.night.AnalysisRun
import com.rbmr.taigasleep.night.AnalysisStatus
import com.rbmr.taigasleep.night.Night
import com.rbmr.taigasleep.night.NightRepository
import com.rbmr.taigasleep.night.NightStatus
import com.rbmr.taigasleep.night.formatDuration
import com.rbmr.taigasleep.night.formatNightDate
import com.rbmr.taigasleep.night.formatNightDateAndTime
import com.rbmr.taigasleep.night.parseApiDate
import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.AnalysisResultDto
import com.rbmr.taigasleep.remote.QualityComponentDto
import com.rbmr.taigasleep.remote.RecordingDto
import com.rbmr.taigasleep.remote.RemoteStatus
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.recording.ExportSource
import com.rbmr.taigasleep.recording.Exporter
import com.rbmr.taigasleep.recording.exportSource
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.ChartExpandButton
import com.rbmr.taigasleep.ui.components.ChartFullscreenDialog
import com.rbmr.taigasleep.ui.components.HeartRateChart
import com.rbmr.taigasleep.ui.components.SleepStagesChart
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScoreRing
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StageBreakdown
import com.rbmr.taigasleep.ui.components.hrSeriesPoints
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import com.rbmr.taigasleep.upload.RawActionResult
import com.rbmr.taigasleep.upload.RawRetention
import com.rbmr.taigasleep.upload.RecordingDeleter
import com.rbmr.taigasleep.upload.UploadCoordinator
import com.rbmr.taigasleep.upload.UploadFailure
import com.rbmr.taigasleep.upload.UploadScheduler
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class NightDetailState(
    val local: SessionInfo? = null,
    val remote: RecordingDto? = null,
    val analysis: AnalysisDto? = null,
    val remoteId: String? = null,
    val analysisId: String? = null,
    val status: NightStatus = NightStatus.LOCAL_ONLY,
    val progress: Float = 0f,
    val startUtcMs: Long = 0,
    val endUtcMs: Long? = null,
    val durationMs: Long = 0,
    val remoteCreatedAtMs: Long? = null,
    val segments: List<StageSegmentDto> = emptyList(),
    val epochSeconds: Int = 30,
    val analyses: List<AnalysisRun> = emptyList(),
    val failure: UploadFailure? = null,
    val errorDetail: String? = null,
    val warning: String? = null,
    val loading: Boolean = true,
    val offlineSaved: Boolean = false,
    val error: String? = null,
)

private enum class ExportPhase {
  DOWNLOADING,
  ZIPPING,
}

private fun Night.toDetailState(
    result: AnalysisDto?,
    loading: Boolean,
    offlineSaved: Boolean,
    error: String?,
    loginExpired: Boolean,
    loginError: String,
): NightDetailState =
    NightDetailState(
        local = local,
        remote = remote,
        analysis = result,
        remoteId = remoteId,
        analysisId = analysisId,
        status = status,
        progress = progress,
        startUtcMs = startUtcMs,
        endUtcMs = endUtcMs,
        durationMs = durationMs,
        remoteCreatedAtMs =
            parseApiDate(remote?.created.orEmpty()).takeIf { it > 0 }
                ?: backup.uploadedAtMs,
        segments = segments,
        epochSeconds = epochSeconds,
        analyses = analyses,
        failure = if (loginExpired) UploadFailure.LOGIN else failure,
        errorDetail = if (loginExpired) loginError else errorDetail,
        warning = warning,
        loading = loading,
        offlineSaved = offlineSaved,
        error = error,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightDetailScreen(
    localId: String?,
    remoteId: String?,
    analysisId: String?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val repository = remember { NightRepository.of(context) }
  val remoteStatus by repository.remoteStatus.collectAsStateWithLifecycle()
  val nights by repository.nights.collectAsStateWithLifecycle()
  val key = localId ?: remoteId?.let { "remote-$it" } ?: analysisId ?: "night"
  val scope = rememberCoroutineScope()

  var result by remember(key) {
    mutableStateOf(repository.night(key)?.let { repository.cachedResult(key, it.analysisId) })
  }
  var loading by remember(key) { mutableStateOf(result == null) }
  var offlineSaved by remember(key) { mutableStateOf(false) }
  var error by remember(key) { mutableStateOf<String?>(null) }
  var loginExpired by remember(key) { mutableStateOf(false) }
  var refreshKey by remember { mutableIntStateOf(0) }
  var showQuality by rememberSaveable { mutableStateOf(false) }
  var showSleepScore by rememberSaveable { mutableStateOf(false) }
  var fullscreenChart by rememberSaveable { mutableStateOf<String?>(null) }
  var metric by rememberSaveable { mutableStateOf<String?>(null) }
  var actionError by remember { mutableStateOf<String?>(null) }
  var showDetails by remember { mutableStateOf(false) }
  var showDelete by remember { mutableStateOf(false) }
  var showRawDelete by remember { mutableStateOf(false) }
  var showAnalysis by remember { mutableStateOf(false) }
  var analysisBusy by remember { mutableStateOf(false) }
  var algorithms by remember { mutableStateOf<List<AlgorithmDto>>(emptyList()) }
  var algorithmsLoading by remember { mutableStateOf(false) }
  var algorithmsError by remember { mutableStateOf<String?>(null) }
  var exportBusy by remember { mutableStateOf(false) }
  val exportPhase = remember { mutableStateOf<ExportPhase?>(null) }
  val exportProgress = remember { mutableStateOf<Float?>(null) }
  val loadFailed = stringResource(R.string.night_load_failed)
  val loginError = stringResource(R.string.upload_error_login)
  val rawRestoreFailed = stringResource(R.string.night_raw_restore_failed)
  val rawDeleteFailed = stringResource(R.string.night_raw_delete_failed)
  val rawNotUploaded = stringResource(R.string.night_raw_not_uploaded)

  val night =
      nights.firstOrNull { it.key == key }
          ?: localId?.let { id -> nights.firstOrNull { it.localId == id } }

  LaunchedEffect(key, refreshKey, remoteStatus, night?.analysisId) {
    RecordingRepository.refreshSessions(context)
    repository.reload()
    val current = repository.night(key)
    result = repository.cachedResult(key, current?.analysisId) ?: result
    loading = false
    val server = current?.remoteId ?: remoteId
    if (server == null) {
      offlineSaved = false
      error = null
      return@LaunchedEffect
    }
    if (remoteStatus !is RemoteStatus.Connected) {
      offlineSaved = result != null
      error = if (current?.local != null) null else loadFailed
      return@LaunchedEffect
    }
    repository
        .refreshNight(key, server, night?.analysisId ?: analysisId)
        .onSuccess { fresh ->
          if (fresh != null && (fresh.isSucceeded || result == null)) result = fresh
          loginExpired = false
          offlineSaved = false
          error = null
        }
        .onFailure { failure ->
          loginExpired = failure is TaigaUnauthorizedException
          offlineSaved = result != null
          error = failure.message
        }
  }

  // The night can arrive after the first composition (the stores are read on a flow). Load its
  // cached result as soon as the selected analysis is known, without waiting for the network. A
  // result that arrives while the screen is open (the live sync saves it) is picked up the same way.
  LaunchedEffect(key, night?.analysisId, night?.resultDownloaded) {
    val selected = night?.analysisId
    if (selected == null) return@LaunchedEffect
    val cached = repository.cachedResult(key, selected)
    if (cached != null) {
      result = cached
    } else if (result?.id != selected) {
      result = null
    }
  }

  LaunchedEffect(showAnalysis, night?.key, remoteStatus) {
    if (!showAnalysis) return@LaunchedEffect
    val current = night ?: return@LaunchedEffect
    algorithmsLoading = true
    algorithmsError = null
    repository
        .compatibleAlgorithms(current)
        .onSuccess { algorithms = it }
        .onFailure { algorithmsError = it.message }
    algorithmsLoading = false
  }

  val state =
      night?.toDetailState(result, loading, offlineSaved, error, loginExpired, loginError)
          ?: NightDetailState(loading = loading, error = error)

  fun upload() {
    val id = state.local?.id ?: localId ?: return
    if (remoteStatus !is RemoteStatus.Connected) {
      onLogin()
      return
    }
    actionError = null
    UploadScheduler.enqueueManual(context, id)
    refreshKey++
  }

  fun checkNow() {
    refreshKey++
  }

  fun retryAnalysis() {
    val key = state.local?.id ?: state.remoteId?.let { "remote-$it" } ?: localId ?: return
    if (state.remoteId == null) return
    actionError = null
    scope.launch {
      val result = UploadCoordinator.retryAnalysis(context, key, state.analysisId)
      result.onSuccess { refreshKey++ }.onFailure { actionError = it.message }
    }
  }

  fun exportNight() {
    val id = state.local?.id ?: localId ?: return
    if (exportBusy) return
    actionError = null
    exportBusy = true
    exportPhase.value = null
    exportProgress.value = null
    scope.launch {
      val result =
          prepareAndShare(
              context = context,
              id = id,
              hasRaw = state.local?.hasRaw == true,
              remoteId = state.remoteId,
              phase = exportPhase,
              progress = exportProgress,
          )
      exportBusy = false
      exportPhase.value = null
      exportProgress.value = null
      if (result.isFailure) {
        showDetails = false
        actionError =
            listOfNotNull(
                    rawRestoreFailed,
                    result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() },
                )
                .joinToString(": ")
      } else {
        showDetails = false
        refreshKey++
      }
    }
  }

  fun downloadRaw() {
    val id = state.local?.id ?: localId ?: return
    val server = state.remoteId ?: return
    if (exportBusy) return
    actionError = null
    exportBusy = true
    scope.launch {
      val result = downloadOnly(context, id, server, exportPhase, exportProgress)
      exportBusy = false
      exportPhase.value = null
      exportProgress.value = null
      if (result.isFailure) {
        showDetails = false
        actionError =
            listOfNotNull(
                    rawRestoreFailed,
                    result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() },
                )
                .joinToString(": ")
      } else {
        refreshKey++
      }
    }
  }

  fun deleteRaw() {
    val id = state.local?.id ?: localId ?: return
    actionError = null
    scope.launch {
      when (val result = RawRetention.deleteRaw(context, id)) {
        RawActionResult.Done,
        RawActionResult.NothingToDelete -> refreshKey++
        RawActionResult.NotUploaded,
        RawActionResult.Recording -> {
          showDetails = false
          actionError = rawNotUploaded
        }
        is RawActionResult.Failed -> {
          showDetails = false
          actionError = rawDeleteFailed
        }
      }
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
              IconButton(onClick = { showDetails = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.night_details),
                )
              }
            },
        )
      },
  ) { padding ->
    val start = startOf(state)
    val device = state.local?.sensorName ?: state.remote?.device.orEmpty()
    val run = state.analysis
    val analysis = run?.result
    val sleepScore = analysis?.sleep_quality?.score
    val processed = run?.isSucceeded == true
    val selectedMetricSpec = metricSpec(metric.orEmpty())

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        ScreenHeader(
            kicker = stringResource(R.string.night_kicker),
            title = formatNightDate(start),
            subtitle = device.ifBlank { stringResource(R.string.night_device_unknown) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
      }
      item {
        NightHeaderCard(
            sleepScore = sleepScore,
            processed = processed,
            onScoreClick = { if (state.analysis != null) showSleepScore = true },
        )
      }
      if (state.remoteId != null) {
        item {
          val model = state.analysis?.algorithm.orEmpty()
          val version = state.analysis?.version.orEmpty()
          val label =
              if (model.isNotBlank()) {
                stringResource(R.string.night_analyzed_with, model, version) +
                    " · " +
                    stringResource(R.string.night_analysis_change)
              } else {
                stringResource(R.string.night_analysis_choose)
              }
          Text(
              text = label,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              modifier =
                  Modifier.padding(horizontal = 4.dp).clickable { showAnalysis = true },
          )
        }
      }

      state.error?.takeIf { !state.offlineSaved }?.let { message ->
        item {
          StatusBanner(
              kind = StatusKind.Warn,
              title = stringResource(R.string.night_load_failed),
              body = message,
              actions = {
                SecondaryButton(
                    text = stringResource(R.string.night_retry),
                    onClick = { refreshKey++ },
                    modifier = Modifier.weight(1f),
                )
              },
          )
        }
      }
      if (state.offlineSaved) {
        item {
          Text(
              text = stringResource(R.string.night_offline_saved),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }
      actionError?.let { message ->
        item {
          Text(
              text = message,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }

      if (state.loading && state.analysis == null) {
        item {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.height(32.dp))
          }
        }
      }

      when {
        processed && analysis != null -> {
          item {
            SleepStatsCard(
                state = state,
                start = start,
                analysis = analysis,
                onMetric = { metric = it.key },
                onExpand = { fullscreenChart = "sleep" },
            )
          }
          item {
            HeartRateStatsCard(
                analysis = analysis,
                onMetric = { metric = it.key },
                onExpand = { fullscreenChart = "heart" },
            )
          }
          item {
            DataQualityStatsCard(
                analysis = analysis,
                onOpen = { showQuality = true },
                onMetric = { metric = it.key },
            )
          }
          item {
            MovementStatsCard(
                analysis = analysis,
                onMetric = { metric = it.key },
            )
          }
          analysis.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
            item {
              TaigaCard {
                Text(
                    text = stringResource(R.string.night_warnings),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                warnings.forEach {
                  Text(
                      text = "• $it",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
          run?.algorithm?.takeIf { it.isNotBlank() }?.let { model ->
            item {
              Text(
                  text =
                      stringResource(
                          R.string.night_analyzed_with,
                          model,
                          run.version,
                      ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 4.dp),
              )
            }
          }
        }
        state.status == NightStatus.PROCESSING -> {
          item {
            TaigaCard {
              Text(
                  text = stringResource(R.string.night_processing_title),
                  style = MaterialTheme.typography.titleLarge,
              )
              Spacer(Modifier.height(6.dp))
              Text(
                  text = stringResource(R.string.night_processing_body),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(12.dp))
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              Spacer(Modifier.height(12.dp))
              SecondaryButton(
                  text = stringResource(R.string.night_check_again),
                  onClick = ::checkNow,
              )
            }
          }
        }
        state.status == NightStatus.QUEUED || state.status == NightStatus.UPLOADING -> {
          item {
            TaigaCard {
              Text(
                  text = stringResource(R.string.night_uploading_title),
                  style = MaterialTheme.typography.titleLarge,
              )
              Spacer(Modifier.height(6.dp))
              Text(
                  text =
                      if (state.status == NightStatus.UPLOADING) {
                        stringResource(
                            R.string.night_uploading_progress,
                            ((state.progress) * 100)
                                .toInt()
                                .coerceIn(0, 100),
                        ) + " · " + stringResource(R.string.night_uploading_body)
                      } else {
                        stringResource(R.string.night_queued_body)
                      },
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(12.dp))
              if (state.status == NightStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
              }
              SecondaryButton(
                  text =
                      stringResource(
                          if (state.local != null) R.string.night_retry_upload
                          else R.string.night_check_again
                      ),
                  onClick = { if (state.local != null) upload() else checkNow() },
              )
            }
          }
        }
        state.status == NightStatus.FAILED -> {
          item {
            StatusBanner(
                kind = StatusKind.Bad,
                title =
                    stringResource(
                        if (state.failure == UploadFailure.ANALYSIS) R.string.night_analysis_failed_title
                        else R.string.night_upload_failed_title
                    ),
                body =
                    state.errorDetail?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.night_analysis_failed_body),
                actions = {
                  when {
                    state.failure == UploadFailure.LOGIN &&
                        remoteStatus !is RemoteStatus.Connected ->
                        PrimaryButton(
                            text = stringResource(R.string.history_action_login),
                            onClick = onLogin,
                            modifier = Modifier.weight(1f),
                        )
                    state.failure == UploadFailure.ANALYSIS || state.local != null ->
                        PrimaryButton(
                            text = stringResource(R.string.night_retry),
                            onClick = {
                              if (state.failure == UploadFailure.ANALYSIS) retryAnalysis()
                              else upload()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    else ->
                        SecondaryButton(
                            text = stringResource(R.string.night_check_again),
                            onClick = ::checkNow,
                            modifier = Modifier.weight(1f),
                        )
                  }
                },
            )
          }
        }
        state.local != null -> {
          item {
            TaigaCard {
              Text(
                  text = stringResource(R.string.night_local_only_title),
                  style = MaterialTheme.typography.titleLarge,
              )
              Spacer(Modifier.height(6.dp))
              Text(
                  text = stringResource(R.string.night_local_only_body),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(12.dp))
              PrimaryButton(
                  text =
                      stringResource(
                          if (remoteStatus is RemoteStatus.Connected) R.string.night_upload
                          else R.string.history_action_login
                      ),
                  onClick = { if (remoteStatus is RemoteStatus.Connected) upload() else onLogin() },
                  size = ButtonSize.Regular,
              )
            }
          }
        }
        else -> Unit
      }
      item { Spacer(Modifier.height(12.dp)) }
    }

    if (showQuality) {
      DataQualitySheet(
          analysis = state.analysis?.result,
          onDismiss = { showQuality = false },
      )
    }
    if (showSleepScore) {
      SleepScoreSheet(
          analysis = state.analysis?.result,
          onDismiss = { showSleepScore = false },
      )
    }
    fullscreenChart?.let { chart ->
      val title =
          stringResource(
              if (chart == "sleep") R.string.night_sleep_stages
              else R.string.night_heart_rate_stats
          )
      ChartFullscreenDialog(title = title, onDismiss = { fullscreenChart = null }) { chartModifier ->
        when (chart) {
          "sleep" ->
              SleepStagesChart(
                  segments = segmentsOf(state),
                  epochSeconds = epochSecondsOf(state),
                  startUnixMs =
                      startOf(state).takeIf { it > 0 }
                          ?: state.analysis?.result?.stages?.start_unix_ms
                          ?: 0L,
                  support = state.analysis?.result?.metric_support,
                  modifier = chartModifier,
                  fullscreen = true,
              )
          else ->
              state.analysis?.result?.hr?.let { series ->
                HeartRateChart(series = series, modifier = chartModifier, fullscreen = true)
              }
        }
      }
    }
    selectedMetricSpec?.let { spec ->
      MetricSheet(spec = spec, onDismiss = { metric = null })
    }
    if (showAnalysis && night != null) {
      AnalysisSheet(
          runs = state.analyses,
          selectedId = state.analysisId,
          algorithms = algorithms,
          loading = algorithmsLoading,
          error = algorithmsError,
          busy = analysisBusy,
          onSelect = { id ->
            repository.selectAnalysis(night, id)
            showAnalysis = false
            refreshKey++
          },
          onRun = { id ->
            analysisBusy = true
            scope.launch {
              repository
                  .requestManual(night, id)
                  .onSuccess { refreshKey++ }
                  .onFailure { algorithmsError = it.message }
              analysisBusy = false
            }
          },
          onDismiss = { showAnalysis = false },
      )
    }
    if (showDetails) {
      NightDetailsSheet(
          state = state,
          exportBusy = exportBusy,
          exportPhase = exportPhase.value,
          exportProgress = exportProgress.value,
          onDismiss = { showDetails = false },
          onExport = ::exportNight,
          onDownloadRaw = ::downloadRaw,
          onDeleteRaw = { showRawDelete = true },
          onDelete = {
            showDetails = false
            showDelete = true
          },
      )
    }
    if (showRawDelete) {
      AlertDialog(
          onDismissRequest = { showRawDelete = false },
          title = { Text(stringResource(R.string.night_raw_delete_title)) },
          text = { Text(stringResource(R.string.night_raw_delete_body)) },
          confirmButton = {
            TextButton(
                onClick = {
                  showRawDelete = false
                  deleteRaw()
                }
            ) {
              Text(stringResource(R.string.night_raw_delete_confirm))
            }
          },
          dismissButton = {
            TextButton(onClick = { showRawDelete = false }) {
              Text(stringResource(R.string.night_cancel))
            }
          },
      )
    }
    if (showDelete) {
      AlertDialog(
          onDismissRequest = { showDelete = false },
          title = { Text(stringResource(R.string.night_delete_title)) },
          text = { Text(stringResource(R.string.night_delete_body)) },
          confirmButton = {
            TextButton(
                onClick = {
                  showDelete = false
                  RecordingDeleter.delete(
                      context,
                      state.local?.id ?: localId,
                      state.remoteId ?: remoteId,
                  )
                  onBack()
                }
            ) {
              Text(stringResource(R.string.night_delete_confirm))
            }
          },
          dismissButton = {
            TextButton(onClick = { showDelete = false }) {
              Text(stringResource(R.string.night_cancel))
            }
          },
      )
    }
  }
}

/** Downloads the raw streams for a night without building an export. */
private suspend fun downloadOnly(
    context: Context,
    id: String,
    remoteId: String,
    phase: MutableState<ExportPhase?>,
    progress: MutableState<Float?>,
): Result<Unit> {
  phase.value = ExportPhase.DOWNLOADING
  progress.value = 0f
  return RawRetention.restore(context, id, remoteId) { progress.value = it }
}

/**
 * Prepares one night's export and opens the share sheet. Server-only raw data is restored first,
 * with progress, so there is no separate download step in the UI.
 */
private suspend fun prepareAndShare(
    context: Context,
    id: String,
    hasRaw: Boolean,
    remoteId: String?,
    phase: MutableState<ExportPhase?>,
    progress: MutableState<Float?>,
): Result<Unit> {
  when (exportSource(hasRaw, remoteId != null)) {
    ExportSource.LOCAL -> Unit
    ExportSource.UNAVAILABLE ->
        return Result.failure(IllegalStateException("no copy of this night is available"))
    ExportSource.RESTORE -> {
      val server =
          remoteId ?: return Result.failure(IllegalStateException("no server copy is available"))
      phase.value = ExportPhase.DOWNLOADING
      progress.value = 0f
      val restored = RawRetention.restore(context, id, server) { progress.value = it }
      if (restored.isFailure) return restored
    }
  }
  phase.value = ExportPhase.ZIPPING
  progress.value = 0f
  Exporter.share(context, id) { progress.value = it }
  progress.value = 1f
  return Result.success(Unit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightDetailsSheet(
    state: NightDetailState,
    exportBusy: Boolean,
    exportPhase: ExportPhase?,
    exportProgress: Float?,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onDownloadRaw: () -> Unit,
    onDeleteRaw: () -> Unit,
    onDelete: () -> Unit,
) {
  val context = LocalContext.current
  val local = state.local
  val device =
      local?.sensorName?.takeIf { it.isNotBlank() }
          ?: state.remote?.device?.takeIf { it.isNotBlank() }
          ?: ""
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      contentWindowInsets = { WindowInsets.navigationBars },
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
          text = stringResource(R.string.night_details_title),
          style = MaterialTheme.typography.headlineSmall,
      )
      DetailRow(
          label = stringResource(R.string.night_details_started),
          value = formatNightDateAndTime(state.startUtcMs),
      )
      DetailRow(
          label = stringResource(R.string.night_details_stopped),
          value =
              state.endUtcMs?.takeIf { it > 0 }?.let { formatNightDateAndTime(it) }
                  ?: "—",
      )
      DetailRow(
          label = stringResource(R.string.night_details_device),
          value = device.ifBlank { stringResource(R.string.night_device_unknown) },
      )
      DetailRow(
          label = stringResource(R.string.night_details_duration),
          value = formatDuration(state.durationMs),
      )
      DetailRow(
          label = stringResource(R.string.night_details_phone),
          value =
              when {
                local?.hasRaw == true ->
                    stringResource(
                        R.string.night_details_raw_on_phone,
                        Formatter.formatShortFileSize(
                            context,
                            SessionStore.RAW_FILES.sumOf { File(local.dir, it).length() },
                        ),
                    )
                else -> stringResource(R.string.night_details_not_on_phone)
              },
      )
      when {
        local?.hasRaw == true ->
            TextButton(
                onClick = onDeleteRaw,
                enabled = !exportBusy,
                modifier = Modifier.align(Alignment.End).height(36.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
              Text(
                  text = stringResource(R.string.night_details_delete_raw),
                  color = MaterialTheme.taiga.bad,
              )
            }
        local != null && state.remoteId != null ->
            TextButton(
                onClick = onDownloadRaw,
                enabled = !exportBusy,
                modifier = Modifier.align(Alignment.End).height(36.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
              Text(text = stringResource(R.string.night_details_download_raw))
            }
      }
      if (exportBusy) {
        val percent = ((exportProgress ?: 0f) * 100).toInt().coerceIn(0, 100)
        if (exportProgress != null) {
          LinearProgressIndicator(
              progress = { exportProgress.coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth(),
          )
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text =
                when (exportPhase) {
                  ExportPhase.DOWNLOADING ->
                      stringResource(R.string.night_details_downloading, percent)
                  ExportPhase.ZIPPING -> stringResource(R.string.night_details_zipping, percent)
                  null -> stringResource(R.string.night_details_preparing)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      DetailRow(
          label = stringResource(R.string.night_details_server),
          value =
              when {
                state.remoteId == null -> stringResource(R.string.night_details_not_uploaded)
                state.remoteCreatedAtMs != null && state.remoteCreatedAtMs > 0 ->
                    formatNightDateAndTime(state.remoteCreatedAtMs)
                else -> "—"
              },
      )
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PrimaryButton(
            text = stringResource(R.string.night_export_action),
            onClick = onExport,
            enabled =
                local != null &&
                    exportSource(local.hasRaw, state.remoteId != null) !=
                        ExportSource.UNAVAILABLE &&
                    !exportBusy,
            modifier = Modifier.weight(1f),
        )
        DestructiveButton(
            text = stringResource(R.string.night_delete_action),
            onClick = onDelete,
            enabled = !exportBusy,
            modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisSheet(
    runs: List<AnalysisRun>,
    selectedId: String?,
    algorithms: List<AlgorithmDto>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = stringResource(R.string.night_analysis),
          style = MaterialTheme.typography.headlineSmall,
      )
      if (runs.isNotEmpty()) {
        Text(
            text = stringResource(R.string.night_analysis_this_night),
            style = MaterialTheme.typography.titleMedium,
        )
        runs.forEach { run ->
          AnalysisRunRow(run = run, selectedId = selectedId, busy = busy, onSelect = onSelect)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }
      Text(
          text = stringResource(R.string.night_analysis_add),
          style = MaterialTheme.typography.titleMedium,
      )
      when {
        loading -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.night_analysis_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        error != null -> {
          Text(
              text = error,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
          )
        }
        else -> {
          val existing = runs.map { it.algorithm to it.version }.toSet()
          val available = algorithms.filter { (it.id to it.version) !in existing }
          if (available.isEmpty()) {
            Text(
                text = stringResource(R.string.night_analysis_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            available.forEach { algorithm ->
              AlgorithmRow(algorithm = algorithm, busy = busy, onRun = onRun)
            }
          }
        }
      }
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.night_sheet_close))
      }
    }
  }
}

@Composable
private fun AnalysisRunRow(
    run: AnalysisRun,
    selectedId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(
          text =
              buildString {
                append(run.algorithm.ifBlank { run.id })
                if (run.version.isNotBlank()) {
                  append(" ")
                  append(run.version)
                }
                if (run.origin == AnalysisOrigin.MANUAL) append(" · Manual")
              },
          style = MaterialTheme.typography.titleSmall,
      )
      Text(
          text = analysisStatus(run.status),
          style = MaterialTheme.typography.bodySmall,
          color =
              when (run.status) {
                AnalysisStatus.DONE -> MaterialTheme.taiga.ok
                AnalysisStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
      )
    }
    if (run.status == AnalysisStatus.DONE) {
      if (run.id == selectedId) {
        Text(
            text = stringResource(R.string.night_analysis_shown),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.taiga.ok,
        )
      } else {
        TextButton(onClick = { onSelect(run.id) }, enabled = !busy) {
          Text(stringResource(R.string.night_analysis_show))
        }
      }
    }
  }
}

@Composable
private fun AlgorithmRow(algorithm: AlgorithmDto, busy: Boolean, onRun: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text = algorithm.name.ifBlank { algorithm.id },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "v${algorithm.version}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { onRun(algorithm.id) }, enabled = !busy) {
        Text(stringResource(R.string.night_analysis_run))
      }
    }
    if (algorithm.description.isNotBlank()) {
      Text(
          text = algorithm.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (algorithm.supported_stages.isNotEmpty()) {
      Text(
          text =
              stringResource(
                  R.string.night_analysis_stages,
                  algorithm.supported_stages.joinToString(", "),
              ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun analysisStatus(status: AnalysisStatus): String =
    stringResource(
        when (status) {
          AnalysisStatus.QUEUED -> R.string.night_analysis_status_queued
          AnalysisStatus.RUNNING -> R.string.night_analysis_status_running
          AnalysisStatus.DONE -> R.string.night_analysis_status_done
          AnalysisStatus.FAILED -> R.string.night_analysis_status_failed
        }
    )

@Composable
private fun NightHeaderCard(
    sleepScore: Double?,
    processed: Boolean,
    onScoreClick: () -> Unit,
) {
  TaigaCard(
      modifier = if (processed) Modifier.clickable(onClick = onScoreClick) else Modifier
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text =
                if (processed) stringResource(R.string.night_processed)
                else stringResource(R.string.night_not_processed),
            style = MaterialTheme.typography.labelLarge,
            color =
                if (processed) MaterialTheme.taiga.ok
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                if (processed) stringResource(R.string.night_sleep_score_tap)
                else stringResource(R.string.night_header_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (processed) {
        ScoreRing(
            score = sleepScore,
            label = stringResource(R.string.night_sleep_score),
        )
      }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End,
        modifier = Modifier.weight(1.4f),
    )
  }
}

@Composable
private fun SleepStatsCard(
    state: NightDetailState,
    start: Long,
    analysis: AnalysisResultDto,
    onMetric: (MetricSpec) -> Unit,
    onExpand: () -> Unit,
) {
  TaigaCard {
    Text(
        text = stringResource(R.string.night_sleep_stats),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = stringResource(R.string.night_sleep_stages),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
      )
      ChartExpandButton(onClick = onExpand)
    }
    Spacer(Modifier.height(6.dp))
    SleepStagesChart(
        segments = segmentsOf(state),
        epochSeconds = epochSecondsOf(state),
        startUnixMs = start.takeIf { it > 0 } ?: analysis.stages?.start_unix_ms ?: 0L,
        support = analysis.metric_support,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.night_stage_breakdown),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(10.dp))
    StageBreakdown(
        segments = segmentsOf(state),
        epochSeconds = epochSecondsOf(state),
        support = analysis.metric_support,
    )
    Spacer(Modifier.height(16.dp))
    MetricGrid(analysis = analysis, keys = SleepMetricKeys, onMetric = onMetric)
  }
}

@Composable
private fun HeartRateStatsCard(
    analysis: AnalysisResultDto,
    onMetric: (MetricSpec) -> Unit,
    onExpand: () -> Unit,
) {
  TaigaCard {
    val series = analysis.hr
    val points = hrSeriesPoints(series)
    val hasSamples = points.any { it != null }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = stringResource(R.string.night_heart_rate_stats),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
      )
      if (hasSamples) ChartExpandButton(onClick = onExpand)
    }
    Spacer(Modifier.height(6.dp))
    val support = analysis.metric_support
    val noStream = supportState("hr_mean_bpm", support) == MetricSupportState.UNSUPPORTED_DEVICE
    when {
      hasSamples && series != null -> {
        HeartRateChart(series = series)
      }
      noStream -> {
        Text(
            text = stringResource(R.string.night_heart_rate_missing_stream),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      series != null -> {
        Text(
            text = stringResource(R.string.night_heart_rate_no_samples),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      else -> {
        Text(
            text = stringResource(R.string.night_heart_rate_no_series),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    MetricGrid(analysis = analysis, keys = HeartRateMetricKeys, onMetric = onMetric)
    val rrCount = metricNumber(analysis.metrics, "hrv_rr_count")?.roundToInt()
    if (rrCount != null && rrCount > 0) {
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.night_hrv_supporting, rrCount),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun DataQualityStatsCard(
    analysis: AnalysisResultDto,
    onOpen: () -> Unit,
    onMetric: (MetricSpec) -> Unit,
) {
  TaigaCard {
    Row(
        modifier = Modifier.clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            text = stringResource(R.string.night_data_quality_stats),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.night_data_quality_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                if (analysis.quality?.score == null) stringResource(R.string.night_data_quality_none)
                else stringResource(R.string.night_data_quality_tap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      ScoreRing(
          score = analysis.quality?.score,
          label = stringResource(R.string.night_data_quality),
          size = 88,
      )
    }
    Spacer(Modifier.height(12.dp))
    MetricGrid(analysis = analysis, keys = DataQualityMetricKeys, onMetric = onMetric)
  }
}

@Composable
private fun MovementStatsCard(
    analysis: AnalysisResultDto,
    onMetric: (MetricSpec) -> Unit,
) {
  TaigaCard {
    Text(
        text = stringResource(R.string.night_movement),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(10.dp))
    MetricGrid(analysis = analysis, keys = MovementMetricKeys, onMetric = onMetric)
  }
}

@Composable
private fun MetricGrid(
    analysis: AnalysisResultDto,
    keys: List<String>,
    onMetric: (MetricSpec) -> Unit,
) {
  val metrics = analysis.metrics
  val support = analysis.metric_support
  val specs = keys.mapNotNull { metricSpec(it) }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    specs.chunked(2).forEach { pair ->
      Row(
          modifier = Modifier.height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        pair.forEach { spec ->
          val state = supportState(spec.key, support)
          val value = formatMetric(spec.kind, metrics?.get(spec.key))
          MetricTile(
              spec = spec,
              value = if (state == MetricSupportState.SUPPORTED) value ?: "—" else value ?: "—",
              note =
                  when (state) {
                    MetricSupportState.UNSUPPORTED_DEVICE ->
                        stringResource(R.string.night_metric_unsupported_device)
                    MetricSupportState.INSUFFICIENT_DATA ->
                        stringResource(R.string.night_metric_insufficient)
                    MetricSupportState.UNSUPPORTED_ALGORITHM ->
                        stringResource(R.string.night_metric_unsupported_algorithm)
                    else -> null
                  },
              supported =
                  state == MetricSupportState.SUPPORTED ||
                      (state == MetricSupportState.MISSING && value != null),
              onClick = { onMetric(spec) },
              modifier = Modifier.weight(1f).fillMaxHeight(),
          )
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun MetricTile(
    spec: MetricSpec,
    value: String,
    note: String?,
    supported: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier.heightIn(min = 92.dp).clickable(onClick = onClick),
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
      Text(
          text = stringResource(spec.titleRes),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(4.dp))
      Text(
          text = if (supported) value else "—",
          style = MaterialTheme.typography.titleLarge,
          color =
              if (supported) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (note != null) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricSheet(spec: MetricSpec, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(spec.titleRes), style = MaterialTheme.typography.headlineSmall)
      val explanation = splitMetricExplanation(stringResource(spec.explanationRes))
      Text(
          text = explanation.body,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      explanation.source?.let { source ->
        Text(
            text = source,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataQualitySheet(analysis: AnalysisResultDto?, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = stringResource(R.string.night_data_quality_breakdown),
          style = MaterialTheme.typography.headlineSmall,
      )
      val quality = analysis?.quality
      Row(verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(
            score = quality?.score,
            label = stringResource(R.string.night_data_quality),
            size = 82,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text =
                if (quality?.score == null) stringResource(R.string.night_data_quality_none)
                else stringResource(R.string.night_data_quality_score, quality.score?.roundToInt() ?: 0),
            style = MaterialTheme.typography.titleMedium,
        )
      }
      Text(
          text = stringResource(R.string.night_data_quality_explainer),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          text = stringResource(R.string.night_quality_formula),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      val components = quality?.components.orEmpty()
      val keys =
          listOf(
              "usable_epoch_coverage",
              "artifact_free_rate",
              "mean_stage_confidence",
              "hr_coverage",
          )
      ScoreComponentRows(components = components, keys = keys, label = ::dataQualityComponentLabel)
      quality?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ScoreNotes(notes)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepScoreSheet(analysis: AnalysisResultDto?, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = stringResource(R.string.night_sleep_score_breakdown),
          style = MaterialTheme.typography.headlineSmall,
      )
      val quality = analysis?.sleep_quality
      Row(verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(
            score = quality?.score,
            label = stringResource(R.string.night_sleep_score),
            size = 82,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text =
                if (quality?.score == null) stringResource(R.string.night_sleep_score_none)
                else stringResource(R.string.night_sleep_score_value, quality.score?.roundToInt() ?: 0),
            style = MaterialTheme.typography.titleMedium,
        )
      }
      if (quality == null) {
        Text(
            text = stringResource(R.string.night_sleep_score_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
            text = stringResource(R.string.night_sleep_score_formula),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val keys =
            listOf(
                "sleep_efficiency",
                "waso",
                "awakenings_per_hour",
                "sleep_latency",
                "restorative_sleep_share",
            )
        ScoreComponentRows(
            components = quality.components,
            keys = keys,
            label = ::sleepComponentLabel,
        )
        quality.notes.takeIf { it.isNotEmpty() }?.let { notes ->
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          ScoreNotes(notes)
        }
      }
    }
  }
}

@Composable
private fun ScoreComponentRows(
    components: Map<String, QualityComponentDto>,
    keys: List<String>,
    label: (String) -> Int,
) {
  keys.forEach { key ->
    val component = components[key] ?: return@forEach
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text = stringResource(label(key)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                if (component.available && component.value != null) {
                  stringResource(
                      R.string.night_quality_component_value,
                      (component.value * 100).roundToInt(),
                      (component.weight * 100).roundToInt(),
                  )
                } else {
                  stringResource(R.string.night_quality_component_unavailable)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (component.available && component.value != null) {
        Text(
            text = "${(component.value * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

@Composable
private fun ScoreNotes(notes: List<String>) {
  notes.forEach {
    Text(
        text = "• $it",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun dataQualityComponentLabel(key: String): Int =
    when (key) {
      "usable_epoch_coverage" -> R.string.night_quality_component_usable
      "artifact_free_rate" -> R.string.night_quality_component_artifact
      "mean_stage_confidence" -> R.string.night_quality_component_confidence
      "hr_coverage" -> R.string.night_quality_component_hr
      else -> R.string.night_data_quality
    }

private fun sleepComponentLabel(key: String): Int =
    when (key) {
      "sleep_efficiency" -> R.string.sleep_score_component_efficiency
      "waso" -> R.string.sleep_score_component_waso
      "awakenings_per_hour" -> R.string.sleep_score_component_awakenings
      "sleep_latency" -> R.string.sleep_score_component_latency
      "restorative_sleep_share" -> R.string.sleep_score_component_restorative
      else -> R.string.night_sleep_score
    }

private fun startOf(state: NightDetailState): Long =
    state.startUtcMs.takeIf { it > 0 }
        ?: state.analysis?.result?.stages?.start_unix_ms
        ?: 0L

private fun segmentsOf(state: NightDetailState): List<StageSegmentDto> =
    state.analysis?.result?.stages?.segments ?: state.segments

private fun epochSecondsOf(state: NightDetailState): Int =
    state.analysis?.result?.stages?.epoch_seconds ?: state.epochSeconds
