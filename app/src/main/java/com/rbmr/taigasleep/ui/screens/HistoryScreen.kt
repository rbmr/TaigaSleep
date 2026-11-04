package com.rbmr.taigasleep.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.night.Night
import com.rbmr.taigasleep.night.NightRepository
import com.rbmr.taigasleep.night.NightStatus
import com.rbmr.taigasleep.night.formatDuration
import com.rbmr.taigasleep.night.formatNightDateAndTime
import com.rbmr.taigasleep.recording.Exporter
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.remote.RemoteStatus
import com.rbmr.taigasleep.ui.components.ScoreBadge
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SectionLabel
import com.rbmr.taigasleep.ui.components.StageBar
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TertiaryButton
import com.rbmr.taigasleep.upload.PendingDeleteStore
import com.rbmr.taigasleep.upload.RawRetention
import com.rbmr.taigasleep.upload.RecordingDeleter
import com.rbmr.taigasleep.upload.UploadCoordinator
import com.rbmr.taigasleep.upload.UploadFailure
import com.rbmr.taigasleep.upload.UploadScheduler
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    onOpenNight: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
    onLoginClick: () -> Unit,
) {
  val context = LocalContext.current
  val repository = remember { NightRepository.of(context) }
  val pendingDeleteStore = remember { PendingDeleteStore.of(context) }
  val pendingDeletes by pendingDeleteStore.pending.collectAsStateWithLifecycle()
  val remoteStatus by repository.remoteStatus.collectAsStateWithLifecycle()
  val nights by repository.nights.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  var refreshKey by remember { mutableIntStateOf(0) }
  var actionError by remember { mutableStateOf<String?>(null) }
  var deleteCandidate by remember { mutableStateOf<Night?>(null) }

  LaunchedEffect(Unit) {
    RecordingRepository.refreshSessions(context)
    UploadScheduler.resumePendingDeletes(context)
    UploadScheduler.reconcile(context)
    RawRetention.enforce(context)
    repository.reload()
  }
  LifecycleResumeEffect(Unit) {
    RecordingRepository.refreshSessions(context)
    repository.reload()
    refreshKey++
    onPauseOrDispose {}
  }
  LaunchedEffect(remoteStatus) { repository.reload() }
  LaunchedEffect(remoteStatus, refreshKey) {
    if (remoteStatus !is RemoteStatus.Connected) {
      return@LaunchedEffect
    }
    repository.refreshList()
  }
  // Results are small. Fetch any finished result whose full payload is not on this phone yet.
  LaunchedEffect(nights, remoteStatus) {
    if (remoteStatus !is RemoteStatus.Connected) return@LaunchedEffect
    for (night in nights) {
      val server = night.remoteId ?: continue
      if (!night.isDone || night.resultDownloaded) continue
      UploadScheduler.enqueuePoll(
          context,
          night.localId ?: night.key,
          server,
          night.analysisId,
          night.jobId,
      )
    }
  }

  val items =
      remember(nights, pendingDeletes) {
        nights.filterNot { it.localId == null && it.remoteId in pendingDeletes }
      }
  val connected = remoteStatus is RemoteStatus.Connected

  fun upload(item: Night) {
    if (item.isRecording) return
    val localId = item.localId ?: return
    if (!connected) {
      onLoginClick()
      return
    }
    actionError = null
    UploadScheduler.enqueueManual(context, localId)
  }

  fun export(item: Night) {
    val localId = item.localId ?: return
    scope.launch { Exporter.share(context, localId) }
  }

  fun retry(item: Night) {
    if (item.isRecording) return
    if (!connected) {
      onLoginClick()
      return
    }
    actionError = null
    val localId = item.localId
    val remoteId = item.remoteId
    if (item.status == NightStatus.PROCESSING) {
      if (remoteId != null) {
        UploadScheduler.enqueuePoll(
            context,
            localId ?: item.key,
            remoteId,
            item.analysisId,
            item.jobId,
            replace = true,
        )
      } else {
        refreshKey++
      }
      return
    }
    if (item.status == NightStatus.FAILED &&
        remoteId != null &&
        (item.analysisId != null || item.failure == UploadFailure.ANALYSIS)) {
      scope.launch {
        val result =
            UploadCoordinator.retryAnalysis(
                context,
                item.key,
                remoteId,
                item.analysisId,
                item.jobId,
            )
        result.onSuccess { refreshKey++ }.onFailure { actionError = it.message }
      }
      return
    }
    if (localId != null) upload(item) else refreshKey++
  }

  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ScreenHeader(
          kicker = stringResource(R.string.history_kicker),
          title = stringResource(R.string.history_title),
          subtitle = stringResource(R.string.history_subtitle),
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
      )
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

    if (items.isEmpty()) {
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.history_empty_title),
              style = MaterialTheme.typography.titleLarge,
          )
          Spacer(Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.history_empty_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      item { SectionLabel(stringResource(R.string.history_nights_label)) }
      items(items, key = { it.key }) { item ->
        NightRow(
            item = item,
            connected = connected,
            onOpen = { onOpenNight(item.localId, item.remoteId, item.analysisId) },
            onDownload = {
              val remoteId = item.remoteId
              if (remoteId != null) {
                UploadScheduler.enqueuePoll(
                    context,
                    item.localId ?: item.key,
                    remoteId,
                    item.analysisId,
                    item.jobId,
                    replace = true,
                )
              }
            },
            onAction = {
              when {
                item.status == NightStatus.LOCAL_ONLY -> upload(item)
                item.status == NightStatus.PROCESSING -> retry(item)
                item.status == NightStatus.FAILED -> retry(item)
                item.status == NightStatus.QUEUED || item.status == NightStatus.UPLOADING ->
                    retry(item)
                else -> Unit
              }
            },
            onDelete = { deleteCandidate = item },
            onExport = { export(item) },
        )
      }
    }
  }

  deleteCandidate?.let { item ->
    AlertDialog(
        onDismissRequest = { deleteCandidate = null },
        title = { Text(stringResource(R.string.night_delete_title)) },
        text = { Text(stringResource(R.string.night_delete_body)) },
        confirmButton = {
          TextButton(
              onClick = {
                deleteCandidate = null
                RecordingDeleter.delete(context, item.localId, item.remoteId)
              }
          ) {
            Text(stringResource(R.string.night_delete_confirm))
          }
        },
        dismissButton = {
          TextButton(onClick = { deleteCandidate = null }) {
            Text(stringResource(R.string.night_cancel))
          }
        },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NightRow(
    item: Night,
    connected: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onAction: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
  var actionsOpen by remember { mutableStateOf(false) }
  Box {
    TaigaCard(
        modifier =
            Modifier.combinedClickable(
                onClickLabel = null,
                onLongClickLabel =
                    if (item.isRecording) null else stringResource(R.string.night_more),
                onLongClick = if (item.isRecording) null else { { actionsOpen = true } },
                onClick = onOpen,
            )
    ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text = formatNightDateAndTime(item.startUtcMs),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "${item.device} · ${formatDuration(item.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (item.status == NightStatus.READY) {
        ScoreBadge(
            score = item.sleepScore,
            label = stringResource(R.string.history_sleep_score_label),
            emptyLabel = stringResource(R.string.history_no_sleep_score),
        )
      } else {
        Text(
            text = rowStatus(item),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (item.status == NightStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (item.status == NightStatus.READY) {
      Spacer(Modifier.height(12.dp))
      StageBar(item.segments)
      if (!item.resultDownloaded && item.remoteId != null) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              text = stringResource(R.string.history_result_not_downloaded),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
          )
          TertiaryButton(
              text = stringResource(R.string.history_action_download),
              onClick = onDownload,
          )
        }
      }
    } else {
      if (item.isRecording) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.history_recording),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        return@TaigaCard
      }
      if (item.status == NightStatus.UPLOADING) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { item.progress },
            modifier = Modifier.fillMaxWidth().height(5.dp),
        )
      } else if (item.status == NightStatus.QUEUED || item.status == NightStatus.PROCESSING) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
      }
      item.errorDetail
          ?.takeIf { item.status == NightStatus.FAILED || item.status == NightStatus.PROCESSING }
          ?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text =
                when {
                  item.status == NightStatus.LOCAL_ONLY && !connected ->
                      stringResource(R.string.history_login_to_upload)
                  item.status == NightStatus.LOCAL_ONLY -> stringResource(R.string.history_local_only)
                  else -> pendingStatus(item)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TertiaryButton(
            text =
                stringResource(
                    when {
                      item.status == NightStatus.LOCAL_ONLY && !connected -> R.string.history_action_login
                      item.status == NightStatus.LOCAL_ONLY -> R.string.history_action_upload
                      item.status == NightStatus.PROCESSING -> R.string.history_action_check
                      else -> R.string.history_action_retry
                    }
                ),
            onClick = onAction,
        )
      }
    }
  }
    DropdownMenu(
        expanded = actionsOpen,
        onDismissRequest = { actionsOpen = false },
    ) {
      DropdownMenuItem(
          text = { Text(stringResource(R.string.night_export)) },
          enabled = item.local?.hasRaw == true,
          onClick = {
            actionsOpen = false
            onExport()
          },
      )
      DropdownMenuItem(
          text = { Text(stringResource(R.string.night_delete)) },
          onClick = {
            actionsOpen = false
            onDelete()
          },
      )
    }
  }
}

@Composable
private fun pendingStatus(item: Night): String =
    if (item.isRecording) {
      stringResource(R.string.history_recording)
    } else {
      when (item.status) {
        NightStatus.RECORDING -> stringResource(R.string.history_recording)
        NightStatus.LOCAL_ONLY -> stringResource(R.string.history_local_only)
        NightStatus.QUEUED -> stringResource(R.string.history_queued)
        NightStatus.UPLOADING ->
            stringResource(R.string.history_uploading, (item.progress * 100).toInt().coerceIn(0, 100))
        NightStatus.PROCESSING ->
            if (item.failure != null) uploadFailureText(item.failure)
            else stringResource(R.string.history_processing)
        NightStatus.FAILED -> uploadFailureText(item.failure)
        NightStatus.READY -> stringResource(R.string.history_status_all_title)
      }
    }

@Composable
private fun rowStatus(item: Night): String =
    if (item.isRecording) {
      stringResource(R.string.history_recording)
    } else {
      when (item.status) {
        NightStatus.RECORDING -> stringResource(R.string.history_recording)
        NightStatus.LOCAL_ONLY -> stringResource(R.string.history_local_only)
        NightStatus.QUEUED -> stringResource(R.string.history_queued)
        NightStatus.UPLOADING ->
            stringResource(R.string.history_uploading, (item.progress * 100).toInt().coerceIn(0, 100))
        NightStatus.PROCESSING ->
            if (item.failure != null) uploadFailureText(item.failure)
            else stringResource(R.string.history_processing)
        NightStatus.FAILED -> uploadFailureText(item.failure)
        NightStatus.READY -> stringResource(R.string.history_status_all_title)
      }
    }

@Composable
private fun uploadFailureText(failure: UploadFailure?): String =
    stringResource(
        when (failure) {
          UploadFailure.LOGIN -> R.string.upload_error_login
          UploadFailure.NETWORK -> R.string.upload_error_network
          UploadFailure.SERVER -> R.string.upload_error_server
          UploadFailure.STORAGE -> R.string.upload_error_storage
          UploadFailure.ANALYSIS -> R.string.upload_error_analysis
          UploadFailure.RECORDING -> R.string.upload_error_recording
          UploadFailure.VERSION -> R.string.upload_error_version
          null -> R.string.upload_error_generic
        }
    )
