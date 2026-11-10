package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.recording.RecordingRepository
import java.io.File

/**
 * Deletes one recording everywhere the active account has it. The local copy and cached results go
 * immediately; the remote recording is queued, with that account, and retried until the server is
 * reachable. A 404 from the server is treated as success, because the only useful meaning is that
 * the recording no longer exists for that account. [remoteId] is the recording id on the server,
 * which is the local id for a recording that was uploaded from this phone.
 *
 * A remote delete is only queued when an account is active: a recording id without an account could
 * otherwise be sent to whoever logs in next. The work queued for the recording is cancelled before
 * this runs, which is [SyncService]'s step.
 */
internal object RecordingDeleter {
  suspend fun delete(context: Context, localId: String?, remoteId: String?) {
    if (localId == null && remoteId == null) return
    val pending = context.appContainer.pendingDeleteStore
    val account = context.appContainer.remoteRepository.accountKey
    if (remoteId != null && account != null) pending.add(remoteId, account)

    if (localId != null) {
      context.appContainer.recordingStore.delete(localId)
      context.appContainer.resultCache.delete(account.orEmpty(), localId)
      context.appContainer.uploadStore.clear(localId)
      File(context.filesDir, "upload-cache/$localId").deleteRecursively()
    }
    if (remoteId != null && account != null) {
      context.appContainer.resultCache.delete(account, "remote-$remoteId")
      context.appContainer.uploadStore.clear("remote-$remoteId", account)
      UploadScheduler.enqueueDelete(context, remoteId)
    }
    RecordingRepository.refreshRecordings(context)
  }
}
