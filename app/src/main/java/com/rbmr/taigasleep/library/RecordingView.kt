package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.parseServerTime
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.insights.StagerPreference
import com.rbmr.taigasleep.insights.nightDate
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.sync.LibraryEntry
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.recordingIdOf
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
  /** The recording is on the server and an analysis is queued, running or being requested. */
  PROCESSING,
  /** The recording is on the server and no analysis exists for it. */
  NOT_ANALYZED,
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
)

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
 * Merge the recordings on this phone with the entries the database publishes for the active account.
 * An entry is one recording key with its sync state, the server's row and runs, the run it shows and
 * the payloads this phone holds, read in one query; the phone's recordings are not in the database,
 * so they are matched to an entry by their id.
 *
 * The server's row and the payloads are the only sources of analysis facts; the sync record only says
 * where the bytes are and whether they are moving. An entry that is [LibraryEntry.hidden] loses its
 * server row and its runs, so History cannot show a recording that the server no longer has, while a
 * recording that is still on this phone stays as the recording it is.
 *
 * The phone's recording id is the server's recording id, so a local recording and its server copy
 * are matched by id. A recording the phone does not have is its own row under its remote key.
 */
fun buildRecordings(recordings: List<Recording>, entries: List<LibraryEntry>): List<RecordingView> {
  val byKey = entries.associateBy { it.key }
  val localIds = recordings.map { it.id }.toSet()
  val merged = mutableListOf<RecordingView>()
  for (recording in recordings) {
    val entry = byKey[recording.id]
    merged +=
        buildRecording(
            key = recording.id,
            local = recording,
            entry = entry,
        )
  }
  for (entry in entries) {
    if (entry.key in localIds) continue
    // A key without a server row is a sync state or a payload whose recording is not here: it is
    // not a recording, so it is not a row in History.
    if (entry.recording == null || entry.hidden) continue
    merged += buildRecording(key = entry.key, local = null, entry = entry)
  }
  return merged.sortedWith(compareByDescending<RecordingView> { it.startUtcMs }.thenBy { it.device })
}

private fun buildRecording(
    key: String,
    local: Recording?,
    entry: LibraryEntry?,
): RecordingView {
  val shown = entry?.takeUnless { it.hidden }
  val remoteSession = shown?.recording
  val selectedAnalysisId = shown?.selectedAnalysisId
  val cached = entry?.cachedResults.orEmpty()
  val remoteRuns = shown?.runs.orEmpty()
  val record = entry?.record
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
  // The server's list has the runs. A recording whose list is not on this phone at all is drawn from
  // the cached payloads instead, so an upgrade or a logged-out start does not lose a result it had.
  // A recording the server does list is never filled in from the cache: a run it no longer lists is
  // gone, not hidden.
  val cachedRuns =
      if (remoteSession == null) {
        cached.map { run -> run.toRun(downloaded = true) }
      } else {
        remoteRuns.map { run -> run.toRun(downloaded = cached.any { it.id == run.id }) }
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
  // The app asked for an analysis that the server's list does not show yet.
  val analysisRequested = record?.phase == UploadPhase.PROCESSING
  val status =
      if (backup.phase == BackupPhase.BACKED_UP && analysisFailure != null) {
        RecordingStatus.FAILED
      } else {
        deriveStatus(local, backup, runs, remoteSession, analysisRequested)
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
    analysisRequested: Boolean,
): RecordingStatus {
  if (local?.state == RecordingPhase.RECORDING) return RecordingStatus.RECORDING
  return when (backup.phase) {
    BackupPhase.QUEUED -> RecordingStatus.QUEUED
    BackupPhase.UPLOADING -> RecordingStatus.UPLOADING
    BackupPhase.FAILED -> RecordingStatus.FAILED
    BackupPhase.NOT_BACKED_UP ->
        when {
          remote == null -> RecordingStatus.LOCAL_ONLY
          remote.complete -> deriveFromRuns(runs, analysisRequested)
          // The recording exists on the server but is not sealed yet: its files are still moving.
          else -> RecordingStatus.UPLOADING
        }
    BackupPhase.BACKED_UP -> deriveFromRuns(runs, analysisRequested)
  }
}

internal fun deriveFromRuns(runs: List<Analysis>, analysisRequested: Boolean): RecordingStatus =
    when {
      runs.any { it.status == AnalysisStatus.DONE } -> RecordingStatus.READY
      runs.any { it.status == AnalysisStatus.QUEUED || it.status == AnalysisStatus.RUNNING } ->
          RecordingStatus.PROCESSING
      runs.any { it.status == AnalysisStatus.FAILED } -> RecordingStatus.FAILED
      // No run exists: it is on its way only when the app has just asked for one.
      analysisRequested -> RecordingStatus.PROCESSING
      else -> RecordingStatus.NOT_ANALYZED
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

/** A server time in epoch milliseconds (see [parseServerTime]); unparseable is 0. */
fun parseApiDate(value: String): Long = parseServerTime(value)?.toEpochMilli() ?: 0

/**
 * The date a recording is filed under, e.g. `Sat 7 Nov`: the night it belongs to (see
 * [nightDate]), so a recording started just after midnight is dated by the evening before, the
 * same as in Trends.
 */
fun formatRecordingDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (ms <= 0) "-"
    else
        nightDate(ms, zone)
            .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

fun formatRecordingDateAndTime(ms: Long): String =
    if (ms <= 0) "-"
    else SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

/** The clock time of a recording, e.g. `23:12`. */
fun formatRecordingTime(ms: Long): String =
    if (ms <= 0) "-" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/**
 * Where the bytes are according to the sync store. This is the only place that reads the upload
 * phase for display; the recording view then treats the phone and the server as two copies.
 */
internal fun UploadRecord.hasServerCopy(): Boolean =
    onServer ||
        phase == UploadPhase.UPLOADED ||
        phase == UploadPhase.PROCESSING ||
        phase == UploadPhase.DONE

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
      UploadPhase.UPLOADED,
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
