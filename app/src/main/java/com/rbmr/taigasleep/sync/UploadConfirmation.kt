package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.TaigaClient
import kotlinx.coroutines.CancellationException

/**
 * The check a record the phone left at full progress gets on the next sync. It only reads: a
 * recording the server has not finished is left to the upload queue, and one that cannot be asked
 * about stays unconfirmed, so raw cleanup keeps away from it.
 */
internal suspend fun confirmUploadOnServer(
    client: TaigaClient,
    id: String,
    store: UploadStore,
    account: String,
): Boolean {
  val complete =
      try {
        client.getRecording(id).complete
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        return false
      }
  if (!complete) return false
  store.markUploadConfirmed(id, account)
  return true
}
