package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.insights.AnalysisSettingsStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.backend.ApiCode
import com.rbmr.taigasleep.backend.CheckResult
import com.rbmr.taigasleep.backend.RemoteError
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.backend.label
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import java.io.File
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
        // A version mismatch is not fixed by waiting: the app or the server has to be updated.
        if (failure == UploadFailure.LOGIN || failure == UploadFailure.VERSION) {
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
      val sessions = RecordingStore.of(context)
      val dir = sessions.dir(localId)
      if (!dir.isDirectory) {
        store.markFailed(localId, UploadFailure.STORAGE, "recording directory is missing")
        return Result.failure()
      }
      val meta = RecordingMeta.read(dir)
      if (meta == null) {
        store.markFailed(localId, UploadFailure.STORAGE, "meta.json is missing or invalid")
        return Result.failure()
      }

      if (meta.state == RecordingPhase.RECORDING) {
        if (RecordingService.running) {
          // The service schedules a fresh worker when it finishes. Never upload an open file.
          store.markQueued(localId, UploadFailure.RECORDING, "recording is still in progress")
          return Result.success()
        }
        // A crash left the state as recording. Close the local metadata the same way app start
        // does, using the last frame as the end time.
        val end = sessions.lastFrameMs(dir) ?: meta.startUtcMs
        meta.copy(state = RecordingPhase.INTERRUPTED, endUtcMs = end).write(dir)
      }

      store.markUploading(localId, 0f)

      // The recording id on the server is the phone's own id.
      UploadEngine(
              client = api,
              id = localId,
              dir = dir,
              onProgress = { store.markUploading(localId, it) },
              onCreated = { store.markOnServer(localId) },
              compress = UploadSettings.get(context).compressUploads.value,
              cacheDir = File(context.filesDir, "upload-cache/$localId"),
          )
          .run()
      val settings = AnalysisSettingsStore.of(context).settings.value
      val started =
          try {
            AnalysisService.startAuto(api, localId, meta.config, settings)
          } catch (e: TaigaHttpException) {
            // The bytes are safe on the server and only the analysis was refused, for example
            // because no worker offers the algorithm yet. The retry asks for it again.
            if (e.status != 422) throw e
            store.markFailed(localId, UploadFailure.ANALYSIS, e.message)
            RawRetention.enforce(context)
            return Result.failure()
          }
      store.markProcessing(localId)
      // The server has the bytes now, so an immediate or overdue cleanup may run.
      RawRetention.enforce(context)
      // The night stays in the processing state and nothing waits for it: while the app is open the
      // event stream reports the result, otherwise the next open reads it. With automatic analysis
      // off (started is null) the night is only stored and can be analysed manually.
      Result.success()
    } catch (e: TaigaUnauthorizedException) {
      remote.logout()
      store.markFailed(localId, UploadFailure.LOGIN, e.message)
      Result.failure()
    } catch (e: TaigaNetworkException) {
      retryOrFail(store, localId, UploadFailure.NETWORK, e.message)
    } catch (e: TaigaHttpException) {
      when {
        e.code == ApiCode.RECORDING_IN_PROGRESS -> {
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
        is RemoteError.IncompatibleApi -> UploadFailure.VERSION
        is RemoteError.Http,
        RemoteError.MalformedResponse -> UploadFailure.SERVER
      }

  private companion object {
    const val MAX_ATTEMPTS = 5
  }
}
