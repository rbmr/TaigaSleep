package com.rbmr.taigasleep.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.remote.CheckResult
import com.rbmr.taigasleep.remote.RemoteError
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.TaigaHttpException
import com.rbmr.taigasleep.remote.TaigaNetworkException
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.remote.label
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.storage.SessionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val localId = inputData.getString(UploadScheduler.KEY_SESSION_ID) ?: return Result.failure()
    val context = applicationContext
    val store = UploadStore.of(context)
    val remote = RemoteRepository.of(context)

    // WorkManager has already started the foreground service. Promote it before any network or
    // disk work, otherwise Android can kill the process for not calling startForeground in time.
    try {
      setForeground(UploadNotifications.foreground(context))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // The upload still runs if the OS refuses the foreground promotion.
    }

    val verified =
        try {
          remote.verify()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return retryOrFail(store, localId, UploadFailure.NETWORK, "could not verify the session")
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
        return retryOrFail(store, localId, failure, verified.error.label)
      }
      is CheckResult.Success -> Unit
    }

    val api =
        remote.api()
            ?: run {
              store.markFailed(localId, UploadFailure.LOGIN, "the saved login is no longer valid")
              return Result.failure()
            }

    return try {
      val sessions = SessionStore.of(context)
      val dir = sessions.dir(localId)
      if (!dir.isDirectory) {
        store.markFailed(localId, UploadFailure.STORAGE, "recording directory is missing")
        return Result.failure()
      }
      val meta = SessionMeta.read(dir)
      if (meta == null) {
        store.markFailed(localId, UploadFailure.STORAGE, "meta.json is missing or invalid")
        return Result.failure()
      }

      if (meta.state == SessionState.RECORDING) {
        if (RecordingService.running) {
          // The service schedules a fresh worker when it finishes. Never upload an open file.
          store.markQueued(localId, UploadFailure.RECORDING, "recording is still in progress")
          return Result.success()
        }
        // A crash left the state as recording. Close the local metadata the same way app start
        // does, using the last frame as the end time.
        val end = sessions.lastFrameMs(dir) ?: meta.startUtcMs
        meta.copy(state = SessionState.INTERRUPTED, endUtcMs = end).write(dir)
      }

      store.markUploading(localId, 0f)

      val outcome =
          UploadEngine(
                  client = api,
                  dir = dir,
                  onProgress = { store.markUploading(localId, it) },
                  onRemoteSession = { store.markRemote(localId, it) },
              )
              .run()
      store.markProcessing(
          localId,
          outcome.remoteId,
          outcome.analysisId,
          outcome.jobId,
          outcome.warning,
      )

      val poller = AnalysisPoller(api, store, ResultCache.of(context), localId)
      try {
        when (val polled = poller.poll(outcome.remoteId, outcome.analysisId, outcome.jobId)) {
          is PollResult.Done,
          is PollResult.Failed -> Result.success()
          is PollResult.Pending -> {
            UploadScheduler.enqueuePoll(
                context,
                localId,
                outcome.remoteId,
                polled.analysisId,
                polled.jobId,
                delayMs = 10_000,
            )
            Result.success()
          }
        }
      } catch (e: TaigaNetworkException) {
        // The upload is safe on the server; only the first status read was missed.
        UploadScheduler.enqueuePoll(
            context,
            localId,
            outcome.remoteId,
            outcome.analysisId,
            outcome.jobId,
            delayMs = 15_000,
        )
        Result.success()
      } catch (e: TaigaHttpException) {
        if (e.status == 401 || e.status == 403) throw e
        if (e.status == 429 || e.status >= 500) {
          UploadScheduler.enqueuePoll(
              context,
              localId,
              outcome.remoteId,
              outcome.analysisId,
              outcome.jobId,
              delayMs = 30_000,
          )
          Result.success()
        } else {
          store.markFailed(localId, UploadFailure.ANALYSIS, e.message)
          Result.success()
        }
      }
    } catch (e: TaigaUnauthorizedException) {
      remote.logout()
      store.markFailed(localId, UploadFailure.LOGIN, e.message)
      Result.failure()
    } catch (e: TaigaNetworkException) {
      retryOrFail(store, localId, UploadFailure.NETWORK, e.message)
    } catch (e: TaigaHttpException) {
      when {
        e.status == 409 && e.message.orEmpty().contains("in progress") -> {
          // The backend refuses a recording that is still open. The service will enqueue again.
          store.markQueued(localId, UploadFailure.RECORDING, e.message)
          Result.success()
        }
        e.status == 429 || e.status >= 500 ->
            retryOrFail(store, localId, UploadFailure.SERVER, e.message)
        else -> {
          store.markFailed(localId, UploadFailure.SERVER, e.message)
          Result.failure()
        }
      }
    } catch (e: UploadException) {
      store.markFailed(localId, UploadFailure.SERVER, e.message)
      Result.failure()
    } catch (e: IOException) {
      store.markFailed(localId, UploadFailure.STORAGE, e.message)
      Result.failure()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      store.markFailed(localId, UploadFailure.STORAGE, e.message)
      Result.failure()
    }
  }

  private fun retryOrFail(
      store: UploadStore,
      localId: String,
      failure: UploadFailure,
      detail: String?,
  ): Result =
      if (runAttemptCount < MAX_ATTEMPTS) {
        store.markQueued(localId, failure, detail)
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
    const val MAX_ATTEMPTS = 5
  }
}
