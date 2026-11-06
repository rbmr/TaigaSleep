package com.rbmr.taigasleep.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the WorkManager queue. Uploads resume from the stored size of each file on the server, so
 * replacing a queued worker never loses bytes. Manual uploads use the same worker with a connected-network constraint and no
 * charging requirement.
 */
internal object UploadScheduler {
  const val KEY_SESSION_ID = "session_id"
  const val KEY_RECORDING_ID = "recording_id"

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

  fun cancelRecordingWork(context: Context, localId: String) {
    val manager = WorkManager.getInstance(context)
    manager.cancelUniqueWork("upload-$localId")
  }

  fun enqueueDelete(context: Context, recordingId: String) {
    val request =
        OneTimeWorkRequestBuilder<DeleteWorker>()
            .setInputData(Data.Builder().putString(KEY_RECORDING_ID, recordingId).build())
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_DELETE)
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("delete-$recordingId", ExistingWorkPolicy.KEEP, request)
  }

  /**
   * Version 0.5 polled analyses from WorkManager under this tag. Nothing polls any more, so work an
   * earlier version left queued is dropped instead of failing to start a worker class that is gone.
   */
  fun cancelLegacyPolls(context: Context) {
    WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_TAG_POLL)
  }

  /** Restore the remote-delete queue after a process restart. */
  fun resumePendingDeletes(context: Context) {
    for (recordingId in PendingDeleteStore.of(context).remoteIds()) {
      enqueueDelete(context, recordingId)
    }
  }

  /** After an automatic-upload checkbox changes, apply it to the queued uploads. */
  fun rescheduleAll(context: Context) {
    val local = context.appContainer.recordingStore.list()
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
      if (session.state == RecordingPhase.RECORDING) return@forEach
      // A night that is processing is on the server already; its result comes from the live stream.
      if (store.record(session.id)?.phase == UploadPhase.PROCESSING) return@forEach
      if (settings.enabled) enqueueAuto(context, session.id)
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
        for (session in context.appContainer.recordingStore.list()) {
          if (session.state == RecordingPhase.RECORDING &&
              RecordingService.running) {
            continue
          }
          val record = store.record(session.id)
          when (record?.phase) {
            // Results are not polled: a processing night is refreshed by the live sync.
            UploadPhase.DONE,
            UploadPhase.FAILED,
            UploadPhase.PROCESSING -> Unit
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
  private const val TAG_DELETE = "recording-delete"
  private const val LEGACY_TAG_POLL = "analysis-poll"
}
