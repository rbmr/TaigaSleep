package com.rbmr.taigasleep.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.storage.SessionStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the WorkManager queue. Uploads resume from the server manifest, so replacing a queued worker
 * never loses bytes. Manual uploads use the same worker with a connected-network constraint and no
 * charging requirement.
 */
object UploadScheduler {
  const val KEY_SESSION_ID = "session_id"
  const val KEY_REMOTE_ID = "remote_id"
  const val KEY_ANALYSIS_ID = "analysis_id"
  const val KEY_JOB_ID = "job_id"

  fun onRecordingFinished(context: Context, localId: String) {
    val settings = UploadSettings.get(context)
    if (!settings.autoUpload.value.enabled) return
    if (!RemoteRepository.of(context).hasSession) return
    val dir = SessionStore.of(context).dir(localId)
    if (!dir.isDirectory) return
    enqueueAuto(context, localId)
  }

  fun enqueueManual(context: Context, localId: String) {
    val store = UploadStore.of(context)
    store.markQueued(localId)
    enqueue(context, localId, manualConstraints(), ExistingWorkPolicy.REPLACE)
  }

  fun enqueueAuto(context: Context, localId: String) {
    val settings = UploadSettings.get(context).autoUpload.value
    if (!settings.enabled) return
    if (!RemoteRepository.of(context).hasSession) return
    val store = UploadStore.of(context)
    val record = store.record(localId)
    if (record?.phase == UploadPhase.DONE) return
    if (record?.phase == UploadPhase.FAILED) return // let the user decide; retry is explicit
    if (record?.failure == UploadFailure.RECORDING) store.markQueued(localId)
    if (record?.phase == UploadPhase.LOCAL || record == null) store.markQueued(localId)
    enqueue(context, localId, autoConstraints(settings), ExistingWorkPolicy.KEEP)
  }

  fun enqueuePoll(
      context: Context,
      localId: String,
      remoteId: String,
      analysisId: String?,
      jobId: String?,
      delayMs: Long = 0,
      replace: Boolean = false,
  ) {
    val data =
        Data.Builder()
            .putString(KEY_SESSION_ID, localId)
            .putString(KEY_REMOTE_ID, remoteId)
            .apply {
              if (analysisId != null) putString(KEY_ANALYSIS_ID, analysisId)
              if (jobId != null) putString(KEY_JOB_ID, jobId)
            }
            .build()
    val request =
        OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_POLL)
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "analysis-$localId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
  }

  fun cancelRecordingWork(context: Context, localId: String) {
    val manager = WorkManager.getInstance(context)
    manager.cancelUniqueWork("upload-$localId")
    manager.cancelUniqueWork("analysis-$localId")
  }

  fun enqueueDelete(context: Context, remoteId: String) {
    val request =
        OneTimeWorkRequestBuilder<DeleteWorker>()
            .setInputData(Data.Builder().putString(KEY_REMOTE_ID, remoteId).build())
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_DELETE)
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("delete-$remoteId", ExistingWorkPolicy.KEEP, request)
  }

  /** Restore the remote-delete queue after a process restart. */
  fun resumePendingDeletes(context: Context) {
    for (remoteId in PendingDeleteStore.of(context).remoteIds()) {
      enqueueDelete(context, remoteId)
    }
  }

  /** After an automatic-upload checkbox changes, apply it to the queued uploads. */
  fun rescheduleAll(context: Context) {
    val local = SessionStore.of(context).list()
    val manager = WorkManager.getInstance(context)
    val store = UploadStore.of(context)
    val settings = UploadSettings.get(context).autoUpload.value
    local.forEach { session ->
      manager.cancelUniqueWork("upload-${session.id}")
      if (store.record(session.id)?.phase == UploadPhase.UPLOADING) {
        store.markQueued(session.id)
      }
    }
    if (!RemoteRepository.of(context).hasSession) return
    local.forEach { session ->
      if (session.state == SessionState.RECORDING) return@forEach
      val record = store.record(session.id)
      if (record?.phase == UploadPhase.PROCESSING) {
        val remote = record.remoteId
        if (remote != null) {
          enqueuePoll(
              context,
              session.id,
              remote,
              record.analysisId,
              record.jobId,
              replace = true,
          )
        }
      } else if (settings.enabled) {
        enqueueAuto(context, session.id)
      }
    }
  }

  /**
   * Called on app resume. It never starts bytes for an active recording and never retries a
   * permanent failure automatically.
   */
  suspend fun reconcile(context: Context) =
      withContext(Dispatchers.IO) {
        if (!RemoteRepository.of(context).hasSession) return@withContext
        val settings = UploadSettings.get(context).autoUpload.value
        val store = UploadStore.of(context)
        for (session in SessionStore.of(context).list()) {
          if (session.state == SessionState.RECORDING &&
              RecordingService.running) {
            continue
          }
          val record = store.record(session.id)
          when (record?.phase) {
            UploadPhase.DONE,
            UploadPhase.FAILED -> Unit
            UploadPhase.PROCESSING -> {
              val remote = record.remoteId ?: continue
              enqueuePoll(
                  context,
                  session.id,
                  remote,
                  record.analysisId,
                  record.jobId,
                  replace = true,
              )
            }
            UploadPhase.LOCAL,
            UploadPhase.QUEUED,
            UploadPhase.UPLOADING,
            null ->
                if (settings.enabled) {
                  enqueueAuto(context, session.id)
                } else if (record?.phase == UploadPhase.UPLOADING) {
                  store.markQueued(session.id)
                }
          }
        }
      }

  private fun enqueue(
      context: Context,
      localId: String,
      constraints: Constraints,
      policy: ExistingWorkPolicy,
  ) {
    val request: OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(KEY_SESSION_ID, localId).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_UPLOAD)
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("upload-$localId", policy, request)
  }

  private fun manualConstraints(): Constraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  private fun autoConstraints(settings: AutoUploadSettings): Constraints =
      Constraints.Builder()
          .setRequiredNetworkType(
              if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
          )
          .setRequiresCharging(settings.chargingOnly)
          .build()

  private const val TAG_UPLOAD = "recording-upload"
  private const val TAG_POLL = "analysis-poll"
  private const val TAG_DELETE = "recording-delete"
}
