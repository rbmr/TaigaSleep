package com.rbmr.taigasleep.ui.home

import android.animation.ValueAnimator
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.recording.RecordingFormat
import com.rbmr.taigasleep.ui.components.BearDen
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.ConfirmDialog
import com.rbmr.taigasleep.ui.components.ConfirmDialogStyle
import com.rbmr.taigasleep.ui.components.DenSize
import com.rbmr.taigasleep.ui.components.DenTapEffect
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay

internal enum class StopAction {
  STOP,
  FORCE_STOP,
  DISCARD,
}

/**
 * Recording: the smaller glowing den, the elapsed time, the status card and the three ways to end
 * the recording. Stop is the one large action; the two harsher ways sit together under it.
 */
@Composable
internal fun ActiveHome(
    state: HomeUiState.Recording,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onDiscard: () -> Unit,
) {
  val stopping = state.stopping
  val space = MaterialTheme.taiga.space
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surface)
              .verticalScroll(rememberScrollState())
  ) {
    BearDen(
        size = DenSize.Compact,
        variant = LogoVariant.Sleeping,
        glow = true,
        tapEffect = DenTapEffect.ForceField,
        contentDescription = stringResource(R.string.home_logo_recording),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = space.m).padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      RecordingHeader(state.status)
      if (state.status.state.overall.reason != null) OverallBanner(state.status)
      RecordingCard(state.status)
      Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
        PrimaryButton(
            text = stringResource(R.string.rec_stop_and_save),
            onClick = onStop,
            enabled = !stopping,
            size = ButtonSize.Large,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(space.s)) {
          SecondaryButton(
              text = stringResource(R.string.rec_force_stop),
              onClick = onForceStop,
              modifier = Modifier.weight(1f),
              enabled = !stopping,
          )
          DestructiveButton(
              text = stringResource(R.string.rec_discard),
              onClick = onDiscard,
              modifier = Modifier.weight(1f),
              enabled = !stopping,
          )
        }
      }
    }
  }
}

/** The recording label, its elapsed time in the hero style and what it records since. */
@Composable
private fun RecordingHeader(status: CaptureStatus) {
  // A recording stored on the tracker has no service ticking: count from its start.
  val sinceStartMs by
      produceState(0L, status.startUtcMs, status.liveStreaming) {
        while (!status.liveStreaming) {
          value = (System.currentTimeMillis() - status.startUtcMs).coerceAtLeast(0)
          delay(1000)
        }
      }
  val elapsed =
      RecordingFormat.elapsed(
          if (status.liveStreaming) status.state.capture.elapsedMs else sinceStartMs
      )
  val locale = LocalLocale.current.platformLocale
  val since = SimpleDateFormat("HH:mm", locale).format(Date(status.startUtcMs))
  Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.xs)) {
    PulsingLabel(
        text =
            stringResource(
                if (status.phase == Phase.STOPPING) R.string.rec_active_stopping
                else R.string.rec_active_title
            )
    )
    Text(text = elapsed, style = MaterialTheme.taiga.text.hero)
    Text(
        text = stringResource(R.string.rec_active_since, since, status.sensorName.orEmpty()),
        style = MaterialTheme.taiga.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** The recording label breathes while data arrives; with animations off it stays still. */
@Composable
private fun PulsingLabel(text: String) {
  val animations = remember { ValueAnimator.areAnimatorsEnabled() }
  val alpha =
      if (animations) {
        val transition = rememberInfiniteTransition(label = "recording")
        val pulse by
            transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                label = "pulse",
            )
        pulse
      } else {
        1f
      }
  Text(
      text = text,
      style = MaterialTheme.taiga.text.section,
      color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
  )
}

/** The tracker still holds a recording: explain why and offer the only useful action. */
@Composable
internal fun FetchPendingHome(state: HomeUiState.FetchPending, onFetchAgain: () -> Unit) {
  val space = MaterialTheme.taiga.space
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surface)
              .verticalScroll(rememberScrollState())
  ) {
    BearDen(
        size = DenSize.Compact,
        variant = LogoVariant.Sleeping,
        contentDescription = stringResource(R.string.home_logo_sleeping),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = space.m).padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
        Text(
            text = stringResource(R.string.rec_fetch_pending_title),
            style = MaterialTheme.taiga.text.screenTitle,
        )
        Text(
            text = stringResource(R.string.rec_fetch_pending_subtitle),
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TaigaCard {
        Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
          Text(
              text = stringResource(R.string.rec_fetch_pending_body, state.sensorName),
              style = MaterialTheme.taiga.text.body,
          )
          state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.taiga.text.body,
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }
      PrimaryButton(
          text = stringResource(R.string.rec_fetch_again),
          onClick = onFetchAgain,
          size = ButtonSize.Large,
      )
      Text(
          text = stringResource(R.string.rec_fetch_pending_note),
          style = MaterialTheme.taiga.text.meta,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * The confirmation that stands between a tap and ending the recording. Each text says what happens
 * to the data in this mode, and the stop one says where the recording goes next.
 */
@Composable
internal fun StopConfirmDialog(
    action: StopAction,
    onDevice: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
  val (title, body, label) =
      when (action) {
        StopAction.STOP ->
            Triple(
                R.string.rec_stop_title,
                if (onDevice) R.string.rec_stop_body_on_device else R.string.rec_stop_body,
                R.string.rec_stop,
            )
        StopAction.FORCE_STOP ->
            Triple(
                R.string.rec_force_stop_title,
                if (onDevice) R.string.rec_force_stop_body_on_device else R.string.rec_force_stop_body,
                R.string.rec_force_stop,
            )
        StopAction.DISCARD ->
            Triple(
                R.string.rec_discard_title,
                if (onDevice) R.string.rec_discard_body_on_device else R.string.rec_discard_body,
                R.string.rec_discard_confirm,
            )
      }
  ConfirmDialog(
      title = stringResource(title),
      body = stringResource(body),
      confirmLabel = stringResource(label),
      onConfirm = onConfirm,
      onDismiss = onDismiss,
      style =
          if (action == StopAction.DISCARD) ConfirmDialogStyle.Destructive
          else ConfirmDialogStyle.Primary,
  )
}
