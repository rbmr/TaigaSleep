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
 */
class UploadStore internal constructor(private val storage: UploadRecordsStorage) {
  private val lock = Any()
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  private val _records = MutableStateFlow(load())
  val records: StateFlow<Map<String, UploadRecord>> = _records.asStateFlow()

  init {
    migrateLegacy()
  }

  fun record(id: String): UploadRecord? = _records.value[id]

  fun markQueued(id: String, failure: UploadFailure? = null, detail: String? = null) {
    update(id) {
      it.copy(
          phase = UploadPhase.QUEUED,
          failure = failure,
          errorDetail = detail,
          warning = null,
          updatedAtMs = now(),
      )
    }
  }

  fun markOnServer(id: String) {
    update(id) { it.copy(onServer = true, updatedAtMs = now()) }
  }

  fun markUploading(id: String, progress: Float) {
    val value = progress.coerceIn(0f, 1f)
    val observedAt = now()
    // Bytes on the wire are not the server's word, not even the last ones: the recording counts as
    // uploaded when completeRecording succeeded, which is what markUploadConfirmed records.
    update(id) {
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
  fun markUploadConfirmed(id: String) {
    val confirmedAt = now()
    update(id) {
      it.copy(
          onServer = true,
          uploadedAtMs = it.uploadedAtMs ?: confirmedAt,
          confirmedOnServer = true,
          updatedAtMs = confirmedAt,
      )
    }
  }

  fun markProcessing(id: String) {
    val confirmedAt = now()
    update(id) {
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

  fun markDone(id: String) {
    val doneAt = now()
    update(id) {
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

  fun markFailed(id: String, failure: UploadFailure, detail: String? = null) {
    update(id) {
      it.copy(
          phase = UploadPhase.FAILED,
          failure = failure,
          errorDetail = detail,
          warning = null,
          updatedAtMs = now(),
      )
    }
  }

  fun markLocal(id: String) {
    update(id) {
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
  fun markManualAnalysis(id: String, analysisId: String) {
    update(id) {
      it.copy(
          manualAnalysisIds = (it.manualAnalysisIds + analysisId).distinct(),
          updatedAtMs = now(),
      )
    }
  }

  /** The user downloaded this recording's raw files again; keep them out of automatic cleanup. */
  fun markKeepRaw(id: String) {
    if (_records.value[id] == null) return
    update(id) { it.copy(keepRawOnPhone = true) }
  }

  fun clear(id: String) {
    synchronized(lock) {
      storage.delete(id)
      _records.value = _records.value - id
    }
  }

  private fun update(id: String, transform: (UploadRecord) -> UploadRecord) {
    synchronized(lock) {
      val current = _records.value[id] ?: UploadRecord(id = id)
      val next = transform(current)
      storage.write(id, json.encodeToString(next))
      _records.value = _records.value + (id to next)
    }
  }

  private fun load(): Map<String, UploadRecord> =
      storage.ids().mapNotNull { id ->
        val raw = storage.read(id) ?: return@mapNotNull null
        runCatching { id to json.decodeFromString<UploadRecord>(raw) }.getOrNull()
      }.toMap()

  /** Move the records of the old shared document next to their recordings, once. */
  private fun migrateLegacy() {
    val raw = storage.legacy() ?: return
    synchronized(lock) {
      val old =
          runCatching { json.decodeFromString<Map<String, UploadRecord>>(raw) }.getOrDefault(emptyMap())
      val next = _records.value.toMutableMap()
      for ((id, record) in old) {
        // Only a recording on this phone can hold its state there now.
        if (!storage.hasRecording(id)) continue
        val merged = next[id]?.let { current -> current.copyFrom(record) } ?: record
        storage.write(id, json.encodeToString(merged))
        next[id] = merged
      }
      _records.value = next
      storage.clearLegacy()
    }
  }

  private fun UploadRecord.copyFrom(old: UploadRecord): UploadRecord =
      if (updatedAtMs >= old.updatedAtMs) this else old

  private fun now(): Long = System.currentTimeMillis()

  companion object {
    fun of(context: Context): UploadStore = context.appContainer.uploadStore
  }
}
