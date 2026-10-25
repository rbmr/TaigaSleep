package com.rbmr.taigasleep.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.rbmr.taigasleep.analysis.MetricCatalog
import com.rbmr.taigasleep.analysis.MetricSpec
import com.rbmr.taigasleep.analysis.MetricSupportState
import com.rbmr.taigasleep.analysis.formatMetric
import com.rbmr.taigasleep.analysis.supportState
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.RemoteStatus
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.SessionDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.recording.Exporter
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionStore
import com.rbmr.taigasleep.ui.formatDuration
import com.rbmr.taigasleep.ui.formatNightDate
import com.rbmr.taigasleep.ui.parsePocketBaseDate
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.SleepStagesChart
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.QualityRing
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StageBreakdown
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TertiaryButton
import com.rbmr.taigasleep.ui.theme.taiga
import com.rbmr.taigasleep.upload.RecordingDeleter
import com.rbmr.taigasleep.upload.ResultCache
import com.rbmr.taigasleep.upload.UploadCoordinator
import com.rbmr.taigasleep.upload.UploadFailure
import com.rbmr.taigasleep.upload.UploadPhase
import com.rbmr.taigasleep.upload.UploadRecord
import com.rbmr.taigasleep.upload.UploadScheduler
import com.rbmr.taigasleep.upload.UploadStore
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NightDetailState(
    val local: SessionInfo? = null,
    val remote: SessionDto? = null,
    val analysis: AnalysisDto? = null,
    val record: UploadRecord? = null,
    val remoteId: String? = null,
    val analysisId: String? = null,
    val jobId: String? = null,
    val phase: UploadPhase = UploadPhase.LOCAL,
    val failure: UploadFailure? = null,
    val errorDetail: String? = null,
    val warning: String? = null,
    val loading: Boolean = true,
    val offlineSaved: Boolean = false,
    val error: String? = null,
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
  val remote = remember { RemoteRepository.of(context) }
  val store = remember { UploadStore.of(context) }
  val cache = remember { ResultCache.of(context) }
  val uploads by store.records.collectAsStateWithLifecycle()
  val remoteStatus by remote.status.collectAsStateWithLifecycle()
  val record = localId?.let { uploads[it] } ?: remoteId?.let { uploads["remote-$it"] }
  val scope = rememberCoroutineScope()

  var state by remember { mutableStateOf(NightDetailState()) }
  var refreshKey by remember { mutableIntStateOf(0) }
  var showQuality by rememberSaveable { mutableStateOf(false) }
  var metric by rememberSaveable { mutableStateOf<String?>(null) }
  var actionError by remember { mutableStateOf<String?>(null) }
  var showMenu by remember { mutableStateOf(false) }
  var showDelete by remember { mutableStateOf(false) }
  val loadFailed = stringResource(R.string.night_load_failed)
  val loginError = stringResource(R.string.upload_error_login)

  LaunchedEffect(
      localId,
      remoteId,
      analysisId,
      refreshKey,
      record?.phase,
      record?.remoteId,
      record?.analysisId,
      remoteStatus,
  ) {
    state = state.copy(loading = true)
    val local = localId?.let { id -> SessionStore.of(context).info(SessionStore.of(context).dir(id)) }
    val resolvedRemote = remoteId ?: record?.remoteId
    val resolvedAnalysis = analysisId ?: record?.analysisId
    val key = localId ?: resolvedRemote?.let { "remote-$it" } ?: analysisId ?: "night"
    val cached = cache.load(key)
    var next =
        state.copy(
            local = local,
            record = record,
            remoteId = resolvedRemote,
            analysisId = resolvedAnalysis,
            jobId = record?.jobId ?: state.jobId,
            failure = record?.failure,
            errorDetail = record?.errorDetail,
            warning = record?.warning,
            phase = record?.phase ?: UploadPhase.LOCAL,
            analysis = cached,
            loading = false,
            offlineSaved = false,
            error = null,
        )
    if (resolvedRemote == null) {
      state = next
      return@LaunchedEffect
    }

    val connected = remoteStatus is RemoteStatus.Connected
    val api = if (connected) remote.api() else null
    if (api == null) {
      state =
          next.copy(
              phase = if (cached?.status == "done") UploadPhase.DONE else next.phase,
              loading = false,
              offlineSaved = cached != null,
              error = if (local != null) null else loadFailed,
          )
      return@LaunchedEffect
    }

    val result =
        runCatching {
          withContext(Dispatchers.IO) {
            val detail = api.getSession(resolvedRemote)
            val target =
                resolvedAnalysis
                    ?: detail.analyses.filter { it.status == "done" }.maxByOrNull { it.updated }?.id
                    ?: detail.analyses.maxByOrNull { it.created }?.id
            val analysis = target?.let { api.getAnalysis(it) }
            Triple(detail, analysis, target)
          }
        }
    result
        .onSuccess { (detail, analysis, target) ->
          val resolvedJob =
              detail.jobs.firstOrNull { it.analysis == (target ?: next.analysisId) }?.id
                  ?: next.jobId
          if (analysis?.status == "done") {
            val downloaded = cache.save(key, analysis)
            store.markDone(
                id = key,
                remoteId = detail.session.id,
                analysisId = target,
                jobId = resolvedJob,
                model = analysis.model,
                modelVersion = analysis.model_version,
                epochSeconds = analysis.stages?.epoch_seconds ?: 30,
                segments = analysis.stages?.segments.orEmpty(),
                qualityScore = analysis.quality?.score,
                resultDownloaded = downloaded,
            )
          }
          val saved = if (analysis?.status == "done") analysis else cached ?: analysis
          next =
              next.copy(
                  remote = detail.session,
                  analysis = saved,
                  remoteId = detail.session.id,
                  analysisId = target ?: next.analysisId,
                  jobId = resolvedJob,
                  phase =
                      when {
                        analysis?.status == "done" -> UploadPhase.DONE
                        analysis?.status == "failed" -> UploadPhase.FAILED
                        detail.session.upload_status == "uploaded" -> UploadPhase.PROCESSING
                        detail.session.upload_status == "failed" -> UploadPhase.FAILED
                        else -> UploadPhase.UPLOADING
                      },
                  failure =
                      when {
                        analysis?.status == "failed" -> UploadFailure.ANALYSIS
                        detail.session.upload_status == "failed" -> UploadFailure.SERVER
                        else -> next.failure
                      },
                  errorDetail =
                      analysis?.error?.takeIf { it.isNotBlank() }
                          ?: detail.session.upload_error.takeIf { it.isNotBlank() }
                          ?: next.errorDetail,
                  loading = false,
                  offlineSaved = false,
              )
          state = next
        }
        .onFailure { error ->
          val unauthorized = error is TaigaUnauthorizedException
          if (unauthorized) remote.logout()
          val hasSaved = cached?.status == "done"
          state =
              next.copy(
                  analysis = cached,
                  phase =
                      when {
                        hasSaved -> UploadPhase.DONE
                        unauthorized -> UploadPhase.FAILED
                        else -> next.phase
                      },
                  failure = if (unauthorized) UploadFailure.LOGIN else next.failure,
                  errorDetail = if (unauthorized) loginError else next.errorDetail,
                  loading = false,
                  offlineSaved = hasSaved,
                  error = error.message,
              )
        }
  }

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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.night_export)) },
                    enabled = rawId != null,
                    onClick = {
                      showMenu = false
                      if (rawId != null) {
                        scope.launch { Exporter.share(context, rawId) }
                      }
                    },
                )
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
    val duration = durationOf(state)
    val device = state.local?.sensorName ?: state.remote?.device.orEmpty()
    val analysis = state.analysis
    val quality = analysis?.quality?.score
    val processed = analysis?.status == "done"
    val metricSpec = MetricCatalog.firstOrNull { it.key == metric }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        ScreenHeader(
            kicker = stringResource(R.string.night_kicker),
            title = formatNightDate(start),
            subtitle =
                buildString {
                  if (device.isNotBlank()) append(device)
                  if (duration > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatDuration(duration))
                  }
                }.ifBlank { stringResource(R.string.night_device_unknown) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
      }
      item {
        NightHeaderCard(
            quality = quality,
            processed = processed,
            onQualityClick = { if (state.analysis != null) showQuality = true },
        )
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
            TaigaCard {
              Text(
                  text = stringResource(R.string.night_sleep_stages),
                  style = MaterialTheme.typography.titleLarge,
              )
              Spacer(Modifier.height(12.dp))
              SleepStagesChart(
                  segments = segmentsOf(state),
                  epochSeconds = epochSecondsOf(state),
                  startUnixMs =
                      start.takeIf { it > 0 } ?: state.analysis?.stages?.start_unix_ms ?: 0L,
                  support = state.analysis?.metric_support,
              )
            }
          }
          item {
            TaigaCard {
              Text(
                  text = stringResource(R.string.night_stage_breakdown),
                  style = MaterialTheme.typography.titleLarge,
              )
              Spacer(Modifier.height(12.dp))
              StageBreakdown(
                  segments = segmentsOf(state),
                  epochSeconds = epochSecondsOf(state),
                  support = state.analysis?.metric_support,
              )
            }
          }
          item {
            TaigaCard {
              Text(
                  text = stringResource(R.string.night_metrics),
                  style = MaterialTheme.typography.titleLarge,
              )
              Spacer(Modifier.height(10.dp))
              MetricGrid(
                  analysis = analysis,
                  onMetric = { metric = it.key },
              )
            }
          }
          item {
            TaigaCard(modifier = Modifier.clickable { showQuality = true }) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                  Text(
                      text = stringResource(R.string.night_quality),
                      style = MaterialTheme.typography.titleLarge,
                  )
                  Text(
                      text =
                          if (quality == null) stringResource(R.string.night_quality_none)
                          else stringResource(R.string.night_quality_tap),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                QualityRing(score = quality, size = 78)
              }
            }
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
        state.phase == UploadPhase.PROCESSING -> {
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
        state.phase == UploadPhase.QUEUED || state.phase == UploadPhase.UPLOADING -> {
          item {
            TaigaCard {
              Text(
                  text = stringResource(R.string.night_uploading_title),
                  style = MaterialTheme.typography.titleLarge,
              )
              Spacer(Modifier.height(6.dp))
              Text(
                  text =
                      if (state.phase == UploadPhase.UPLOADING) {
                        stringResource(
                            R.string.night_uploading_progress,
                            ((record?.progress ?: state.record?.progress ?: 0f) * 100)
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
              if (state.phase == UploadPhase.UPLOADING) {
                LinearProgressIndicator(
                    progress = { record?.progress ?: state.record?.progress ?: 0f },
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
        state.phase == UploadPhase.FAILED -> {
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
      QualitySheet(
          analysis = state.analysis,
          onDismiss = { showQuality = false },
      )
    }
    metricSpec?.let { spec ->
      MetricSheet(spec = spec, onDismiss = { metric = null })
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
    quality: Double?,
    processed: Boolean,
    onQualityClick: () -> Unit,
) {
  TaigaCard(
      modifier = if (processed) Modifier.clickable(onClick = onQualityClick) else Modifier
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
                if (processed) stringResource(R.string.night_quality_tap)
                else stringResource(R.string.night_header_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (processed) {
        QualityRing(score = quality)
      }
    }
  }
}

@Composable
private fun MetricGrid(
    analysis: AnalysisDto,
    onMetric: (MetricSpec) -> Unit,
) {
  val metrics = analysis.metrics
  val support = analysis.metric_support
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    MetricCatalog.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
              modifier = Modifier.weight(1f),
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
          maxLines = 1,
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
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(spec.titleRes), style = MaterialTheme.typography.headlineSmall)
      Text(
          text = stringResource(spec.explanationRes),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TertiaryButton(text = stringResource(R.string.night_sheet_close), onClick = onDismiss)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySheet(analysis: AnalysisDto?, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = stringResource(R.string.night_quality_breakdown),
          style = MaterialTheme.typography.headlineSmall,
      )
      val quality = analysis?.quality
      Row(verticalAlignment = Alignment.CenterVertically) {
        QualityRing(score = quality?.score, size = 82)
        Spacer(Modifier.width(16.dp))
        Text(
            text =
                if (quality?.score == null) stringResource(R.string.night_quality_none)
                else stringResource(R.string.night_quality_score, quality.score?.roundToInt() ?: 0),
            style = MaterialTheme.typography.titleMedium,
        )
      }
      Text(
          text = stringResource(R.string.night_quality_formula),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      val components = quality?.components.orEmpty()
      val keys = listOf("usable_epoch_coverage", "artifact_free_rate", "mean_stage_confidence", "hr_coverage")
      keys.forEach { key ->
        val component = components[key] ?: return@forEach
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(qualityComponentLabel(key)),
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
      quality?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        notes.forEach {
          Text(
              text = "• $it",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      TertiaryButton(text = stringResource(R.string.night_sheet_close), onClick = onDismiss)
    }
  }
}

private fun qualityComponentLabel(key: String): Int =
    when (key) {
      "usable_epoch_coverage" -> R.string.night_quality_component_usable
      "artifact_free_rate" -> R.string.night_quality_component_artifact
      "mean_stage_confidence" -> R.string.night_quality_component_confidence
      "hr_coverage" -> R.string.night_quality_component_hr
      else -> R.string.night_quality
    }

private fun startOf(state: NightDetailState): Long =
    state.local?.startUtcMs
        ?: parsePocketBaseDate(state.remote?.started_at.orEmpty()).takeIf { it > 0 }
        ?: state.analysis?.stages?.start_unix_ms
        ?: 0L

private fun durationOf(state: NightDetailState): Long =
    state.local?.durationMs?.takeIf { it > 0 }
        ?: state.remote?.duration_s?.times(1000.0)?.toLong()?.takeIf { it > 0 }
        ?: ((state.analysis?.stages?.count ?: 0) * (state.analysis?.stages?.epoch_seconds ?: 0) * 1000L)

private fun segmentsOf(state: NightDetailState): List<StageSegmentDto> =
    state.analysis?.stages?.segments ?: state.record?.segments.orEmpty()

private fun epochSecondsOf(state: NightDetailState): Int =
    state.analysis?.stages?.epoch_seconds ?: state.record?.epochSeconds ?: 30
