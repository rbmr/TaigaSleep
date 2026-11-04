package com.rbmr.taigasleep.ui.screens

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.rbmr.taigasleep.night.Night
import com.rbmr.taigasleep.night.NightRepository
import com.rbmr.taigasleep.night.NightStatus
import com.rbmr.taigasleep.night.formatNightDate
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.QualityComponentDto
import com.rbmr.taigasleep.remote.RemoteStatus
import com.rbmr.taigasleep.remote.SessionDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.recording.Exporter
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.ui.components.ButtonSize
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
    val remote: SessionDto? = null,
    val analysis: AnalysisDto? = null,
    val remoteId: String? = null,
    val analysisId: String? = null,
    val jobId: String? = null,
    val status: NightStatus = NightStatus.LOCAL_ONLY,
    val progress: Float = 0f,
    val startUtcMs: Long = 0,
    val segments: List<StageSegmentDto> = emptyList(),
    val epochSeconds: Int = 30,
    val failure: UploadFailure? = null,
    val errorDetail: String? = null,
    val warning: String? = null,
    val loading: Boolean = true,
    val offlineSaved: Boolean = false,
    val error: String? = null,
)

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
        jobId = jobId,
        status = status,
        progress = progress,
        startUtcMs = startUtcMs,
        segments = segments,
        epochSeconds = epochSeconds,
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
  var showMenu by remember { mutableStateOf(false) }
  var showDelete by remember { mutableStateOf(false) }
  var showRawDelete by remember { mutableStateOf(false) }
  var rawBusy by remember { mutableStateOf(false) }
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
          if (fresh != null && (fresh.status == "done" || result == null)) result = fresh
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
  // cached result as soon as the selected analysis is known, without waiting for the network.
  LaunchedEffect(key, night?.analysisId) {
    val selected = night?.analysisId
    if (selected == null) return@LaunchedEffect
    val cached = repository.cachedResult(key, selected)
    if (cached != null) {
      result = cached
    } else if (result?.id != selected) {
      result = null
    }
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
    val id = state.local?.id ?: localId ?: state.remoteId?.let { "remote-$it" }
    val server = state.remoteId
    if (id != null && server != null) {
      UploadScheduler.enqueuePoll(
          context,
          id,
          server,
          state.analysisId,
          state.jobId,
          replace = true,
      )
    }
    refreshKey++
  }

  fun retryAnalysis() {
    val key = state.local?.id ?: state.remoteId?.let { "remote-$it" } ?: localId ?: return
    val server = state.remoteId ?: return
    actionError = null
    scope.launch {
      val result =
          UploadCoordinator.retryAnalysis(
              context,
              key,
              server,
              state.analysisId,
              state.jobId,
          )
      result.onSuccess { refreshKey++ }.onFailure { actionError = it.message }
    }
  }

  fun downloadRaw() {
    val id = state.local?.id ?: localId ?: return
    val server = state.remoteId ?: return
    if (rawBusy) return
    actionError = null
    rawBusy = true
    scope.launch {
      val result = RawRetention.restore(context, id, server)
      rawBusy = false
      result
          .onSuccess { refreshKey++ }
          .onFailure {
            actionError =
                listOfNotNull(rawRestoreFailed, it.message?.takeIf { message -> message.isNotBlank() })
                    .joinToString(": ")
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
        RawActionResult.Recording -> actionError = rawNotUploaded
        is RawActionResult.Failed -> actionError = rawDeleteFailed
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
              IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.night_more),
                )
              }
              DropdownMenu(
                  expanded = showMenu,
                  onDismissRequest = { showMenu = false },
              ) {
                val rawId = state.local?.id ?: localId
                val rawOnPhone = state.local?.hasRaw == true
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.night_export)) },
                    enabled = rawId != null && rawOnPhone,
                    onClick = {
                      showMenu = false
                      if (rawId != null) {
                        scope.launch { Exporter.share(context, rawId) }
                      }
                    },
                )
                if (rawId != null && !rawOnPhone && state.remoteId != null) {
                  DropdownMenuItem(
                      text = {
                        Text(
                            stringResource(
                                if (rawBusy) R.string.night_raw_downloading
                                else R.string.night_raw_download
                            )
                        )
                      },
                      enabled = !rawBusy,
                      onClick = {
                        showMenu = false
                        downloadRaw()
                      },
                  )
                }
                if (rawId != null && rawOnPhone) {
                  DropdownMenuItem(
                      text = { Text(stringResource(R.string.night_raw_delete)) },
                      onClick = {
                        showMenu = false
                        showRawDelete = true
                      },
                  )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.night_delete)) },
                    onClick = {
                      showMenu = false
                      showDelete = true
                    },
                )
              }
            },
        )
      },
  ) { padding ->
    val start = startOf(state)
    val device = state.local?.sensorName ?: state.remote?.device.orEmpty()
    val analysis = state.analysis
    val sleepScore = analysis?.sleep_quality?.score
    val processed = analysis?.status == "done"
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

      state.local?.takeIf { !it.hasRaw && state.remoteId != null }?.let {
        item {
          Text(
              text = stringResource(R.string.night_raw_gone),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 4.dp),
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
        analysis?.status == "done" -> {
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
          state.analysis?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
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
          state.analysis?.model?.takeIf { it.isNotBlank() }?.let { model ->
            item {
              Text(
                  text =
                      stringResource(
                          R.string.night_analyzed_with,
                          model,
                          state.analysis?.model_version.orEmpty(),
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
          analysis = state.analysis,
          onDismiss = { showQuality = false },
      )
    }
    if (showSleepScore) {
      SleepScoreSheet(
          analysis = state.analysis,
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
                          ?: state.analysis?.stages?.start_unix_ms
                          ?: 0L,
                  support = state.analysis?.metric_support,
                  modifier = chartModifier,
                  fullscreen = true,
              )
          else ->
              state.analysis?.hr?.let { series ->
                HeartRateChart(series = series, modifier = chartModifier, fullscreen = true)
              }
        }
      }
    }
    selectedMetricSpec?.let { spec ->
      MetricSheet(spec = spec, onDismiss = { metric = null })
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
                if (processed) MaterialTheme.taiga.ok else MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SleepStatsCard(
    state: NightDetailState,
    start: Long,
    analysis: AnalysisDto,
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
    analysis: AnalysisDto,
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
    analysis: AnalysisDto,
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
    analysis: AnalysisDto,
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
    analysis: AnalysisDto,
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
private fun DataQualitySheet(analysis: AnalysisDto?, onDismiss: () -> Unit) {
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
private fun SleepScoreSheet(analysis: AnalysisDto?, onDismiss: () -> Unit) {
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
        ?: state.analysis?.stages?.start_unix_ms
        ?: 0L

private fun segmentsOf(state: NightDetailState): List<StageSegmentDto> =
    state.analysis?.stages?.segments ?: state.segments

private fun epochSecondsOf(state: NightDetailState): Int =
    state.analysis?.stages?.epoch_seconds ?: state.epochSeconds
