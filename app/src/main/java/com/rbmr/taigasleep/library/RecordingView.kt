package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.insights.StagerPreference
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.recordingIdOf
import com.rbmr.taigasleep.sync.remoteKey
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

/**
 * The one display state of a recording. It is derived from the two independent layers below:
 * where the bytes are ([BackupState]) and which analyses exist ([Analysis]).
 */
enum class RecordingStatus {
  /** The recorder is still writing this recording. */
  RECORDING,
  /** No server copy exists. */
  LOCAL_ONLY,
  /** An upload is queued or waiting for its network conditions. */
  QUEUED,
  /** Bytes are moving; see [RecordingView.progress]. */
  UPLOADING,
  /** The recording is on the server and an analysis is queued, running or not requested yet. */
  PROCESSING,
  /** At least one analysis finished and can be shown. */
  READY,
  /** The upload or the last analysis failed. */
  FAILED,
}

/** Where the recording's bytes exist. The phone and the server are copies, not stages. */
enum class BackupPhase {
  NOT_BACKED_UP,
  QUEUED,
  UPLOADING,
  BACKED_UP,
  FAILED,
}

data class BackupState(
    val phase: BackupPhase = BackupPhase.NOT_BACKED_UP,
    val progress: Float = 0f,
    val failure: UploadFailure? = null,
    val detail: String? = null,
    val warning: String? = null,
    val uploadedAtMs: Long? = null,
) {
  val isBackedUp: Boolean
    get() = phase == BackupPhase.BACKED_UP
}

enum class AnalysisStatus {
  QUEUED,
  RUNNING,
  DONE,
  FAILED,
}

/** Why a run exists: the automatic preference order or an explicit user request. */
enum class AnalysisOrigin {
  AUTO,
  MANUAL,
}

/** One requested result. It corresponds to exactly one result once it finishes. */
data class Analysis(
    val id: String,
    val algorithm: String,
    val version: String,
    val status: AnalysisStatus,
    val origin: AnalysisOrigin = AnalysisOrigin.AUTO,
    val error: String? = null,
    val epochSeconds: Int = 30,
    val segments: List<StageSegmentDto> = emptyList(),
    val sleepScore: Double? = null,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    val resultDownloaded: Boolean = false,
)

/** A snapshot served to the UI. The server copy is never required to draw a recording. */
data class RecordingView(
    val key: String,
    val localId: String?,
    val remoteId: String?,
    val startUtcMs: Long,
    val endUtcMs: Long?,
    val durationMs: Long,
    val device: String,
    val status: RecordingStatus,
    val progress: Float,
    val failure: UploadFailure?,
    val errorDetail: String?,
    val warning: String?,
    val analyses: List<Analysis>,
    val selectedAnalysisId: String?,
    val backup: BackupState,
    val local: Recording?,
    val remote: RecordingDto?,
    /** True only when the full payload of the selected analysis is cached on this phone. */
    val resultDownloaded: Boolean,
) {
  val selectedAnalysis: Analysis?
    get() = analyses.firstOrNull { it.id == selectedAnalysisId }

  val analysisId: String?
    get() = selectedAnalysis?.id

  val epochSeconds: Int
    get() = selectedAnalysis?.epochSeconds ?: 30

  val segments: List<StageSegmentDto>
    get() = selectedAnalysis?.segments.orEmpty()

  val sleepScore: Double?
    get() = selectedAnalysis?.sleepScore

  val model: String?
    get() = selectedAnalysis?.algorithm?.takeIf { it.isNotBlank() }

  val modelVersion: String?
    get() = selectedAnalysis?.version?.takeIf { it.isNotBlank() }

  val isDone: Boolean
    get() = status == RecordingStatus.READY

  val isRecording: Boolean
    get() = local?.state == RecordingPhase.RECORDING

  val onServer: Boolean
    get() = remoteId != null

  val onPhone: Boolean
    get() = local != null

  val hasRaw: Boolean
    get() = local?.hasRaw == true
}

/**
 * Merge the local recordings and the app's durable record state with what the analysis caches say a
 * recording shows. The server's list and the result cache are the only sources of analysis facts; the
 * sync record only says where the bytes are and whether they are moving.
 *
 * [overrides] carries a fresh recording and analysis list fetched by the recording screen, so a
 * recording with several analyses shows all of them before the next History refresh.
 *
 * [selectedAnalysisIds] is the run each recording shows, remembered by the cached History. It only
 * wins while it is one of the runs the caches list.
 *
 * [hiddenRemoteIds] are recordings whose server copy is being deleted or is already gone. Their
 * server summary, their fresh override and their cached runs are left out, so History cannot show
 * a recording that the server no longer has. A local recording stays as the local recording it is.
 *
 * The phone's recording id is the server's recording id, so a local recording and its server copy
 * are matched by id.
 */
fun buildRecordings(
    recordings: List<Recording>,
    records: Map<String, UploadRecord>,
    remote: RecordingSummaryListDto?,
    overrides: Map<String, RemoteOverride> = emptyMap(),
    hiddenRemoteIds: Set<String> = emptySet(),
    selectedAnalysisIds: Map<String, String> = emptyMap(),
    results: ResultCache? = null,
): List<RecordingView> {
  val byId = remote?.items?.associateBy { it.recording.id }.orEmpty()
  val localIds = recordings.map { it.id }.toSet()
  val merged = mutableListOf<RecordingView>()
  for (recording in recordings) {
    val hidden = recording.id in hiddenRemoteIds
    merged +=
        buildRecording(
            key = recording.id,
            local = recording,
            summary = byId[recording.id].takeUnless { hidden },
            record = records[recording.id],
            override = overrides[recording.id].takeUnless { hidden },
            selectedAnalysisId = selectedAnalysisIds[recording.id],
            results = results,
        )
  }
  for (summary in remote?.items.orEmpty()) {
    if (summary.recording.id in localIds) continue
    if (summary.recording.id in hiddenRemoteIds) continue
    val key = remoteKey(summary.recording.id)
    merged +=
        buildRecording(
            key = key,
            local = null,
            summary = summary,
            record = records[key],
            override = overrides[key],
            selectedAnalysisId = selectedAnalysisIds[key],
            results = results,
        )
  }
  // A recording opened directly by its recording id can be fresh before the next History refresh.
  for ((key, override) in overrides) {
    if (recordingIdOf(key) in hiddenRemoteIds) continue
    if (merged.none { it.key == key }) {
      merged +=
          buildRecording(
              key = key,
              local = null,
              summary = null,
              record = records[key],
              override = override,
              selectedAnalysisId = selectedAnalysisIds[key],
              results = results,
          )
    }
  }
  return merged.sortedWith(compareByDescending<RecordingView> { it.startUtcMs }.thenBy { it.device })
}

/** A fresh recording and analysis list from the server, kept only for the current process. */
data class RemoteOverride(
    val recording: RecordingDto,
    val runs: List<Analysis>,
)

private fun buildRecording(
    key: String,
    local: Recording?,
    summary: RecordingSummaryDto?,
    record: UploadRecord?,
    override: RemoteOverride?,
    selectedAnalysisId: String?,
    results: ResultCache?,
): RecordingView {
  val remoteSession = override?.recording ?: summary?.recording
  val start = local?.startUtcMs ?: parseApiDate(remoteSession?.started_at.orEmpty())
  val end =
      local?.endUtcMs
          ?: parseApiDate(remoteSession?.ended_at.orEmpty()).takeIf { it > start }
  val duration =
      local?.durationMs?.takeIf { it > 0 }
          ?: remoteSession?.duration_s?.times(1000.0)?.toLong()?.takeIf { it > 0 }
          ?: 0L
  val device =
      local?.sensorName?.takeIf { it.isNotBlank() }
          ?: remoteSession?.device?.takeIf { it.isNotBlank() }
          ?: ""
  val backupFromRecord = record?.backupState() ?: backupFromRemote(remoteSession)
  val remoteRuns =
      override?.runs
          ?: summary?.analyses
              ?.map { run -> run.toRun(downloaded = results?.has(key, run.id) == true) }
              .orEmpty()
  // The server's list has the runs. A recording whose list is not on this phone at all is drawn from
  // the result cache instead, so an upgrade or a logged-out start does not lose a result it had.
  // A recording the server does list is never filled in from the cache: a run it no longer lists is
  // gone, not hidden.
  val cachedRuns =
      if (summary == null && override == null) {
        results?.cached(key).orEmpty().map { run -> run.toRun(downloaded = true) }
      } else {
        remoteRuns
      }
  val runs =
      cachedRuns.map { run ->
        if (record?.manualAnalysisIds?.contains(run.id) == true) {
          run.copy(origin = AnalysisOrigin.MANUAL)
        } else {
          run
        }
      }
  // A finished result on the server proves the upload succeeded, even if the local record is stale.
  val backup =
      if (runs.any { it.status == AnalysisStatus.DONE } && remoteSession != null) {
        backupFromRecord.copy(
            phase = BackupPhase.BACKED_UP,
            progress = 1f,
            failure = null,
            detail = null,
        )
      } else {
        backupFromRecord
      }
  // A failed analysis leaves the upload untouched; the row reports the failure until a run says
  // otherwise. This is how a failure the server refused before starting a run is still shown.
  val analysisFailure =
      record?.takeIf {
        it.phase == UploadPhase.FAILED &&
            it.failure == UploadFailure.ANALYSIS &&
            runs.none { run ->
              run.status == AnalysisStatus.DONE || run.status == AnalysisStatus.FAILED
            }
      }
  val explicitId =
      selectedAnalysisId?.takeIf { id -> runs.any { it.id == id } }
  // A selected run that is still working or failed must not hide a finished result.
  val selectedId =
      explicitId?.takeIf { id -> runs.first { it.id == id }.status == AnalysisStatus.DONE }
          ?: defaultRun(runs)?.id
          ?: explicitId
  val selected = runs.firstOrNull { it.id == selectedId }
  val failedRun = runs.firstOrNull { it.status == AnalysisStatus.FAILED }
  val hasDone = runs.any { it.status == AnalysisStatus.DONE }
  val failure =
      backup.failure
          ?: if (hasDone) null
          else (failedRun?.let { UploadFailure.ANALYSIS } ?: analysisFailure?.failure)
  val errorDetail =
      backup.detail
          ?: failedRun?.error?.takeIf { it.isNotBlank() }
          ?: analysisFailure?.errorDetail
  val status =
      if (backup.phase == BackupPhase.BACKED_UP && analysisFailure != null) {
        RecordingStatus.FAILED
      } else {
        deriveStatus(local, backup, runs, remoteSession)
      }
  return RecordingView(
      key = key,
      localId = local?.id,
      remoteId =
          remoteSession?.id?.takeIf { it.isNotBlank() }
              ?: recordingIdOf(key).takeIf { record?.hasServerCopy() == true },
      startUtcMs = start,
      endUtcMs = end,
      durationMs = duration,
      device = device,
      status = status,
      progress = backup.progress.coerceIn(0f, 1f),
      failure = failure,
      errorDetail = errorDetail,
      warning = backup.warning,
      analyses = runs,
      selectedAnalysisId = selectedId,
      backup = backup,
      local = local,
      remote = remoteSession,
      resultDownloaded = selected?.resultDownloaded == true,
  )
}

private fun deriveStatus(
    local: Recording?,
    backup: BackupState,
    runs: List<Analysis>,
    remote: RecordingDto?,
): RecordingStatus {
  if (local?.state == RecordingPhase.RECORDING) return RecordingStatus.RECORDING
  return when (backup.phase) {
    BackupPhase.QUEUED -> RecordingStatus.QUEUED
    BackupPhase.UPLOADING -> RecordingStatus.UPLOADING
    BackupPhase.FAILED -> RecordingStatus.FAILED
    BackupPhase.NOT_BACKED_UP ->
        when {
          remote == null -> RecordingStatus.LOCAL_ONLY
          remote.complete -> deriveFromRuns(runs)
          // The recording exists on the server but is not sealed yet: its files are still moving.
          else -> RecordingStatus.UPLOADING
        }
    BackupPhase.BACKED_UP -> deriveFromRuns(runs)
  }
}

private fun deriveFromRuns(runs: List<Analysis>): RecordingStatus =
    when {
      runs.any { it.status == AnalysisStatus.DONE } -> RecordingStatus.READY
      runs.any { it.status == AnalysisStatus.QUEUED || it.status == AnalysisStatus.RUNNING } ->
          RecordingStatus.PROCESSING
      runs.any { it.status == AnalysisStatus.FAILED } -> RecordingStatus.FAILED
      // The upload is done; the analysis has either not been requested yet or is about to be.
      else -> RecordingStatus.PROCESSING
    }

private fun backupFromRemote(remote: RecordingDto?): BackupState =
    when {
      remote == null -> BackupState()
      // Sealing is the last change a recording gets, so its update time is when the upload ended.
      remote.complete ->
          BackupState(
              phase = BackupPhase.BACKED_UP,
              progress = 1f,
              uploadedAtMs = parseApiDate(remote.updated),
          )
      else -> BackupState(phase = BackupPhase.UPLOADING)
    }

private fun defaultRun(runs: List<Analysis>): Analysis? {
  if (runs.isEmpty()) return null
  val done = runs.filter { it.status == AnalysisStatus.DONE }
  if (done.isNotEmpty()) {
    return done.sortedWith(
            compareBy<Analysis> { if (it.origin == AnalysisOrigin.MANUAL) 0 else 1 }
                .thenBy { run ->
                  StagerPreference.indexOf(run.algorithm).let { if (it < 0) StagerPreference.size else it }
                }
                .thenByDescending { it.updatedAtMs }
        )
        .first()
  }
  return runs.maxByOrNull { it.updatedAtMs }
}

/**
 * A run as the app shows it. The stage timeline and the sleep score come from the full result when
 * it is present and from the summary a list carries otherwise; the server derives the second from
 * the first.
 */
fun AnalysisDto.toRun(downloaded: Boolean = false): Analysis {
  val stages = result?.stages
  val summarized = summary?.stages
  return Analysis(
      id = id,
      algorithm = algorithm,
      version = version,
      status = status.toAnalysisStatus(),
      error = error.takeIf { it.isNotBlank() },
      epochSeconds = stages?.epoch_seconds ?: summarized?.epoch_seconds ?: 30,
      segments = stages?.segments ?: summarized?.segments.orEmpty(),
      sleepScore =
          result?.items?.firstOrNull { it.id == "sleep_quality" }?.value
              ?: summary?.scores?.firstOrNull { it.id == "sleep_quality" }?.value,
      createdAtMs = parseApiDate(created),
      updatedAtMs = parseApiDate(updated),
      resultDownloaded = downloaded,
  )
}

private fun String.toAnalysisStatus(): AnalysisStatus =
    when (this) {
      "succeeded" -> AnalysisStatus.DONE
      "failed" -> AnalysisStatus.FAILED
      "running" -> AnalysisStatus.RUNNING
      else -> AnalysisStatus.QUEUED
    }

/** The API speaks RFC 3339 in UTC, for example `2026-10-24T05:50:01Z`. Unparseable is 0. */
fun parseApiDate(value: String): Long {
  if (value.isBlank()) return 0
  return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0)
}

fun formatRecordingDate(ms: Long): String =
    if (ms <= 0) "—"
    else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))

fun formatRecordingDateAndTime(ms: Long): String =
    if (ms <= 0) "—"
    else SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

fun formatDuration(ms: Long): String {
  if (ms <= 0) return "—"
  val totalSeconds = ms / 1000
  if (totalSeconds < 60) return "${totalSeconds}s"
  val totalMinutes = totalSeconds / 60
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return when {
    hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
    else -> if (hours > 0) "${hours}h" else "${minutes}m"
  }
}

/**
 * Where the bytes are according to the sync store. This is the only place that reads the upload
 * phase for display; the recording view then treats the phone and the server as two copies.
 */
internal fun UploadRecord.hasServerCopy(): Boolean =
    onServer || phase == UploadPhase.PROCESSING || phase == UploadPhase.DONE

internal fun UploadRecord.backupState(): BackupState =
    when (phase) {
      UploadPhase.LOCAL -> BackupState()
      UploadPhase.QUEUED ->
          BackupState(phase = BackupPhase.QUEUED, failure = failure, detail = errorDetail)
      UploadPhase.UPLOADING ->
          BackupState(
              phase = BackupPhase.UPLOADING,
              progress = progress,
              failure = failure,
              detail = errorDetail,
          )
      UploadPhase.PROCESSING,
      UploadPhase.DONE ->
          BackupState(
              phase = BackupPhase.BACKED_UP,
              progress = 1f,
              warning = warning,
              uploadedAtMs = uploadedAtMs ?: updatedAtMs,
          )
      UploadPhase.FAILED ->
          // An analysis that failed leaves the upload itself complete; the run says the rest.
          if (failure == UploadFailure.ANALYSIS) {
            BackupState(
                phase = BackupPhase.BACKED_UP,
                progress = 1f,
                warning = warning,
                uploadedAtMs = uploadedAtMs ?: updatedAtMs,
            )
          } else {
            BackupState(
                phase = BackupPhase.FAILED,
                failure = failure ?: UploadFailure.SERVER,
                detail = errorDetail,
                warning = warning,
            )
          }
    }
