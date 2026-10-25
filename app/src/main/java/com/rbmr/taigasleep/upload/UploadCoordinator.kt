package com.rbmr.taigasleep.upload

import android.content.Context
import com.rbmr.taigasleep.remote.CheckResult
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.TaigaHttpException
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.remote.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User-initiated recovery actions that need a live server call. */
object UploadCoordinator {
  /**
   * Retry a failed analysis. Pending analyses only need a fresh poll; a failed job must be requeued
   * through the job route first.
   */
  suspend fun retryAnalysis(
      context: Context,
      key: String,
      remoteId: String,
      analysisId: String?,
      jobId: String?,
  ): Result<Unit> =
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

        try {
          val detail = api.getSession(remoteId)
          var targetAnalysis = analysisId
          var targetJob = jobId
          if (targetAnalysis == null) {
            targetAnalysis =
                detail.analyses.firstOrNull { it.status == "failed" }?.id
                    ?: detail.analyses.maxByOrNull { it.created }?.id
          }
          if (targetJob == null) {
            targetJob = detail.jobs.firstOrNull { it.analysis == targetAnalysis }?.id
          }

          val failedJob =
              detail.jobs.firstOrNull {
                it.id == targetJob && it.status == "failed"
              }
                  ?: detail.jobs.firstOrNull {
                    it.status == "failed" &&
                        (targetAnalysis == null || it.analysis == targetAnalysis)
                  }
          if (failedJob != null) {
            val retried = api.retryJob(failedJob.id)
            targetAnalysis = retried.analysis?.id ?: targetAnalysis
            targetJob = retried.job.id.ifBlank { failedJob.id }
          } else if (targetAnalysis == null) {
            val requested =
                api.requestAnalysis(remoteId, UploadEngine.MODEL, UploadEngine.MODEL_VERSION)
            targetAnalysis = requested.analyses.firstOrNull()?.id
            targetJob = requested.jobs.firstOrNull()?.id
          }

          store.markProcessing(key, remoteId, targetAnalysis, targetJob)
          UploadScheduler.enqueuePoll(
              context,
              key,
              remoteId,
              targetAnalysis,
              targetJob,
              replace = true,
          )
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
