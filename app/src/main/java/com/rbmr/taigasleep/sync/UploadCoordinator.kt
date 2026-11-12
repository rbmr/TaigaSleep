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
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingStore
import java.time.LocalDate
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
        val container = context.appContainer
        retryAnalysis(
            remote = container.remoteRepository,
            store = container.uploadStore,
            key = key,
            analysisId = analysisId,
            config = { localRecordingConfig(context, key) },
            settings = container.analysisSettingsStore.settings.value,
            personalData = container.personalDataStore.data.value,
            startDate = startDateOf(context, key),
        )
      }

  /**
   * The retry sequence with the session, the store, the recording's configuration and the analysis
   * settings handed in, so a test can drive it without the app's wiring.
   */
  internal suspend fun retryAnalysis(
      remote: RemoteRepository,
      store: UploadStore,
      key: String,
      analysisId: String?,
      config: () -> RecordingConfig,
      settings: AnalysisSettings,
      personalData: PersonalData = PersonalData(),
      startDate: LocalDate? = null,
  ): Result<Unit> {
    val verified =
        try {
          remote.verify()
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
        target == null -> {
          AnalysisService.startPreferred(
              api,
              recordingId,
              config(),
              settings,
              personalData,
              startDate,
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

      // The result arrives through the realtime stream while the app is open, or at the next open.
      store.markProcessing(key, account = session.account)
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
