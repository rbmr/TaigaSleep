package com.rbmr.taigasleep.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.library.formatDuration
import com.rbmr.taigasleep.library.formatRecordingDateAndTime
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.ui.components.ScoreBadge
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SectionLabel
import com.rbmr.taigasleep.ui.components.StageBar
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.history.HistoryUiState
import com.rbmr.taigasleep.ui.history.HistoryViewModel

/**
 * The list shows what the user has: one line of status, one result summary, and nothing to operate.
 * Opening a recording is the only action here; retry, export and delete live in the recording screen.
 *
 * The screen only draws the state its view model holds (see [HistoryViewModel]).
 */
@Composable
fun HistoryScreen(
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
) {
  val context = LocalContext.current
  val factory = remember { HistoryViewModel.Factory(context) }
  val viewModel: HistoryViewModel = viewModel(factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { viewModel.onOpen() }
  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }

  HistoryScreen(state = state, onOpenRecording = onOpenRecording)
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
) {
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

    if (state.items.isEmpty()) {
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
      items(state.items, key = { it.key }) { item ->
        RecordingRow(
            item = item,
            onOpen = { onOpenRecording(item.localId, item.remoteId, item.analysisId) },
        )
      }
    }
  }
}

@Composable
private fun RecordingRow(item: RecordingView, onOpen: () -> Unit) {
  val titleMs =
      if (item.isRecording) item.startUtcMs else item.endUtcMs ?: item.startUtcMs
  val device = item.device.ifBlank { stringResource(R.string.night_device_unknown) }
  TaigaCard(
      modifier =
          Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable(onClick = onOpen)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text = formatRecordingDateAndTime(titleMs),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "$device · ${formatDuration(item.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (item.status == RecordingStatus.READY) {
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
                when {
                  item.status == RecordingStatus.FAILED -> MaterialTheme.colorScheme.error
                  item.isRecording -> MaterialTheme.colorScheme.primary
                  else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
      }
    }

    if (item.status == RecordingStatus.READY) {
      if (item.segments.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        StageBar(item.segments)
      }
    } else if (item.status == RecordingStatus.UPLOADING) {
      Spacer(Modifier.height(10.dp))
      LinearProgressIndicator(
          progress = { item.progress },
          modifier = Modifier.fillMaxWidth().height(5.dp),
      )
    } else if (item.status == RecordingStatus.QUEUED || item.status == RecordingStatus.PROCESSING) {
      Spacer(Modifier.height(10.dp))
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
    }
  }
}

@Composable
private fun rowStatus(item: RecordingView): String =
    when (item.status) {
      RecordingStatus.RECORDING -> stringResource(R.string.history_recording)
      RecordingStatus.LOCAL_ONLY -> stringResource(R.string.history_local_only)
      RecordingStatus.QUEUED -> stringResource(R.string.history_queued)
      RecordingStatus.UPLOADING ->
          stringResource(R.string.history_uploading, (item.progress * 100).toInt().coerceIn(0, 100))
      RecordingStatus.PROCESSING -> stringResource(R.string.history_processing)
      RecordingStatus.FAILED ->
          stringResource(
              when (item.failure) {
                UploadFailure.ANALYSIS -> R.string.history_status_analysis_failed
                UploadFailure.LOGIN -> R.string.history_status_login
                UploadFailure.VERSION -> R.string.history_status_update
                else -> R.string.history_status_upload_failed
              }
          )
      RecordingStatus.READY -> stringResource(R.string.history_status_ready)
    }
