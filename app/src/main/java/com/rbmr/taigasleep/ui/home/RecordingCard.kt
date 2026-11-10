package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.recording.RecordingFormat
import com.rbmr.taigasleep.recording.RecordingState
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import kotlinx.coroutines.delay

/** The live numbers of a running recording: time, link, battery, streams and the one thing to fix. */
@Composable
internal fun RecordingCard(status: CaptureStatus) {
  val state = status.state
  // A recording stored on the tracker has no service ticking: count from its start.
  val sinceStartMs by
      produceState(0L, status.startUtcMs, status.liveStreaming) {
        while (!status.liveStreaming) {
          value = (System.currentTimeMillis() - status.startUtcMs).coerceAtLeast(0)
          delay(1000)
        }
      }
  TaigaCard {
    Text(
        text =
            stringResource(
                if (status.phase == Phase.STOPPING) R.string.rec_active_stopping
                else R.string.rec_active_title
            ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = RecordingFormat.elapsed(if (status.liveStreaming) state.capture.elapsedMs else sinceStartMs),
        style = MaterialTheme.typography.displayLarge,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${status.sensorName.orEmpty()} · ${linkText(state.link.state)}",
        style = MaterialTheme.typography.bodyLarge,
    )
    state.power.percent?.let {
      Text(
          text = stringResource(R.string.rec_battery, it),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (!status.liveStreaming) {
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.rec_active_offline),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@TaigaCard
    }
    state.overall.reason?.let {
      Spacer(Modifier.height(12.dp))
      OverallBanner(state)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.rec_active_hr),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Without skin contact the strap's heart rate is noise, so do not show it as a measurement.
    val offBody = state.contact.state == RecordingState.ContactState.OFF_BODY
    Text(
        text =
            if (offBody)
                stringResource(
                    R.string.rec_active_no_contact_for,
                    RecordingFormat.duration(state.contact.offBodyMs),
                )
            else
                state.heart.bpm?.let { stringResource(R.string.rec_active_hr_value, it) }
                    ?: stringResource(R.string.rec_active_none),
        style = MaterialTheme.typography.headlineMedium,
        color = if (offBody) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (status.config?.ecg == true) {
      state.streams[RecordingState.StreamKind.ECG]?.let {
        StreamLine(
            R.string.rec_active_ecg,
            it,
            poor = state.quality.ecg == RecordingState.EcgQuality.POOR,
        )
      }
    }
    if (status.config?.acc == true) {
      state.streams[RecordingState.StreamKind.ACC]?.let { StreamLine(R.string.rec_active_acc, it) }
    }
    state.storage.lastError?.let {
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.rec_active_failure, it),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

/** The same fixed priority headline the notification shows, formatted for the active screen. */
@Composable
private fun OverallBanner(state: RecordingState) {
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
              RecordingFormat.duration(state.contact.offBodyMs),
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

@Composable
private fun StreamLine(label: Int, stream: RecordingState.Stream, poor: Boolean = false) {
  val name = stringResource(label)
  // The sensor delivers samples in batches about every half second, so the age of the last one
  // jumps around between zero and the batch interval. The monitor owns the staleness decision;
  // this only formats it.
  val text =
      when {
        stream.status == RecordingState.StreamStatus.WAITING ->
            stringResource(R.string.rec_active_stream_waiting, name)
        stream.status == RecordingState.StreamStatus.STALE ->
            stringResource(
                R.string.rec_active_stream_stalled,
                name,
                RecordingFormat.age(stream.lastSampleAgeMs ?: 0),
            )
        poor -> stringResource(R.string.rec_active_stream_poor, name)
        stream.rateHz == null -> stringResource(R.string.rec_active_stream_measuring, name)
        // Rounded to 5 Hz: the batches make the exact figure flicker by a few Hz.
        else ->
            stringResource(
                R.string.rec_active_stream,
                name,
                Math.round(stream.rateHz!! / 5) * 5.0,
            )
      }
  val problem = stream.status == RecordingState.StreamStatus.STALE || poor
  Spacer(Modifier.height(6.dp))
  Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color =
          if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
  )
}

@Composable
private fun linkText(state: RecordingState.LinkState): String =
    stringResource(
        when (state) {
          RecordingState.LinkState.CONNECTED -> R.string.rec_connection_connected
          RecordingState.LinkState.CONNECTING -> R.string.rec_connection_connecting
          RecordingState.LinkState.DISCONNECTED -> R.string.rec_connection_disconnected
        }
    )
