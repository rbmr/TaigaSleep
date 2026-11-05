package com.rbmr.taigasleep.upload

import android.content.Context
import com.rbmr.taigasleep.analysis.AnalysisService
import com.rbmr.taigasleep.analysis.AnalysisSettingsStore
import com.rbmr.taigasleep.night.recordingIdOf
import com.rbmr.taigasleep.remote.CheckResult
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.TaigaHttpException
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.remote.label
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User-initiated recovery actions that need a live server call. */
object UploadCoordinator {
  private val HR_ONLY = SessionConfig(hr = true, ecg = false, acc = false)

  /**
   * Configuration of the local recording behind [key]. A remote-only recording has no local
   * metadata, so the safe answer is an HR-only recording, which selects the HR-based stager.
   */
  private fun localConfig(context: Context, key: String): SessionConfig {
    val localId = key.takeUnless { it.startsWith("remote-") } ?: return HR_ONLY
    val meta = SessionMeta.read(SessionStore.of(context).dir(localId))
    return meta?.config ?: HR_ONLY
  }

  /**
   * Retry a failed analysis. A pending analysis is left as it is; a failed one is asked for again,
   * which makes the server replace it with a fresh queued one under a new id.
   */
  suspend fun retryAnalysis(context: Context, key: String, analysisId: String?): Result<Unit> =
      withContext(Dispatchers.IO) {
        val remote = RemoteRepository.of(context)
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
        val store = UploadStore.of(context)
        val recordingId = recordingIdOf(key)

        try {
          val analyses = api.listAnalyses(recordingId).items
          val target =
              analyses.firstOrNull { it.id == analysisId }
                  ?: analyses.firstOrNull { it.isFailed }
                  ?: analyses.maxByOrNull { it.created }

          val targetId =
              when {
                target == null -> {
                  val settings = AnalysisSettingsStore.of(context).settings.value
                  AnalysisService.startPreferred(
                          api,
                          recordingId,
                          localConfig(context, key),
                          settings,
                      )
                      .analysisId
                }
                target.isFailed -> {
                  val again =
                      api.requestAnalysis(
                          recordingId,
                          target.algorithm,
                          target.version,
                          target.params,
                      )
                  again.id
                }
                else -> target.id
              }

          // The result arrives through the event stream while the app is open, or at the next open.
          store.markProcessing(key, targetId)
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
