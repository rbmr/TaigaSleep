package com.rbmr.taigasleep.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.remote.CheckResult
import com.rbmr.taigasleep.remote.RemoteError
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.TaigaException
import com.rbmr.taigasleep.remote.TaigaHttpException
import com.rbmr.taigasleep.remote.TaigaNetworkException
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.remote.label
import kotlinx.coroutines.CancellationException

class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val localId = inputData.getString(UploadScheduler.KEY_SESSION_ID) ?: return Result.failure()
    val remoteId = inputData.getString(UploadScheduler.KEY_REMOTE_ID) ?: return Result.failure()
    val analysisId = inputData.getString(UploadScheduler.KEY_ANALYSIS_ID)
    val jobId = inputData.getString(UploadScheduler.KEY_JOB_ID)
    val context = applicationContext
    val store = UploadStore.of(context)
    val remote = RemoteRepository.of(context)

    val verified =
        try {
          remote.verify()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return retryOrStop(store, localId, UploadFailure.NETWORK, "could not verify the session")
        }
    when (verified) {
      null -> {
        store.markFailed(localId, UploadFailure.LOGIN, "no analysis server is configured")
        return Result.failure()
      }
      is CheckResult.Failure -> {
        val failure = failureFor(verified.error)
        if (failure == UploadFailure.LOGIN) {
          store.markFailed(localId, failure, verified.error.label)
          return Result.failure()
        }
        return retryOrStop(store, localId, failure, verified.error.label)
      }
      is CheckResult.Success -> Unit
    }

    val api =
        remote.api()
            ?: run {
              store.markFailed(localId, UploadFailure.LOGIN, "the saved login is no longer valid")
              return Result.failure()
            }
    val poller = AnalysisPoller(api, store, ResultCache.of(context), localId)
    return try {
      when (val polled = poller.poll(remoteId, analysisId, jobId)) {
        is PollResult.Done,
        is PollResult.Failed -> Result.success()
        is PollResult.Pending -> {
          if (runAttemptCount >= MAX_POLLS) {
            // Keep the night in PROCESSING with its ids; History offers "Check now" instead of
            // retrying forever in the background.
            Result.success()
          } else {
            Result.retry()
          }
        }
      }
    } catch (e: TaigaUnauthorizedException) {
      remote.logout()
      store.markFailed(localId, UploadFailure.LOGIN, e.message)
      Result.failure()
    } catch (e: TaigaNetworkException) {
      retryOrStop(store, localId, UploadFailure.NETWORK, e.message)
    } catch (e: TaigaHttpException) {
      if (e.status == 404) {
        store.markFailed(localId, UploadFailure.ANALYSIS, e.message)
        Result.failure()
      } else {
        retryOrStop(store, localId, UploadFailure.SERVER, e.message)
      }
    } catch (e: TaigaException) {
      retryOrStop(store, localId, UploadFailure.NETWORK, e.message)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      store.markFailed(localId, UploadFailure.ANALYSIS, e.message)
      Result.failure()
    }
  }

  private fun retryOrStop(
      store: UploadStore,
      localId: String,
      failure: UploadFailure,
      detail: String?,
  ): Result =
      if (runAttemptCount < MAX_RETRIES) {
        store.markProcessingRetry(localId, failure, detail)
        Result.retry()
      } else {
        store.markFailed(localId, failure, detail)
        Result.failure()
      }

  private fun failureFor(error: RemoteError): UploadFailure =
      when (error) {
        RemoteError.UnknownHost,
        RemoteError.Unreachable -> UploadFailure.NETWORK
        RemoteError.InvalidUrl,
        RemoteError.InsecureUrl,
        RemoteError.MissingCredentials,
        RemoteError.BadCredentials,
        RemoteError.SessionExpired,
        RemoteError.NotTaiga -> UploadFailure.LOGIN
        is RemoteError.Http,
        RemoteError.MalformedResponse -> UploadFailure.SERVER
      }

  private companion object {
    const val MAX_RETRIES = 30
    const val MAX_POLLS = 200
  }
}
