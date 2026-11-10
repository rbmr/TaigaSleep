package com.rbmr.taigasleep.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.backend.CheckResult
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaNetworkException
import kotlinx.coroutines.CancellationException

class DeleteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val recordingId =
        inputData.getString(UploadScheduler.KEY_RECORDING_ID) ?: return Result.failure()
    val queuedFor = inputData.getString(UploadScheduler.KEY_ACCOUNT)
    val remote = RemoteRepository.of(applicationContext)
    val pending = PendingDeleteStore.of(applicationContext)
    val history = RemoteHistoryCache.of(applicationContext)

    val outcome =
        RemoteDelete.run(
            recordingId = recordingId,
            queuedFor = queuedFor,
            activeAccount = { remote.accountKey },
            verify = { remote.verify() is CheckResult.Success },
            api = { remote.api() },
            onDeleted = { account ->
                // Out of the cached list first, so a History that reads it while the queue empties
                // cannot show it again, and only then out of the queue.
                history.remove(account, recordingId)
                pending.remove(recordingId, account)
            },
        )
    return when (outcome) {
      RemoteDelete.Outcome.COMPLETE,
      RemoteDelete.Outcome.OTHER_ACCOUNT -> Result.success()
      RemoteDelete.Outcome.DEFER -> Result.retry()
    }
  }
}

/**
 * The delete of one recording, for the account that queued it. It is separate from the worker so the
 * rule "a delete queued for one account is never sent to another" is testable without Android.
 */
internal object RemoteDelete {
  enum class Outcome {
    /** The server no longer has the recording for the account that asked. */
    COMPLETE,
    /** The server could not be reached or answered in a way that may succeed later. */
    DEFER,
    /** The work belongs to an account that is not active; it must not run now. */
    OTHER_ACCOUNT,
  }

  suspend fun run(
      recordingId: String,
      queuedFor: String?,
      activeAccount: () -> String?,
      verify: suspend () -> Boolean,
      api: () -> TaigaClient?,
      onDeleted: (account: String) -> Unit,
  ): Outcome {
    // Read before any server call: the token that verify refreshes must belong to this work.
    if (queuedFor != null && queuedFor != activeAccount()) return Outcome.OTHER_ACCOUNT

    val verified =
        try {
          verify()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return Outcome.DEFER
        }
    if (!verified) return Outcome.DEFER

    val account = activeAccount() ?: return Outcome.DEFER
    if (queuedFor != null && queuedFor != account) return Outcome.OTHER_ACCOUNT
    val client = api() ?: return Outcome.DEFER

    return try {
      client.deleteRecording(recordingId)
      onDeleted(account)
      Outcome.COMPLETE
    } catch (e: TaigaHttpException) {
      if (e.status != 404) {
        Outcome.DEFER
      } else {
        // The account that asked no longer has it: there is nothing left to do for that account.
        onDeleted(account)
        Outcome.COMPLETE
      }
    } catch (e: TaigaNetworkException) {
      Outcome.DEFER
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      Outcome.DEFER
    }
  }
}
