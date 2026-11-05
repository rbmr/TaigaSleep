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
import com.rbmr.taigasleep.sync.recordingIdOf
import com.rbmr.taigasleep.sync.remoteKey
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

/**
 * The one display state of a night. It is derived from the two independent layers below:
 * where the bytes are ([BackupState]) and which analyses exist ([Analysis]).
 */
enum class RecordingStatus {
  /** The recorder is still writing this night. */
  RECORDING,
  /** No server copy exists. */
  LOCAL_ONLY,
  /** An upload is queued or waiting for its network conditions. */
  QUEUED,
  /** Bytes are moving; see [RecordingView.progress]. */
  UPLOADING,
  /** The night is on the server and an analysis is queued, running or not requested yet. */
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

/** A snapshot served to the UI. The server copy is never required to draw a night. */
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
 * Merge the local recordings, the app's night records and the cached server summaries. The server
 * wins when it has a finished analysis; the local state wins while the night is still being moved
 * or when the server is unreachable.
 *
 * [overrides] carries a fresh recording and analysis list fetched by the night screen, so a night
 * with several analyses shows all of them before the next History refresh.
 *
 * The phone's recording id is the server's recording id, so a local night and its server copy are
 * matched by id.
 */
fun buildRecordings(
    recordings: List<Recording>,
    records: Map<String, UploadRecord>,
    remote: RecordingSummaryListDto?,
    overrides: Map<String, RemoteOverride> = emptyMap(),
): List<RecordingView> {
  val byId = remote?.items?.associateBy { it.recording.id }.orEmpty()
  val localIds = recordings.map { it.id }.toSet()
  val nights = mutableListOf<RecordingView>()
  for (recording in recordings) {
    nights +=
        buildNight(
            key = recording.id,
            local = recording,
            summary = byId[recording.id],
            record = records[recording.id],
            override = overrides[recording.id],
        )
  }
  for (summary in remote?.items.orEmpty()) {
    if (summary.recording.id in localIds) continue
    val key = remoteKey(summary.recording.id)
    nights +=
        buildNight(
            key = key,
            local = null,
            summary = summary,
            record = records[key],
            override = overrides[key],
        )
  }
  // A night opened directly by its recording id can be fresh before the next History refresh.
  for ((key, override) in overrides) {
    if (nights.none { it.key == key }) {
      nights += buildNight(key, null, null, records[key], override)
    }
  }
  return nights.sortedWith(compareByDescending<RecordingView> { it.startUtcMs }.thenBy { it.device })
}

/** A fresh recording and analysis list from the server, kept only for the current process. */
data class RemoteOverride(
    val recording: RecordingDto,
    val runs: List<Analysis>,
)

private fun buildNight(
    key: String,
    local: Recording?,
    summary: RecordingSummaryDto?,
    record: UploadRecord?,
    override: RemoteOverride?,
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
          ?: summary?.analyses?.map { it.toRun() }.orEmpty()
  val runs =
      mergeRuns(listOfNotNull(record?.localAnalysis()), remoteRuns).map { run ->
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
  val explicitId =
      record?.analysisId?.takeIf { id -> runs.any { it.id == id } }
  // A selected run that is still working or failed must not hide a finished result.
  val selectedId =
      explicitId?.takeIf { id -> runs.first { it.id == id }.status == AnalysisStatus.DONE }
          ?: defaultRun(runs)?.id
          ?: explicitId
  val selected = runs.firstOrNull { it.id == selectedId }
  val failedRun = runs.firstOrNull { it.status == AnalysisStatus.FAILED }
  val hasDone = runs.any { it.status == AnalysisStatus.DONE }
  val failure =
      backup.failure ?: if (hasDone) null else failedRun?.let { UploadFailure.ANALYSIS }
  val errorDetail = backup.detail ?: failedRun?.error?.takeIf { it.isNotBlank() }
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
      status = deriveStatus(local, backup, runs, remoteSession),
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

private fun mergeRuns(base: List<Analysis>, fresh: List<Analysis>): List<Analysis> {
  if (base.isEmpty()) return fresh.sortedBy { it.createdAtMs }
  if (fresh.isEmpty()) return base.sortedBy { it.createdAtMs }
  val byId = LinkedHashMap<String, Analysis>()
  base.forEach { byId[it.id] = it }
  fresh.forEach { incoming ->
    val existing = byId[incoming.id]
    byId[incoming.id] =
        if (existing == null) {
          incoming
        } else {
          // The server owns the status and the result summary. The local record owns facts the
          // server does not know: why the run exists, and whether its payload is cached.
          incoming.copy(
              origin = existing.origin,
              resultDownloaded = incoming.resultDownloaded || existing.resultDownloaded,
              createdAtMs =
                  if (incoming.createdAtMs > 0) incoming.createdAtMs else existing.createdAtMs,
              updatedAtMs = maxOf(incoming.updatedAtMs, existing.updatedAtMs),
              error = incoming.error ?: existing.error,
              segments = if (incoming.segments.isNotEmpty()) incoming.segments else existing.segments,
              sleepScore = incoming.sleepScore ?: existing.sleepScore,
              epochSeconds =
                  if (incoming.segments.isNotEmpty()) incoming.epochSeconds else existing.epochSeconds,
          )
        }
  }
  return byId.values.sortedBy { it.createdAtMs }
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
  val totalMinutes = ms / 60_000
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
    onServer || phase == UploadPhase.PROCESSING || phase == UploadPhase.DONE || analysisId != null

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
          if (failure == UploadFailure.ANALYSIS && analysisId != null) {
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

/**
 * The one run the sync store remembers locally, enough for a History row before the server is read.
 * The full list comes from the server; [backupState] and this are the two adapters that keep the
 * legacy record from leaking into the view.
 */
internal fun UploadRecord.localAnalysis(): Analysis? {
  val runStatus =
      when (phase) {
        UploadPhase.DONE -> AnalysisStatus.DONE
        UploadPhase.FAILED -> if (failure == UploadFailure.ANALYSIS) AnalysisStatus.FAILED else null
        UploadPhase.PROCESSING -> AnalysisStatus.RUNNING
        else -> null
      }
  val id = analysisId ?: return null
  val status = runStatus ?: return null
  return Analysis(
      id = id,
      algorithm = model.orEmpty(),
      version = modelVersion.orEmpty(),
      status = status,
      error = errorDetail.takeIf { failure == UploadFailure.ANALYSIS },
      epochSeconds = epochSeconds,
      segments = segments,
      sleepScore = sleepScore,
      updatedAtMs = updatedAtMs,
      resultDownloaded = resultDownloaded,
  )
}
