package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.CheckResult
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.backend.allAnalyses
import com.rbmr.taigasleep.backend.label
import com.rbmr.taigasleep.insights.AnalysisSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User-initiated recovery actions that need a live server call. */
internal object UploadCoordinator {
  /**
   * Retry a failed analysis. A pending analysis is left as it is; a failed one is asked for again,
   * which makes the server replace it with a fresh queued one under a new id.
   */
  suspend fun retryAnalysis(context: Context, key: String, analysisId: String?): Result<Unit> =
      withContext(Dispatchers.IO) {
        val container = context.appContainer
        retryAnalysis(
            remote = container.remoteRepository,
            requests = container.analysisRequests,
            key = key,
            analysisId = analysisId,
            settings = container.analysisSettingsStore.settings.value,
        )
      }

  /**
   * The retry sequence with the session, the requester and the analysis settings handed in, so a
   * test can drive it without the app's wiring.
   */
  internal suspend fun retryAnalysis(
      remote: RemoteRepository,
      requests: AnalysisRequester,
      key: String,
      analysisId: String?,
      settings: AnalysisSettings,
  ): Result<Unit> {
    val verified =
        try {
          remote.verifyIfStale()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return Result.failure(e)
        }
    if (verified !is CheckResult.Success) {
      val reason =
          (verified as? CheckResult.Failure)?.error?.label ?: "no analysis server is configured"
      return Result.failure(IllegalStateException(reason))
    }
    val session =
        remote.session() ?: return Result.failure(IllegalStateException("login required"))
    val api = session.api
    val recordingId = recordingIdOf(key)

    try {
      val analyses = api.allAnalyses(recordingId)
      val target =
          analyses.firstOrNull { it.id == analysisId }
              ?: analyses.firstOrNull { it.isFailed }
              ?: analyses.maxByOrNull { it.created }

      when {
        target == null -> requests.startPreferred(session, key, settings)
        target.isFailed -> requests.retry(session, key, target, automatic = false)
        // Already on its way: nothing to ask for, but the recording is processing either way.
        else -> requests.markProcessing(session, key)
      }

      // The result arrives through the realtime stream while the app is open, or at the next open.
      return Result.success(Unit)
    } catch (e: TaigaUnauthorizedException) {
      if (remote.accountKey == session.account) remote.logout()
      return Result.failure(e)
    } catch (e: TaigaHttpException) {
      return Result.failure(e)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return Result.failure(e)
    }
  }
}
