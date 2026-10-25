package com.rbmr.taigasleep.upload

import android.content.Context
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.storage.SessionStore

/**
 * Deletes one recording everywhere. The local copy and cached results go immediately; the remote
 * session is queued and retried until the server is reachable. A 404 from the server is treated as
 * success, because the only useful meaning is that the session no longer exists for this account.
 */
object RecordingDeleter {
  fun delete(context: Context, localId: String?, remoteId: String?) {
    if (localId == null && remoteId == null) return
    val pending = PendingDeleteStore.of(context)
    if (remoteId != null) pending.add(remoteId)

    if (localId != null) {
      UploadScheduler.cancelRecordingWork(context, localId)
      SessionStore.of(context).delete(localId)
      ResultCache.of(context).delete(localId)
      UploadStore.of(context).clear(localId)
    }
    if (remoteId != null) {
      ResultCache.of(context).delete("remote-$remoteId")
      UploadStore.of(context).clear("remote-$remoteId")
      UploadScheduler.enqueueDelete(context, remoteId)
    }
    RecordingRepository.refreshSessions(context)
  }
}
