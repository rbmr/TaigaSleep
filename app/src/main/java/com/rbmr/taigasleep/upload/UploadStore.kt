package com.rbmr.taigasleep.upload

import android.content.Context
import android.content.SharedPreferences
import com.rbmr.taigasleep.remote.StageSegmentDto
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
}

@Serializable
data class UploadRecord(
    val id: String,
    val remoteId: String? = null,
    val analysisId: String? = null,
    val jobId: String? = null,
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
    val qualityScore: Double? = null,
    val model: String? = null,
    val modelVersion: String? = null,
    /** True only when the full analysis payload is in the local result cache. */
    val resultDownloaded: Boolean = false,
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
 * Persisted status for every local recording. The backend is authoritative for the bytes and the
 * analysis; this store is the app's durable view so History survives process death and an upload
 * can resume where it stopped.
 */
class UploadStore private constructor(private val prefs: SharedPreferences) {
  private val lock = Any()
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  private val _records = MutableStateFlow(load())
  val records: StateFlow<Map<String, UploadRecord>> = _records.asStateFlow()

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

  fun markRemote(id: String, remoteId: String) {
    update(id) { it.copy(remoteId = remoteId, updatedAtMs = now()) }
  }

  fun markUploading(id: String, progress: Float) {
    update(id) {
      it.copy(
          phase = UploadPhase.UPLOADING,
          progress = progress.coerceIn(0f, 1f),
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = now(),
      )
    }
  }

  fun markProcessing(
      id: String,
      remoteId: String,
      analysisId: String?,
      jobId: String?,
      warning: String? = null,
  ) {
    update(id) {
      it.copy(
          phase = UploadPhase.PROCESSING,
          remoteId = remoteId,
          analysisId = analysisId,
          jobId = jobId,
          progress = 1f,
          failure = null,
          errorDetail = null,
          warning = warning,
          updatedAtMs = now(),
      )
    }
  }

  fun markDone(
      id: String,
      remoteId: String,
      analysisId: String?,
      jobId: String?,
      model: String?,
      modelVersion: String?,
      epochSeconds: Int,
      segments: List<StageSegmentDto>,
      qualityScore: Double?,
      resultDownloaded: Boolean,
  ) {
    update(id) {
      it.copy(
          phase = UploadPhase.DONE,
          remoteId = remoteId,
          analysisId = analysisId,
          jobId = jobId,
          progress = 1f,
          failure = null,
          errorDetail = null,
          warning = null,
          updatedAtMs = now(),
          model = model,
          modelVersion = modelVersion,
          epochSeconds = epochSeconds,
          segments = segments,
          qualityScore = qualityScore,
          resultDownloaded = resultDownloaded,
      )
    }
  }

  fun markProcessingRetry(id: String, failure: UploadFailure, detail: String? = null) {
    update(id) {
      it.copy(
          phase = UploadPhase.PROCESSING,
          failure = failure,
          errorDetail = detail,
          progress = 1f,
          updatedAtMs = now(),
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

  fun clear(id: String) {
    synchronized(lock) {
      val next = _records.value - id
      persist(next)
      _records.value = next
    }
  }

  private fun update(id: String, transform: (UploadRecord) -> UploadRecord) {
    synchronized(lock) {
      val current = _records.value[id] ?: UploadRecord(id = id)
      val next = _records.value + (id to transform(current))
      persist(next)
      _records.value = next
    }
  }

  private fun persist(records: Map<String, UploadRecord>) {
    prefs.edit().putString(KEY_RECORDS, json.encodeToString<Map<String, UploadRecord>>(records)).apply()
  }

  private fun load(): Map<String, UploadRecord> {
    val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyMap()
    return runCatching { json.decodeFromString<Map<String, UploadRecord>>(raw) }.getOrDefault(emptyMap())
  }

  private fun now(): Long = System.currentTimeMillis()

  companion object {
    private const val KEY_RECORDS = "records"
    @Volatile private var instance: UploadStore? = null

    fun of(context: Context): UploadStore =
        instance
            ?: synchronized(this) {
              instance
                  ?: UploadStore(
                          context.applicationContext.getSharedPreferences("uploads", Context.MODE_PRIVATE)
                      )
                      .also { instance = it }
            }
  }
}
