package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard

/**
 * The one card a night that is not ready gets: processing, uploading, failed or only on the phone.
 * Each state names the one action that makes sense for it.
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
              onClick = onRefresh,
          )
        }
      }
    }
    state.status == RecordingStatus.QUEUED || state.status == RecordingStatus.UPLOADING -> {
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.night_uploading_title),
              style = MaterialTheme.typography.titleLarge,
          )
          Spacer(Modifier.height(6.dp))
          Text(
              text =
                  if (state.status == RecordingStatus.UPLOADING) {
                    stringResource(
                        R.string.night_uploading_progress,
                        (state.progress * 100).toInt().coerceIn(0, 100),
                    ) + " · " + stringResource(R.string.night_uploading_body)
                  } else {
                    stringResource(R.string.night_queued_body)
                  },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(12.dp))
          if (state.status == RecordingStatus.UPLOADING) {
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
