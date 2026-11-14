package com.rbmr.taigasleep.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.ApiCode
import com.rbmr.taigasleep.backend.CheckResult
import com.rbmr.taigasleep.backend.RemoteError
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.backend.label
import com.rbmr.taigasleep.backend.remoteErrorForCode
import com.rbmr.taigasleep.recording.RecordingPrefs
import com.rbmr.taigasleep.recording.RecordingRecovery
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.recording.RecoveryAction
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
    val queuedFor = inputData.getString(UploadScheduler.KEY_ACCOUNT)

    // Work queued for one account is never sent to another. The queue is cancelled when the account
    // ends, so this only catches a worker that was already starting.
    if (queuedFor != null && queuedFor != remote.accountKey) return Result.success()

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
          return retryOrFail(
              store,
              localId,
              UploadFailure.NETWORK,
              "could not verify the recording",
              queuedFor,
          )
        }
    when (verified) {
      null -> {
        store.markFailed(
            localId,
            UploadFailure.LOGIN,
            "no analysis server is configured",
            queuedFor,
        )
        return Result.failure()
      }
      is CheckResult.Failure -> {
        val failure = failureFor(verified.error)
        // A version mismatch is not fixed by waiting: the app or the server has to be updated.
        if (failure == UploadFailure.LOGIN || failure == UploadFailure.VERSION) {
          store.markFailed(localId, failure, verified.error.label, queuedFor)
          return Result.failure()
        }
        return retryOrFail(store, localId, failure, verified.error.label, queuedFor)
      }
      is CheckResult.Success -> Unit
    }

    val session =
        remote.session()
            ?: run {
              store.markFailed(
                  localId,
                  UploadFailure.LOGIN,
                  "the saved login is no longer valid",
                  queuedFor,
              )
              return Result.failure()
            }
    // A login during the check means this recording belongs to the session that queued it, not to
    // the one that is active now.
    if (queuedFor != null && queuedFor != session.account) return Result.success()
    val api = session.api
    val account = session.account

    return try {
      val sessions = RecordingStore.of(context)
      val dir = sessions.dir(localId)
      if (!dir.isDirectory) {
        store.markFailed(
            localId,
            UploadFailure.STORAGE,
            "recording directory is missing",
            account,
        )
        return Result.failure()
      }
      val meta = RecordingMeta.read(dir)
      if (meta == null) {
        store.markFailed(
            localId,
            UploadFailure.STORAGE,
            "meta.json is missing or invalid",
            account,
        )
        return Result.failure()
      }

      if (meta.state == RecordingPhase.FETCH_PENDING) {
        // The rest of the night is still on the tracker. Uploading now would seal a recording that
        // is about to grow; the completed fetch queues it again as a new upload.
        store.markQueued(
            localId,
            UploadFailure.RECORDING,
            "the rest of the recording is still on the tracker",
            account,
        )
        return Result.success()
      }

      if (meta.state == RecordingPhase.RECORDING) {
        if (RecordingService.running) {
          // The service schedules a fresh worker when it finishes. Never upload an open file.
          store.markQueued(
              localId,
              UploadFailure.RECORDING,
              "recording is still in progress",
              account,
          )
          return Result.success()
        }
        val active = RecordingPrefs.get(context).activeSessionId
        when (RecordingRecovery.onUploadFoundOpen(meta.mode, active, localId)) {
          RecoveryAction.RESUME -> {
            // The phone still marks this recording live: the service decides whether it continues.
            // A refused start leaves the marker in place, so the watchdog tries again.
            runCatching { RecordingService.send(context, RecordingService.ACTION_RESUME) }
            store.markQueued(
                localId,
                UploadFailure.RECORDING,
                "recording is still in progress",
                account,
            )
            return Result.success()
          }
          RecoveryAction.WAIT -> {
            // An on-device recording is running on the tracker, not on the phone.
            store.markQueued(
                localId,
                UploadFailure.RECORDING,
                "recording is still in progress",
                account,
            )
            return Result.success()
          }
          else -> {
            // No marker: a crash left the state as recording. Close the local metadata the same way
            // app start does, using the last frame as the end time.
            val end = sessions.lastFrameMs(dir) ?: meta.startUtcMs
            if (!meta.copy(state = RecordingPhase.INTERRUPTED, endUtcMs = end).write(dir)) {
              throw IOException("could not close the local recording state")
            }
          }
        }
      }

      store.markUploading(localId, 0f, account)

      // The recording id on the server is the phone's own id.
      UploadEngine(
              client = api,
              id = localId,
              dir = dir,
              onProgress = { store.markUploading(localId, it, account) },
              onCreated = { store.markOnServer(localId, account) },
              onCompleted = { store.markUploadConfirmed(localId, account) },
              compress = UploadSettings.get(context).compressUploads.value,
              cacheDir = File(context.filesDir, "upload-cache/$localId"),
          )
          .run()
      // The recording is on the server and nothing waits for it here: the analysis is the next
      // catch-up's, which looks at what this recording has and asks for it only when it has none.
      store.markUploaded(localId, account)
      // The server has the bytes now, so an immediate or overdue cleanup may run.
      RawRetention.enforce(context)
      context.appContainer.syncService.catchUp(CatchUpReason.UPLOAD_FINISHED, localId)
      Result.success()
    } catch (e: TaigaUnauthorizedException) {
      if (remote.accountKey == account) remote.logout()
      store.markFailed(localId, UploadFailure.LOGIN, e.message, account)
      Result.failure()
    } catch (e: TaigaNetworkException) {
      retryOrFail(store, localId, UploadFailure.NETWORK, e.message, account)
    } catch (e: TaigaHttpException) {
      when {
        remoteErrorForCode(e.code) == RemoteError.StorageFull -> {
          // The account's quota is full and waiting does not free space, so the upload fails
          // instead of retrying; the user frees space by deleting a recording.
          store.markFailed(
              localId,
              UploadFailure.SERVER,
              context.getString(R.string.server_error_storage_full),
              account,
          )
          Result.failure()
        }
        e.code == ApiCode.RECORDING_IN_PROGRESS -> {
          // The backend refuses a recording that is still open. The service will enqueue again.
          store.markQueued(localId, UploadFailure.RECORDING, e.message, account)
          Result.success()
        }
        e.status == 429 || e.status >= 500 ->
            retryOrFail(store, localId, UploadFailure.SERVER, e.message, account)
        else -> {
          store.markFailed(localId, UploadFailure.SERVER, e.message, account)
          Result.failure()
        }
      }
    } catch (e: UploadException) {
      store.markFailed(localId, UploadFailure.SERVER, e.message, account)
      Result.failure()
    } catch (e: IOException) {
      store.markFailed(localId, UploadFailure.STORAGE, e.message, account)
      Result.failure()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      store.markFailed(localId, UploadFailure.STORAGE, e.message, account)
      Result.failure()
    }
  }

  private suspend fun retryOrFail(
      store: UploadStore,
      localId: String,
      failure: UploadFailure,
      detail: String?,
      account: String?,
  ): Result =
      if (runAttemptCount < MAX_ATTEMPTS) {
        store.markQueued(localId, failure, detail, account)
        Result.retry()
      } else {
        store.markFailed(localId, failure, detail, account)
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
        RemoteError.StorageFull,
        RemoteError.MalformedResponse -> UploadFailure.SERVER
      }

  private companion object {
    const val MAX_ATTEMPTS = 5
  }
}
