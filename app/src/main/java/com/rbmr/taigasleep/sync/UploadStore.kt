package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.sync.db.UploadStateDao
import com.rbmr.taigasleep.sync.db.toRow
import com.rbmr.taigasleep.sync.db.toUploadRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** The lifecycle of one local recording on its way to a result. */
enum class UploadPhase {
  /** The recording exists only on this phone. */
  LOCAL,
  /** Queued for an upload, or waiting for its network constraints. */
  QUEUED,
  /** Bytes are moving; [UploadRecord.progress] is between 0 and 1. */
  UPLOADING,
  /** The upload is complete and no analysis has been requested for it. */
  UPLOADED,
  /** The upload is complete and an analysis has been requested. */
  PROCESSING,
  /** A result is available. */
  DONE,
  /** The last attempt failed and needs a retry. */
  FAILED,
}

enum class UploadFailure {
  LOGIN,
  NETWORK,
  SERVER,
  STORAGE,
  ANALYSIS,
  RECORDING,
  /** The app and the server speak different API versions; one of them has to be updated. */
  VERSION,
}

@Serializable
data class UploadRecord(
    val id: String,
    /** The server has a copy of this recording, which it keeps under [id]. */
    val onServer: Boolean = false,
    val phase: UploadPhase = UploadPhase.LOCAL,
    /** 0..1 for [UploadPhase.UPLOADING]; 1 when every byte is out, which says nothing yet. */
    val progress: Float = 0f,
    val failure: UploadFailure? = null,
    /** A server or exception message, shown as the detail under the friendly error. */
    val errorDetail: String? = null,
    val warning: String? = null,
    val updatedAtMs: Long = 0,
    /**
     * When the upload finished, as the phone observed it. Raw cleanup counts from here, and only
     * for a record the server confirmed; see [confirmedOnServer].
     */
    val uploadedAtMs: Long? = null,
    /**
     * The server reported this recording complete. Only [UploadStore.markUploadConfirmed] and the
     * phases the server's answers set write it, never full progress, so cleanup cannot trust an
     * upload the server did not accept.
     */
    val confirmedOnServer: Boolean = false,
    /**
     * The user downloaded this recording's raw files again, or chose to keep them. Automatic cleanup
     * leaves it alone until the user deletes the raw files explicitly.
     */
    val keepRawOnPhone: Boolean = false,
    /** Analyses the user requested explicitly. A finished manual run wins the default selection. */
    val manualAnalysisIds: List<String> = emptyList(),
)

/**
 * When the server confirmed that this recording is complete, or null while it has not. Full
 * progress is not enough: the phases the server's answers set are evidence on their own, and a
 * record the phone left at full progress is checked with the server before anything trusts it.
 */
internal fun UploadRecord.uploadConfirmedAt(): Long? {
  if (confirmedOnServer) return finishedAt()
  // Records written before the confirmation was recorded on its own.
  return when (phase) {
    UploadPhase.UPLOADED,
    UploadPhase.PROCESSING,
    UploadPhase.DONE -> finishedAt()
    // A failed analysis leaves the upload itself complete; the run says the rest.
    UploadPhase.FAILED -> if (failure == UploadFailure.ANALYSIS) finishedAt() else null
    UploadPhase.LOCAL,
    UploadPhase.QUEUED,
    UploadPhase.UPLOADING -> null
  }
}

/**
 * True for a record left in an attempt that reached full progress: the old behaviour recorded a
 * confirmation time there, so the phone has to ask the server before anything trusts the upload.
 */
internal fun UploadRecord.awaitsUploadConfirmation(): Boolean =
    (phase == UploadPhase.UPLOADING || phase == UploadPhase.FAILED) &&
        !confirmedOnServer &&
        progress >= 1f &&
        uploadConfirmedAt() == null

private fun UploadRecord.finishedAt(): Long? = uploadedAtMs ?: updatedAtMs.takeIf { it > 0 }

/**
 * Persisted status for every local recording. The backend is authoritative for the bytes and the
 * analysis; this store is the app's durable view so History survives process death and an upload
 * can resume where it stopped.
 *
 * The rows live in [com.rbmr.taigasleep.sync.db.TaigaDatabase], one per recording and account, so no
 * file is read or written here. Every fact in a record belongs to one account: the server copy, its
 * confirmation, the upload work itself and the requested analyses. A record therefore never shows
 * another account's state, and switching back shows the first one's again.
 *
 * [records] is the active account's rows as the database publishes them, and is what History and the
 * recording screen observe. Reads and writes are suspend, so they run beside the caller's work
 * instead of on the thread that draws.
 */
class UploadStore internal constructor(
    private val states: UploadStateDao,
    private val accounts: StateFlow<String?>,
    scope: CoroutineScope,
) {
  /** The rows of every recording the database holds, for the account that is active now. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val records: StateFlow<Map<String, UploadRecord>> =
      accounts
          .flatMapLatest { states.observe(it.orEmpty()) }
          .map { rows -> rows.associate { it.recordingId to it.toUploadRecord() } }
          .stateIn(scope, SharingStarted.Eagerly, emptyMap())

  /** Serialises the read-modify-write of one row, so two steps of one upload cannot lose each other. */
  private val writes = Mutex()

  /** The account whose rows are read and written, or "" while there is no session. */
  private fun active(): String = accounts.value.orEmpty()

  /** The row for one recording, by default the active account's. */
  suspend fun record(id: String, account: String? = null): UploadRecord? {
    return states.row(account ?: active(), id)?.toUploadRecord()
  }

  /** Another account that already has a server copy of this recording, or null. */
  suspend fun otherAccount(id: String): String? {
    return states.otherAccounts(id, active()).firstOrNull()
  }

  suspend fun markQueued(
      id: String,
      failure: UploadFailure? = null,
      detail: String? = null,
      account: String? = null,
  ) {
    update(id, account) {
      it.copy(
          phase = UploadPhase.QUEUED,
          failure = failure,
          errorDetail = detail,
          warning = null,
          updatedAtMs = now(),
      )
    }
  }

  suspend fun markOnServer(id: String, account: String? = null) {
    update(id, account) { it.copy(onServer = true, updatedAtMs = now()) }
  }

  suspend fun markUploading(id: String, progress: Float, account: String? = null) {
    val value = progress.coerceIn(0f, 1f)
    val observedAt = now()
    // Bytes on the wire are not the server's word, not even the last ones: the recording counts as
    // uploaded when completeRecording succeeded, which is what markUploadConfirmed records.
    update(id, account) {
      it.copy(
          phase = UploadPhase.UPLOADING,
          progress = value,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = observedAt,
      )
    }
  }

  /** The server reported this recording complete: a finished upload, or a status check that found one. */
  suspend fun markUploadConfirmed(id: String, account: String? = null) {
    val confirmedAt = now()
    update(id, account) {
      it.copy(
          onServer = true,
          uploadedAtMs = it.uploadedAtMs ?: confirmedAt,
          confirmedOnServer = true,
          updatedAtMs = confirmedAt,
      )
    }
  }

  /** The server has every byte and no analysis was requested, so none is expected. */
  suspend fun markUploaded(id: String, account: String? = null) {
    val confirmedAt = now()
    update(id, account) {
      it.copy(
          phase = UploadPhase.UPLOADED,
          onServer = true,
          progress = 1f,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = confirmedAt,
          uploadedAtMs = it.uploadedAtMs ?: confirmedAt,
          confirmedOnServer = true,
      )
    }
  }

  suspend fun markProcessing(id: String, account: String? = null) {
    val confirmedAt = now()
    update(id, account) {
      it.copy(
          phase = UploadPhase.PROCESSING,
          onServer = true,
          progress = 1f,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = confirmedAt,
          uploadedAtMs = it.uploadedAtMs ?: confirmedAt,
          confirmedOnServer = true,
      )
    }
  }

  suspend fun markDone(id: String, account: String? = null) {
    val doneAt = now()
    update(id, account) {
      it.copy(
          phase = UploadPhase.DONE,
          onServer = true,
          progress = 1f,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = doneAt,
          uploadedAtMs = it.uploadedAtMs ?: doneAt,
          confirmedOnServer = true,
      )
    }
  }

  suspend fun markFailed(
      id: String,
      failure: UploadFailure,
      detail: String? = null,
      account: String? = null,
  ) {
    update(id, account) {
      it.copy(
          phase = UploadPhase.FAILED,
          failure = failure,
          errorDetail = detail,
          warning = null,
          updatedAtMs = now(),
      )
    }
  }

  suspend fun markLocal(id: String, account: String? = null) {
    update(id, account) {
      it.copy(
          onServer = false,
          phase = UploadPhase.LOCAL,
          progress = 0f,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = now(),
          uploadedAtMs = null,
          confirmedOnServer = false,
      )
    }
  }

  /** Remember that the user asked for this analysis themselves. */
  suspend fun markManualAnalysis(id: String, analysisId: String, account: String? = null) {
    update(id, account) {
      it.copy(
          manualAnalysisIds = (it.manualAnalysisIds + analysisId).distinct(),
          updatedAtMs = now(),
      )
    }
  }

  /** The user downloaded this recording's raw files again; keep them out of automatic cleanup. */
  suspend fun markKeepRaw(id: String, account: String? = null) {
    val target = account ?: active()
    writes.withLock {
      val current = states.row(target, id)?.toUploadRecord() ?: return
      states.upsert(current.copy(keepRawOnPhone = true).toRow(target))
    }
  }

  /** Drops the row of one recording for the active account; the recording itself is not touched. */
  suspend fun clear(id: String, account: String? = null) {
    states.delete(account ?: active(), id)
  }

  private suspend fun update(
      id: String,
      account: String? = null,
      transform: (UploadRecord) -> UploadRecord,
  ) {
    val target = account ?: active()
    writes.withLock {
      val current = states.row(target, id)?.toUploadRecord() ?: UploadRecord(id = id)
      states.upsert(transform(current).toRow(target))
    }
  }

  private fun now(): Long = System.currentTimeMillis()

  companion object {
    fun of(context: Context): UploadStore = context.appContainer.uploadStore
  }
}
