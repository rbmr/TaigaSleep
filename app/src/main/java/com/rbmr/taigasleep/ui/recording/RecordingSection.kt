package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.ui.components.joinParts
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.formatDuration
import com.rbmr.taigasleep.library.formatRecordingDateAndTime
import com.rbmr.taigasleep.recording.store.StreamId
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ProgressTrack
import com.rbmr.taigasleep.ui.components.ProgressTrackState
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The recording itself: where its bytes are, what it holds and what can be done with it. It takes
 * the place of the old details sheet and of the overflow menu above it.
 */
@Composable
internal fun RecordingSection(
    state: RecordingDetailUiState,
    onExport: () -> Unit,
    onDownloadRaw: () -> Unit,
    onDeleteRaw: () -> Unit,
    onDelete: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  val context = LocalContext.current
  val local = state.local
  val uploaded = state.remoteId != null
  Section(title = stringResource(R.string.night_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(space.m)) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        ListRow(
            title = stringResource(R.string.night_details_device),
            value = state.detailsDevice.ifBlank { stringResource(R.string.night_device_unknown) },
            trailing = ListRowTrailing.None,
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_details_started),
            value = formatRecordingDateAndTime(state.startUtcMs),
            trailing = ListRowTrailing.None,
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_details_stopped),
            value =
                state.endUtcMs?.takeIf { it > 0 }?.let { formatRecordingDateAndTime(it) }
                    ?: stringResource(R.string.night_value_none),
            trailing = ListRowTrailing.None,
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_details_duration),
            value = formatDuration(state.durationMs),
            trailing = ListRowTrailing.None,
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_details_streams),
            value = streamsText(state.rawStreams),
            trailing = ListRowTrailing.None,
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_details_raw),
            subtitle =
                stringResource(
                    if (local?.hasRaw == true) R.string.night_details_on_phone
                    else R.string.night_details_not_on_phone
                ),
            value =
                if (local?.hasRaw == true) {
                  Formatter.formatShortFileSize(context, state.rawSizeBytes)
                } else {
                  null
                },
            // A raw copy may only leave the phone while the server has the recording, and only a
            // recording kept on this phone can take it back. A recording on this phone alone has
            // no raw-data action at all.
            trailing =
                when {
                  local?.hasRaw == true && uploaded ->
                      ListRowTrailing.Action(
                          label = stringResource(R.string.night_raw_delete_action),
                          onClick = onDeleteRaw,
                          enabled = !state.exportBusy,
                      )
                  local != null && uploaded ->
                      ListRowTrailing.Action(
                          label = stringResource(R.string.night_details_download_raw),
                          onClick = onDownloadRaw,
                          enabled = !state.exportBusy,
                      )
                  else -> ListRowTrailing.None
                },
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_details_server),
            subtitle =
                state.remoteCreatedAtMs
                    ?.takeIf { uploaded }
                    ?.let { formatRecordingDateAndTime(it) },
            trailing =
                ListRowTrailing.Pill(
                    label =
                        stringResource(
                            if (uploaded) R.string.night_details_uploaded
                            else R.string.night_details_not_uploaded
                        ),
                    state = if (uploaded) StatusPillState.Ok else StatusPillState.Neutral,
                ),
        )
      }
      if (state.exportBusy) {
        ExportProgress(state = state)
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(space.s),
      ) {
        PrimaryButton(
            text = stringResource(R.string.night_export_open),
            onClick = onExport,
            enabled = state.canExport && !state.exportBusy,
            size = ButtonSize.Regular,
            modifier = Modifier.weight(1f),
        )
        DestructiveButton(
            text = stringResource(R.string.night_delete_recording),
            onClick = onDelete,
            enabled = !state.exportBusy,
            modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

/** What a running export reports: a percentage when it is large, otherwise that work is happening. */
@Composable
private fun ExportProgress(state: RecordingDetailUiState) {
  val context = LocalContext.current
  val percent = ((state.exportProgress ?: 0f) * 100).toInt().coerceIn(0, 100)
  val phase =
      when (state.exportPhase) {
        ExportPhase.DOWNLOADING -> stringResource(R.string.night_details_downloading, percent)
        ExportPhase.ZIPPING ->
            if (state.exportProgress == null) {
              stringResource(R.string.night_details_preparing)
            } else {
              stringResource(R.string.night_details_zipping, percent)
            }
        null -> stringResource(R.string.night_details_preparing)
      }
  Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.xs)) {
    ProgressTrack(
        state =
            state.exportProgress
                ?.let { ProgressTrackState.Determinate(it) }
                ?: ProgressTrackState.Indeterminate,
        description = phase,
    )
    Text(
        text = phase,
        style = MaterialTheme.taiga.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.exportPhase == ExportPhase.ZIPPING && state.exportProgress != null) {
      Text(
          text =
              stringResource(
                  R.string.night_details_zipping_bytes,
                  Formatter.formatShortFileSize(context, state.exportDoneBytes),
                  Formatter.formatShortFileSize(context, state.exportTotalBytes),
              ),
          style = MaterialTheme.taiga.text.meta,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The streams of the recording, or a dash when they are not on this phone. */
@Composable
private fun streamsText(streams: List<Int>): String =
    if (streams.isEmpty()) {
      stringResource(R.string.night_value_none)
    } else {
      joinParts(streams.map { streamLabel(it) }).orEmpty()
    }

@Composable
private fun streamLabel(stream: Int): String =
    stringResource(
        when (stream) {
          StreamId.ECG -> R.string.rec_active_ecg
          StreamId.ACC -> R.string.rec_active_acc
          else -> R.string.rec_active_hr
        }
    )
