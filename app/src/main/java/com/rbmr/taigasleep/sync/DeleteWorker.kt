package com.rbmr.taigasleep.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.backend.CheckResult
import com.rbmr.taigasleep.backend.RemoteRepository
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
    val remote = RemoteRepository.of(applicationContext)
    val verified =
        try {
          remote.verify()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return Result.retry()
        }
    if (verified !is CheckResult.Success) return Result.retry()
    val api = remote.api() ?: return Result.retry()

    return try {
      api.deleteRecording(recordingId)
      complete(recordingId)
      Result.success()
    } catch (e: TaigaHttpException) {
      if (e.status == 404) {
        // Already gone, or not visible to this account. Either way there is nothing left to do.
        complete(recordingId)
        Result.success()
      } else if (e.status == 401 || e.status == 403) {
        Result.retry()
      } else {
        Result.retry()
      }
    } catch (e: TaigaNetworkException) {
      Result.retry()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      Result.retry()
    }
  }

  /**
   * The server no longer has the recording. Taken out of the cached list first, so a History that
   * reads it while the queue empties cannot show it again, and only then out of the queue.
   */
  private fun complete(recordingId: String) {
    RemoteHistoryCache.of(applicationContext)
        .remove(RemoteRepository.of(applicationContext).accountKey, recordingId)
    PendingDeleteStore.of(applicationContext).remove(recordingId)
  }
}
