package com.rbmr.taigasleep.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.remote.CheckResult
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.TaigaHttpException
import com.rbmr.taigasleep.remote.TaigaNetworkException
import kotlinx.coroutines.CancellationException

class DeleteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val remoteId = inputData.getString(UploadScheduler.KEY_REMOTE_ID) ?: return Result.failure()
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
      api.deleteSession(remoteId)
      PendingDeleteStore.of(applicationContext).remove(remoteId)
      Result.success()
    } catch (e: TaigaHttpException) {
      if (e.status == 404) {
        // Already gone, or not visible to this account. Either way there is nothing left to do.
        PendingDeleteStore.of(applicationContext).remove(remoteId)
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
}
