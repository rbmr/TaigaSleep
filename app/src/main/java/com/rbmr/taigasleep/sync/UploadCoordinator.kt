package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.CheckResult
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.backend.label
import com.rbmr.taigasleep.insights.AnalysisSettingsStore
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HR_ONLY = RecordingConfig(hr = true, ecg = false, acc = false)

/**
 * Configuration of the local recording behind [key]. A remote-only recording has no local
 * metadata, so the safe answer is an HR-only recording, which selects the HR-based stager.
 */
internal fun localRecordingConfig(context: Context, key: String): RecordingConfig {
  val localId = key.takeUnless { it.startsWith("remote-") } ?: return HR_ONLY
  val meta = RecordingMeta.read(context.appContainer.recordingStore.dir(localId))
  return meta?.config ?: HR_ONLY
}

/** User-initiated recovery actions that need a live server call. */
internal object UploadCoordinator {
  /**
   * Retry a failed analysis. A pending analysis is left as it is; a failed one is asked for again,
   * which makes the server replace it with a fresh queued one under a new id.
   */
  suspend fun retryAnalysis(context: Context, key: String, analysisId: String?): Result<Unit> =
      withContext(Dispatchers.IO) {
        val remote = context.appContainer.remoteRepository
        val verified =
            try {
              remote.verify()
            } catch (e: Exception) {
              return@withContext Result.failure(e)
            }
        if (verified !is CheckResult.Success) {
          val reason =
              (verified as? CheckResult.Failure)?.error?.label ?: "no analysis server is configured"
          return@withContext Result.failure(IllegalStateException(reason))
        }
        val api = remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        val store = context.appContainer.uploadStore
        val recordingId = recordingIdOf(key)

        try {
          val analyses = api.listAnalyses(recordingId).items
          val target =
              analyses.firstOrNull { it.id == analysisId }
                  ?: analyses.firstOrNull { it.isFailed }
                  ?: analyses.maxByOrNull { it.created }

          when {
            target == null -> {
              val settings = context.appContainer.analysisSettingsStore.settings.value
              AnalysisService.startPreferred(
                  api,
                  recordingId,
                  localRecordingConfig(context, key),
                  settings,
              )
            }
            target.isFailed ->
                api.requestAnalysis(
                    recordingId,
                    target.algorithm,
                    target.version,
                    target.params,
                )
          }

          // The result arrives through the event stream while the app is open, or at the next open.
          store.markProcessing(key)
          Result.success(Unit)
        } catch (e: TaigaUnauthorizedException) {
          remote.logout()
          Result.failure(e)
        } catch (e: TaigaHttpException) {
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }
}
