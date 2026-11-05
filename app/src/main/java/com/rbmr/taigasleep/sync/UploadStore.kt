package com.rbmr.taigasleep.sync

import android.content.Context
import android.content.SharedPreferences
import com.rbmr.taigasleep.backend.StageSegmentDto
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
    val analysisId: String? = null,
    val phase: UploadPhase = UploadPhase.LOCAL,
    /** 0..1 for [UploadPhase.UPLOADING]; 1 once the upload is complete. */
    val progress: Float = 0f,
    val failure: UploadFailure? = null,
    /** A server or exception message, shown as the detail under the friendly error. */
    val errorDetail: String? = null,
    val warning: String? = null,
    val updatedAtMs: Long = 0,
    /** Enough of the result to draw the History row without a network call. */
    val epochSeconds: Int = 30,
    val segments: List<StageSegmentDto> = emptyList(),
    val sleepScore: Double? = null,
    val model: String? = null,
    val modelVersion: String? = null,
    /** True only when the full analysis payload is in the local result cache. */
    val resultDownloaded: Boolean = false,
    /**
     * When the server last confirmed that the upload is complete. Raw cleanup counts from here.
     * Null for a night that was never uploaded or whose state predates this field.
     */
    val uploadedAtMs: Long? = null,
    /**
     * The user downloaded this night's raw files again, or chose to keep them. Automatic cleanup
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
 * Where sync state is kept: one `sync.json` inside each recording directory, so it lives and dies
 * with the recording. A remote-only key has no directory, so its session-only state is not written.
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
    val confirmedAt = if (value >= 1f) observedAt else null
    update(id) {
      it.copy(
          phase = UploadPhase.UPLOADING,
          progress = value,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = observedAt,
          uploadedAtMs = it.uploadedAtMs ?: confirmedAt,
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
          phase = UploadPhase.LOCAL,
          progress = 0f,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = now(),
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

  /** The user downloaded this night's raw files again; keep them out of automatic cleanup. */
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
    @Volatile private var instance: UploadStore? = null

    fun of(context: Context): UploadStore =
        instance
            ?: synchronized(this) {
              instance
                  ?: UploadStore(
                          RecordingDirectoryStorage(
                              RecordingStore.of(context).root,
                              context.applicationContext.getSharedPreferences(
                                  "uploads",
                                  Context.MODE_PRIVATE,
                              ),
                          )
                      )
                      .also { instance = it }
            }
  }
}
