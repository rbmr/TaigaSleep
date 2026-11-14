package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.formatDuration
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.RecordingFormat
import com.rbmr.taigasleep.recording.RecordingState
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.StatusPill
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The status card of a running recording: the tracker and how it is connected, its battery, and
 * while the streams flow, one chip per stream that says whether its data looks reasonable. A
 * recording the tracker stores itself says where the recording is instead.
 */
@Composable
internal fun RecordingCard(status: CaptureStatus) {
  val state = status.state
  val colors = MaterialTheme.taiga
  val onDevice = !status.liveStreaming
  val (pillState, pillLabel) =
      when {
        onDevice ->
            StatusPillState.Neutral to stringResource(R.string.rec_status_on_tracker)
        state.link.state == RecordingState.LinkState.CONNECTED ->
            StatusPillState.Ok to stringResource(R.string.rec_connection_connected)
        else ->
            StatusPillState.Busy to stringResource(R.string.rec_status_reconnecting)
      }
  TaigaCard {
    Column(verticalArrangement = Arrangement.spacedBy(colors.space.s)) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(colors.space.s),
      ) {
        Text(
            text = status.sensorName.orEmpty(),
            style = MaterialTheme.taiga.text.row,
            modifier = Modifier.weight(1f),
        )
        StatusPill(state = pillState, label = pillLabel)
      }
      state.power.percent?.let {
        Text(
            text = stringResource(R.string.rec_battery, it),
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (onDevice) {
        Text(
            text = stringResource(R.string.rec_active_on_device),
            style = MaterialTheme.taiga.text.body,
        )
      } else {
        StreamChips(status)
      }
    }
  }
}

/**
 * One chip per chosen kind of data, in a fixed order. Every state has a visible chip, so the card
 * never shows a gap between waiting and ok, and the chips keep one height; they only wrap when a
 * problem makes the labels longer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamChips(status: CaptureStatus) {
  val state = status.state
  val config = status.config
  FlowRow(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.s),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.s),
  ) {
    if (config?.hr != false) HeartChip(state)
    if (config?.ecg == true) {
      StreamChip(
          label = stringResource(R.string.rec_active_ecg),
          stream = state.streams[RecordingState.StreamKind.ECG],
          poor = state.quality.ecg == RecordingState.EcgQuality.POOR,
      )
    }
    if (config?.acc == true) {
      StreamChip(
          label = stringResource(R.string.rec_active_acc),
          stream = state.streams[RecordingState.StreamKind.ACC],
      )
    }
  }
}

/** The heart rate as a measurement, or the reason there is none to judge. */
@Composable
private fun HeartChip(state: RecordingState) {
  val offBody = state.contact.state == RecordingState.ContactState.OFF_BODY
  val bpm = state.heart.bpm
  val stalled = state.streams[RecordingState.StreamKind.HR]?.status == RecordingState.StreamStatus.STALE
  when {
    offBody ->
        StatusPill(
            state = StatusPillState.Warn,
            label = stringResource(R.string.rec_active_no_contact),
        )
    stalled ->
        StatusPill(
            state = StatusPillState.Warn,
            label = stringResource(R.string.rec_status_stalled, stringResource(R.string.rec_active_hr)),
        )
    bpm != null ->
        StatusPill(
            state = StatusPillState.Ok,
            label = stringResource(R.string.rec_active_hr_value, bpm),
        )
    else ->
        StatusPill(
            state = StatusPillState.Checking,
            label = stringResource(R.string.rec_active_hr),
        )
  }
}

/** One stream's chip: ok when its samples arrive, otherwise what is wrong with them. */
@Composable
private fun StreamChip(
    label: String,
    stream: RecordingState.Stream?,
    poor: Boolean = false,
) {
  val (pill, text) =
      when {
        stream?.status == RecordingState.StreamStatus.STALE ->
            StatusPillState.Warn to stringResource(R.string.rec_status_stalled, label)
        poor -> StatusPillState.Warn to stringResource(R.string.rec_status_poor, label)
        stream == null || stream.status == RecordingState.StreamStatus.WAITING ->
            StatusPillState.Checking to label
        else -> StatusPillState.Ok to stringResource(R.string.rec_status_ok, label)
      }
  StatusPill(state = pill, label = text)
}

/** The same fixed priority headline the notification shows, formatted for the active screen. */
@Composable
internal fun OverallBanner(status: CaptureStatus) {
  val state = status.state
  val reason = state.overall.reason ?: return
  val kind =
      if (state.overall.state == RecordingState.OverallState.ERROR) StatusKind.Bad
      else StatusKind.Warn
  val stale =
      state.streams.values.firstOrNull { it.status == RecordingState.StreamStatus.STALE }
  var title = ""
  var body: String? = null
  when (reason) {
    RecordingState.Reason.STORAGE_ERROR -> {
      title = stringResource(R.string.rec_active_storage_error_title)
      body = state.storage.lastError?.let { stringResource(R.string.rec_active_storage_error_body, it) }
    }
    RecordingState.Reason.STORAGE_LOW -> {
      title = stringResource(R.string.rec_active_storage_low_title)
      body = stringResource(R.string.rec_active_storage_low_body)
    }
    RecordingState.Reason.LINK_LOST -> {
      title = stringResource(R.string.rec_active_link_lost_title)
      body = stringResource(R.string.rec_active_link_lost_body)
    }
    RecordingState.Reason.OFF_BODY -> {
      title = stringResource(R.string.rec_active_off_body_title)
      body =
          stringResource(
              R.string.rec_active_off_body_body,
              formatDuration(state.contact.offBodyMs),
          )
    }
    RecordingState.Reason.STREAM_STALE -> {
      title =
          stringResource(
              R.string.rec_active_stream_stalled_title,
              streamLabel(stale?.kind ?: RecordingState.StreamKind.HR),
          )
      body =
          stringResource(
              R.string.rec_active_stream_stalled_body,
              RecordingFormat.age(stale?.lastSampleAgeMs ?: 0),
          )
    }
    RecordingState.Reason.ECG_POOR -> {
      title = stringResource(R.string.rec_active_ecg_poor_title)
      body = stringResource(R.string.rec_active_ecg_poor_body)
    }
    RecordingState.Reason.BATTERY_LOW -> {
      title = stringResource(R.string.rec_active_battery_low_title)
      body = stringResource(R.string.rec_active_battery_low_body, state.power.percent ?: 0)
    }
  }
  StatusBanner(kind = kind, title = title, body = body)
}

@Composable
private fun streamLabel(kind: RecordingState.StreamKind): String =
    stringResource(
        when (kind) {
          RecordingState.StreamKind.HR -> R.string.rec_active_hr
          RecordingState.StreamKind.ECG -> R.string.rec_active_ecg
          RecordingState.StreamKind.ACC -> R.string.rec_active_acc
        }
    )
