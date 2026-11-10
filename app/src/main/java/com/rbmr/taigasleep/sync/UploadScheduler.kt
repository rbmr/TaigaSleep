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
 *
 * Every request carries the account it was queued for. A worker whose account is no longer the active
 * one stops instead of sending that work to the new account.
 */
internal object UploadScheduler {
  const val KEY_SESSION_ID = "session_id"
  const val KEY_RECORDING_ID = "recording_id"
  const val KEY_ACCOUNT = "account"

  fun enqueueManual(context: Context, localId: String) {
    val account = context.appContainer.remoteRepository.accountKey ?: return
    val store = context.appContainer.uploadStore
    store.markQueued(localId, account = account)
    enqueue(context, localId, account, manualConstraints(), ExistingWorkPolicy.REPLACE)
  }

  fun enqueueAuto(context: Context, localId: String) {
    val settings = UploadSettings.get(context).autoUpload.value
    if (!settings.enabled) return
    val account = context.appContainer.remoteRepository.accountKey ?: return
    val store = context.appContainer.uploadStore
    val record = store.record(localId, account)
    if (record?.phase == UploadPhase.DONE) return
    if (record?.phase == UploadPhase.FAILED) return // let the user decide; retry is explicit
    if (record?.failure == UploadFailure.RECORDING) store.markQueued(localId, account = account)
    if (record?.phase == UploadPhase.LOCAL || record == null) {
      store.markQueued(localId, account = account)
    }
    enqueue(context, localId, account, autoConstraints(settings), ExistingWorkPolicy.KEEP)
  }

  fun cancelRecordingWork(context: Context, localId: String) {
    val manager = WorkManager.getInstance(context)
    manager.cancelUniqueWork("upload-$localId")
  }

  /**
   * Everything queued or running for the account that is ending: uploads, deletes and the polls an
   * older version left behind. WorkManager cancels a running worker, so nothing that belongs to the
   * old account reaches the new one.
   */
  fun cancelAllWork(context: Context) {
    val manager = WorkManager.getInstance(context)
    manager.cancelAllWorkByTag(TAG_UPLOAD)
    manager.cancelAllWorkByTag(TAG_DELETE)
    manager.cancelAllWorkByTag(LEGACY_TAG_POLL)
  }

  fun enqueueDelete(context: Context, recordingId: String) {
    val account = context.appContainer.remoteRepository.accountKey ?: return
    enqueueDelete(context, recordingId, account)
  }

  private fun enqueueDelete(context: Context, recordingId: String, account: String) {
    val request =
        OneTimeWorkRequestBuilder<DeleteWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_RECORDING_ID, recordingId)
                    .putString(KEY_ACCOUNT, account)
                    .build()
            )
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

  /** Restore the remote-delete queue of the active account after a process restart. */
  fun resumePendingDeletes(context: Context) {
    val account = context.appContainer.remoteRepository.accountKey ?: return
    val store = context.appContainer.pendingDeleteStore
    store.refreshAccount()
    for (recordingId in store.remoteIds(account)) {
      enqueueDelete(context, recordingId, account)
    }
  }

  /** After an automatic-upload checkbox changes, apply it to the queued uploads. */
  fun rescheduleAll(context: Context) {
    val local = context.appContainer.recordingStore.list()
    val manager = WorkManager.getInstance(context)
    val store = context.appContainer.uploadStore
    val settings = UploadSettings.get(context).autoUpload.value
    local.forEach { recording ->
      manager.cancelUniqueWork("upload-${recording.id}")
      if (store.record(recording.id)?.phase == UploadPhase.UPLOADING) {
        store.markQueued(recording.id)
      }
    }
    val account = context.appContainer.remoteRepository.accountKey ?: return
    local.forEach { recording ->
      if (recording.state == RecordingPhase.RECORDING) return@forEach
      // A recording that is processing is on the server already; its result comes from the live stream.
      if (store.record(recording.id, account)?.phase == UploadPhase.PROCESSING) return@forEach
      if (settings.enabled) enqueueAuto(context, recording.id)
    }
  }

  /**
   * Called on app resume. It never starts bytes for an active recording and never retries a
   * permanent failure automatically. An upload the phone left at its end without the server's
   * word is asked about first, so a confirmation cannot stay missing.
   */
  suspend fun reconcile(context: Context) =
      withContext(Dispatchers.IO) {
        val remote = context.appContainer.remoteRepository
        val session = remote.session() ?: return@withContext
        val settings = UploadSettings.get(context).autoUpload.value
        val store = context.appContainer.uploadStore
        for (recording in context.appContainer.recordingStore.list()) {
          if (recording.state == RecordingPhase.RECORDING &&
              RecordingService.running) {
            continue
          }
          val record = store.record(recording.id, session.account)
          // An upload that reached its end without the server's word is not counted yet: only the
          // server can say whether the recording is complete, so ask before the queue decides.
          if (record != null && record.awaitsUploadConfirmation()) {
            confirmUploadOnServer(session.api, recording.id, store, session.account)
          }
          when (record?.phase) {
            // Results are not polled: a processing recording is refreshed by the live sync.
            UploadPhase.DONE,
            UploadPhase.FAILED,
            UploadPhase.PROCESSING -> Unit
            UploadPhase.LOCAL,
            UploadPhase.QUEUED,
            UploadPhase.UPLOADING,
            null ->
                if (settings.enabled) {
                  enqueueAuto(context, recording.id)
                } else if (record?.phase == UploadPhase.UPLOADING) {
                  store.markQueued(recording.id, account = session.account)
                }
          }
        }
      }

  private fun enqueue(
      context: Context,
      localId: String,
      account: String,
      constraints: Constraints,
      policy: ExistingWorkPolicy,
  ) {
    val request: OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_SESSION_ID, localId)
                    .putString(KEY_ACCOUNT, account)
                    .build()
            )
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
