package com.rbmr.taigasleep.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
 * replacing a queued worker never loses bytes. No upload waits for Android to report a network: the
 * server may be reachable without one (a port forward), so an upload simply tries and a failed
 * attempt is retried with backoff. A manual upload is the user's own request: it runs at once and the
 * automatic-upload conditions (Wi-Fi only, charging) are not applied to it. Its work carries
 * [TAG_MANUAL], so a later pass over the queue leaves it alone and its retries stay manual.
 *
 * Every request carries the account it was queued for. A worker whose account is no longer the active
 * one stops instead of sending that work to the new account.
 */
internal object UploadScheduler {
  const val KEY_SESSION_ID = "session_id"
  const val KEY_RECORDING_ID = "recording_id"
  const val KEY_ACCOUNT = "account"

  suspend fun enqueueManual(context: Context, localId: String) {
    val account = context.appContainer.remoteRepository.accountKey ?: return
    val store = context.appContainer.uploadStore
    // The upload starts now, so it is shown as uploading instead of waiting in the queue.
    store.markUploading(localId, 0f, account)
    enqueue(context, localId, account, UploadConditions.manual(), manual = true, ExistingWorkPolicy.REPLACE)
  }

  suspend fun enqueueAuto(context: Context, localId: String) {
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
    enqueue(context, localId, account, UploadConditions.automatic(settings), manual = false, ExistingWorkPolicy.KEEP)
  }

  fun cancelRecordingWork(context: Context, localId: String) {
    val manager = WorkManager.getInstance(context)
    manager.cancelUniqueWork("upload-$localId")
  }

  /**
   * Everything queued or running for the account that is ending: uploads and deletes. WorkManager
   * cancels a running worker, so nothing that belongs to the old account reaches the new one.
   */
  fun cancelAllWork(context: Context) {
    val manager = WorkManager.getInstance(context)
    manager.cancelAllWorkByTag(TAG_UPLOAD)
    manager.cancelAllWorkByTag(TAG_DELETE)
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

  /** Restore the remote-delete queue of the active account after a process restart. */
  suspend fun resumePendingDeletes(context: Context) {
    val account = context.appContainer.remoteRepository.accountKey ?: return
    val store = context.appContainer.pendingDeleteStore
    for (recordingId in store.remoteIds(account)) {
      enqueueDelete(context, recordingId, account)
    }
  }

  /** After an automatic-upload checkbox changes, apply it to the queued uploads. */
  suspend fun rescheduleAll(context: Context) {
    val local = context.appContainer.recordingStore.list()
    val manager = WorkManager.getInstance(context)
    val store = context.appContainer.uploadStore
    val settings = UploadSettings.get(context).autoUpload.value
    // A manual upload is not governed by these settings; it keeps running as it was queued.
    val manual = local.filter { isManualUploadActive(context, it.id) }.map { it.id }.toSet()
    local.filter { it.id !in manual }.forEach { recording ->
      manager.cancelUniqueWork("upload-${recording.id}")
      if (store.record(recording.id)?.phase == UploadPhase.UPLOADING) {
        store.markQueued(recording.id)
      }
    }
    val account = context.appContainer.remoteRepository.accountKey ?: return
    local.forEach { recording ->
      if (recording.id in manual) return@forEach
      // A recording that is not finished is never queued: the service enqueues it when it ends.
      if (recording.state.unfinished) return@forEach
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
          // A recording waiting for its tracker copy stays out of the queue: the completed fetch
          // queues it as a new upload, with every sample in the files.
          if (recording.state == RecordingPhase.FETCH_PENDING) continue
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
            UploadPhase.UPLOADED,
            UploadPhase.FAILED,
            UploadPhase.PROCESSING -> Unit
            UploadPhase.LOCAL,
            UploadPhase.QUEUED,
            UploadPhase.UPLOADING,
            null ->
                if (settings.enabled) {
                  enqueueAuto(context, recording.id)
                } else if (record?.phase == UploadPhase.UPLOADING &&
                    !isUploadWorkActive(context, recording.id)) {
                  // Only an upload nothing is running is stale; a manual one in flight stays.
                  store.markQueued(recording.id, account = session.account)
                }
          }
        }
      }

  private fun enqueue(
      context: Context,
      localId: String,
      account: String,
      conditions: UploadConditions,
      manual: Boolean,
      policy: ExistingWorkPolicy,
  ) {
    val builder = OneTimeWorkRequestBuilder<UploadWorker>()
    if (conditions.expedited) {
      builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }
    if (manual) builder.addTag(TAG_MANUAL)
    val request: OneTimeWorkRequest =
        builder
            .setInputData(
                Data.Builder()
                    .putString(KEY_SESSION_ID, localId)
                    .putString(KEY_ACCOUNT, account)
                    .build()
            )
            .setConstraints(conditions.toConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_UPLOAD)
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("upload-$localId", policy, request)
  }

  /** Whether an upload worker for this recording is queued or running. */
  private fun isUploadWorkActive(context: Context, localId: String): Boolean =
      activeUploadWork(context, localId).isNotEmpty()

  /** Whether the upload queued or running for this recording was requested by the user. */
  private fun isManualUploadActive(context: Context, localId: String): Boolean =
      activeUploadWork(context, localId).any { TAG_MANUAL in it.tags }

  private fun activeUploadWork(context: Context, localId: String) =
      WorkManager.getInstance(context).getWorkInfosForUniqueWork("upload-$localId").get().filter {
        !it.state.isFinished
      }

  private const val TAG_UPLOAD = "recording-upload"
  private const val TAG_MANUAL = "recording-upload-manual"
  private const val TAG_DELETE = "recording-delete"
}

/**
 * What an upload needs before it may start. There is never a generic network requirement: an upload
 * tries when it is due and is retried with backoff if the server cannot be reached. A manual upload
 * needs nothing and is expedited, so it starts at once; the Wi-Fi and charging choices belong to
 * automatic uploads alone.
 */
internal data class UploadConditions(
    val network: NetworkType,
    val requiresCharging: Boolean,
    val expedited: Boolean,
) {
  fun toConstraints(): Constraints =
      Constraints.Builder()
          .setRequiredNetworkType(network)
          .setRequiresCharging(requiresCharging)
          .build()

  companion object {
    fun manual() =
        UploadConditions(NetworkType.NOT_REQUIRED, requiresCharging = false, expedited = true)

    fun automatic(settings: AutoUploadSettings) =
        UploadConditions(
            network = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED,
            requiresCharging = settings.chargingOnly,
            expedited = false,
        )
  }
}
