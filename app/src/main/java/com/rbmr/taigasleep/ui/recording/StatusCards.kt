package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ProgressTrack
import com.rbmr.taigasleep.ui.components.ProgressTrackState
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The one card a recording that is not ready gets: processing, uploading, failed or only on the
 * phone. Each state names the one action that makes sense for it.
 */
internal fun LazyListScope.statusCards(
    state: RecordingDetailUiState,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onLogin: () -> Unit,
) {
  when {
    state.status == RecordingStatus.PROCESSING -> {
      item {
        StatusCard(
            title = stringResource(R.string.night_processing_title),
            body = stringResource(R.string.night_processing_body),
        ) {
          ProgressTrack(
              state = ProgressTrackState.Indeterminate,
              description = stringResource(R.string.night_processing_title),
          )
          SecondaryButton(text = stringResource(R.string.night_check_again), onClick = onRefresh)
        }
      }
    }
    state.status == RecordingStatus.QUEUED || state.status == RecordingStatus.UPLOADING -> {
      item {
        val uploading = state.status == RecordingStatus.UPLOADING
        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        StatusCard(
            title = stringResource(R.string.night_uploading_title),
            body =
                if (uploading) {
                  stringResource(
                      R.string.night_uploading_progress_body,
                      stringResource(R.string.night_uploading_progress, percent),
                      stringResource(R.string.night_uploading_body),
                  )
                } else {
                  stringResource(R.string.night_queued_body)
                },
        ) {
          ProgressTrack(
              state =
                  if (uploading) {
                    ProgressTrackState.Determinate(state.progress)
                  } else {
                    ProgressTrackState.Indeterminate
                  },
              description =
                  if (uploading) {
                    stringResource(R.string.night_uploading_progress, percent)
                  } else {
                    null
                  },
          )
          SecondaryButton(
              text =
                  stringResource(
                      if (state.local != null) R.string.night_retry_upload
                      else R.string.night_check_again
                  ),
              onClick = { if (state.local != null) onUpload() else onRefresh() },
          )
        }
      }
    }
    state.status == RecordingStatus.FAILED -> {
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
                state.failure == UploadFailure.LOGIN && !state.connected ->
                    PrimaryButton(
                        text = stringResource(R.string.history_action_login),
                        onClick = onLogin,
                        modifier = Modifier.weight(1f),
                    )
                state.failure == UploadFailure.ANALYSIS || state.local != null ->
                    PrimaryButton(
                        text = stringResource(R.string.night_retry),
                        onClick = {
                          if (state.failure == UploadFailure.ANALYSIS) onRetryAnalysis()
                          else onUpload()
                        },
                        modifier = Modifier.weight(1f),
                    )
                else ->
                    SecondaryButton(
                        text = stringResource(R.string.night_check_again),
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
              }
            },
        )
      }
    }
    state.local != null -> {
      item {
        StatusCard(
            title = stringResource(R.string.night_local_only_title),
            body = stringResource(R.string.night_local_only_body),
        ) {
          PrimaryButton(
              text =
                  stringResource(
                      if (state.connected) R.string.night_upload
                      else R.string.history_action_login
                  ),
              onClick = { if (state.connected) onUpload() else onLogin() },
              size = ButtonSize.Regular,
          )
        }
      }
    }
  }
}

/** One state of a recording: what it is, one line about it, and the action that moves it on. */
@Composable
private fun StatusCard(
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  TaigaCard {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space.s)) {
      Text(text = title, style = MaterialTheme.taiga.text.section)
      Text(
          text = body,
          style = MaterialTheme.taiga.text.body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      content()
    }
  }
}
