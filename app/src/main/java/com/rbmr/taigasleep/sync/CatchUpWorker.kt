package com.rbmr.taigasleep.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rbmr.taigasleep.appContainer
import java.util.concurrent.TimeUnit

/**
 * The safety net of the automatic upload and analysis: every few hours the catch-up looks at all
 * recordings once more, in case a change happened that no event reported. It does nothing that
 * the events would not have done, so a night that was handled is not handled twice.
 */
class CatchUpWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    applicationContext.appContainer.syncService.catchUp(CatchUpReason.PERIODIC)
    return Result.success()
  }

  companion object {
    private const val NAME = "automation-catch-up"
    private const val INTERVAL_HOURS = 6L

    /** Keeps the one periodic job; calling it again on every start leaves it as it is. */
    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<CatchUpWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
      WorkManager.getInstance(context)
          .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
