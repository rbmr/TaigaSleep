package com.rbmr.taigasleep.sync

import android.content.Context
import android.content.SharedPreferences
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.recording.store.RecordingStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** The lifecycle of one local recording on its way to a result. */
enum class UploadPhase {
  /** The recording exists only on this phone. */
  LOCAL,
  /** Queued for an upload, or waiting for its network constraints. */
  QUEUED,
  /** Bytes are moving; [UploadRecord.progress] is between 0 and 1. */
  UPLOADING,
  /** The upload is complete and the analysis has been requested. */
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
) {
  val isPending: Boolean
    get() =
        phase == UploadPhase.LOCAL ||
            phase == UploadPhase.QUEUED ||
            phase == UploadPhase.UPLOADING ||
            phase == UploadPhase.PROCESSING ||
            phase == UploadPhase.FAILED
}

/**
 * When the server confirmed that this recording is complete, or null while it has not. Full
 * progress is not enough: the phases the server's answers set are evidence on their own, and a
 * record the phone left at full progress is checked with the server before anything trusts it.
 */
internal fun UploadRecord.uploadConfirmedAt(): Long? {
  if (confirmedOnServer) return finishedAt()
  // Records written before the confirmation was recorded on its own.
  return when (phase) {
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
 * Where sync state is kept: one `sync.json` inside each recording directory, so it lives and dies
 * with the recording. A remote-only key has no directory, so its recording-only state is not written.
 */
interface UploadRecordsStorage {
  fun ids(): Set<String>

  fun read(id: String): String?

  fun write(id: String, json: String)

  fun delete(id: String)

  /** Whether this id is a recording on this phone, and can therefore hold its own sync file. */
  fun hasRecording(id: String): Boolean = true

  /** The old single-document format, read once so an upgrade does not lose queued uploads. */
  fun legacy(): String? = null

  fun clearLegacy() {}
}

private const val KEY_RECORDS = "records"
private const val SYNC_FILE_TMP_SUFFIX = ".tmp"

internal class RecordingDirectoryStorage(
    private val root: File,
    private val prefs: SharedPreferences? = null,
) : UploadRecordsStorage {
  override fun ids(): Set<String> =
      root.listFiles { file -> file.isDirectory && File(file, RecordingStore.SYNC_FILE).isFile }
          ?.map { it.name }
          ?.toSet()
          .orEmpty()

  override fun read(id: String): String? {
    val file = File(root, "$id/${RecordingStore.SYNC_FILE}")
    return if (file.isFile) file.readText() else null
  }

  override fun write(id: String, json: String) {
    val dir = File(root, id)
    // Remote-only recordings are not local recordings; their view comes from the server cache.
    if (!dir.isDirectory) return
    val file = File(dir, RecordingStore.SYNC_FILE)
    val tmp = File(dir, RecordingStore.SYNC_FILE + SYNC_FILE_TMP_SUFFIX)
    tmp.writeText(json)
    if (!tmp.renameTo(file)) {
      file.writeText(json)
      tmp.delete()
    }
  }

  override fun delete(id: String) {
    File(root, "$id/${RecordingStore.SYNC_FILE}").delete()
  }

  override fun hasRecording(id: String): Boolean = File(root, id).isDirectory

  override fun legacy(): String? = prefs?.getString(KEY_RECORDS, null)

  override fun clearLegacy() {
    prefs?.edit()?.remove(KEY_RECORDS)?.apply()
  }
}

/**
 * Persisted status for every local recording. The backend is authoritative for the bytes and the
 * analysis; this store is the app's durable view so History survives process death and an upload
 * can resume where it stopped.
 *
 * Every fact in a record belongs to one account: the server copy, its confirmation, the upload work
 * itself and the requested analyses. The rows are keyed by account, so an account never sees another
 * one's state and switching back shows the first one's again. A row written before accounts existed
 * is adopted by the account that is active the first time this version sees one.
 */
class UploadStore internal constructor(
    private val storage: UploadRecordsStorage,
    private val account: () -> String? = { null },
) {
  private val lock = Any()
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  /** Recording id -> account key -> the row written for that account. */
  private val rows = mutableMapOf<String, MutableMap<String, UploadRecord>>()

  private val _records = MutableStateFlow<Map<String, UploadRecord>>(emptyMap())
  val records: StateFlow<Map<String, UploadRecord>> = _records.asStateFlow()

  init {
    load()
    migrateLegacy()
    refreshAccount()
  }

  /** The account whose rows are read and written, or "" while there is no session. */
  private fun active(): String = account().orEmpty()

  /** The row for one recording, by default the active account's. */
  fun record(id: String, account: String? = null): UploadRecord? {
    synchronized(lock) {
      val target = account ?: active()
      return rows[id]?.get(target)
    }
  }

  /** Another account that already has a server copy of this recording, or null. */
  fun otherAccount(id: String): String? {
    synchronized(lock) {
      val active = active()
      return rows[id]
          ?.entries
          ?.firstOrNull { (key, record) ->
            key.isNotBlank() && key != active && (record.onServer || record.confirmedOnServer)
          }
          ?.key
    }
  }

  /** The active account changed: drop the unscoped rows into it and publish its rows. */
  fun refreshAccount() {
    synchronized(lock) {
      val target = active()
      if (target.isNotBlank()) adoptUnscoped(target)
      _records.value = activeRows(target)
    }
  }

  fun markQueued(
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

  fun markOnServer(id: String, account: String? = null) {
    update(id, account) { it.copy(onServer = true, updatedAtMs = now()) }
  }

  fun markUploading(id: String, progress: Float, account: String? = null) {
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
  fun markUploadConfirmed(id: String, account: String? = null) {
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

  fun markProcessing(id: String, account: String? = null) {
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

  fun markDone(id: String, account: String? = null) {
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

  fun markFailed(
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

  fun markLocal(id: String, account: String? = null) {
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
  fun markManualAnalysis(id: String, analysisId: String, account: String? = null) {
    update(id, account) {
      it.copy(
          manualAnalysisIds = (it.manualAnalysisIds + analysisId).distinct(),
          updatedAtMs = now(),
      )
    }
  }

  /** The user downloaded this recording's raw files again; keep them out of automatic cleanup. */
  fun markKeepRaw(id: String, account: String? = null) {
    synchronized(lock) {
      val target = account ?: active()
      if (rows[id]?.get(target) == null) return
    }
    update(id, account) { it.copy(keepRawOnPhone = true) }
  }

  /** Drops the active account's row; the recording itself is not touched. */
  fun clear(id: String, account: String? = null) {
    synchronized(lock) {
      val target = account ?: active()
      val byAccount = rows[id] ?: return
      byAccount.remove(target)
      if (byAccount.isEmpty()) {
        rows.remove(id)
        storage.delete(id)
      } else {
        write(id)
      }
      publish()
    }
  }

  private fun update(
      id: String,
      account: String? = null,
      transform: (UploadRecord) -> UploadRecord,
  ) {
    synchronized(lock) {
      val target = account ?: active()
      val current = rows[id]?.get(target) ?: UploadRecord(id = id)
      val next = transform(current)
      rows.getOrPut(id) { mutableMapOf() }[target] = next
      write(id)
      publish()
    }
  }

  private fun publish() {
    val target = active()
    _records.value = activeRows(target)
  }

  private fun activeRows(target: String): Map<String, UploadRecord> =
      rows.mapNotNull { (id, byAccount) -> byAccount[target]?.let { id to it } }.toMap()

  /** The unscoped rows of an earlier version belong to the account that is logged in now. */
  private fun adoptUnscoped(target: String) {
    var changed = false
    for ((id, byAccount) in rows) {
      val unscoped = byAccount.remove(UNSCOPED) ?: continue
      if (byAccount[target] == null) byAccount[target] = unscoped
      write(id)
      changed = true
    }
    if (changed) publish()
  }

  private fun load() {
    synchronized(lock) {
      for (id in storage.ids()) {
        val raw = storage.read(id) ?: continue
        val element =
            runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: continue
        val stored =
            if (KEY_ACCOUNTS in element) {
              runCatching { json.decodeFromJsonElement<StoredRecords>(element) }.getOrNull()
                  ?: continue
            } else {
              val legacy =
                  runCatching { json.decodeFromString<UploadRecord>(raw) }.getOrNull() ?: continue
              StoredRecords(mapOf(UNSCOPED to legacy))
            }
        rows[id] = stored.accounts.toMutableMap()
      }
    }
  }

  private fun write(id: String) {
    val byAccount = rows[id] ?: return
    storage.write(id, json.encodeToString(StoredRecords(byAccount)))
  }

  /** Move the records of the old shared document next to their recordings, once. */
  private fun migrateLegacy() {
    val raw = storage.legacy() ?: return
    synchronized(lock) {
      val old =
          runCatching { json.decodeFromString<Map<String, UploadRecord>>(raw) }.getOrDefault(emptyMap())
      for ((id, record) in old) {
        // Only a recording on this phone can hold its state there now.
        if (!storage.hasRecording(id)) continue
        val byAccount = rows.getOrPut(id) { mutableMapOf() }
        val at = if (byAccount.isEmpty()) UNSCOPED else byAccount.keys.first()
        val merged = byAccount[at]?.let { current -> current.copyFrom(record) } ?: record
        byAccount[at] = merged
        write(id)
      }
      storage.clearLegacy()
    }
  }

  private fun UploadRecord.copyFrom(old: UploadRecord): UploadRecord =
      if (updatedAtMs >= old.updatedAtMs) this else old

  private fun now(): Long = System.currentTimeMillis()

  companion object {
    /**
     * The key of the state written before facts carried an account. It can never be a real account
     * key, which holds a server URL. Such state is adopted by the active account and never shown
     * while there is none.
     */
    private const val UNSCOPED = "unscoped"
    private const val KEY_ACCOUNTS = "accounts"

    fun of(context: Context): UploadStore = context.appContainer.uploadStore
  }
}

/**
 * The account-keyed document of one recording. It is a small map so it ports directly to a table
 * keyed by account and recording id.
 */
@Serializable
private data class StoredRecords(val accounts: Map<String, UploadRecord> = emptyMap())

