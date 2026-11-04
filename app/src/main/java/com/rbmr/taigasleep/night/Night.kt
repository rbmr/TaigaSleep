package com.rbmr.taigasleep.night

import com.rbmr.taigasleep.analysis.StagerPreference
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.AnalysisSummaryDto
import com.rbmr.taigasleep.remote.SessionDto
import com.rbmr.taigasleep.remote.SessionListDto
import com.rbmr.taigasleep.remote.SessionSummaryDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.upload.UploadFailure
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The one display state of a night. It is derived from the two independent layers below:
 * where the bytes are ([BackupState]) and which analyses exist ([AnalysisRun]).
 */
enum class NightStatus {
  /** The recorder is still writing this night. */
  RECORDING,
  /** No server copy exists. */
  LOCAL_ONLY,
  /** An upload is queued or waiting for its network conditions. */
  QUEUED,
  /** Bytes are moving; see [Night.progress]. */
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
data class AnalysisRun(
    val id: String,
    val algorithm: String,
    val version: String,
    val status: AnalysisStatus,
    val origin: AnalysisOrigin = AnalysisOrigin.AUTO,
    val error: String? = null,
    val jobId: String? = null,
    val epochSeconds: Int = 30,
    val segments: List<StageSegmentDto> = emptyList(),
    val sleepScore: Double? = null,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    val resultDownloaded: Boolean = false,
)

/** The durable part of a night that the app owns. Raw bytes live beside it as files. */
data class NightRecord(
    val id: String,
    val remoteId: String? = null,
    val backup: BackupState = BackupState(),
    val analyses: List<AnalysisRun> = emptyList(),
    val selectedAnalysisId: String? = null,
    val keepRawOnPhone: Boolean = false,
    val manualAnalysisIds: List<String> = emptyList(),
    val updatedAtMs: Long = 0,
)

/** A snapshot served to the UI. The server copy is never required to draw a night. */
data class Night(
    val key: String,
    val localId: String?,
    val remoteId: String?,
    val startUtcMs: Long,
    val endUtcMs: Long?,
    val durationMs: Long,
    val device: String,
    val status: NightStatus,
    val progress: Float,
    val failure: UploadFailure?,
    val errorDetail: String?,
    val warning: String?,
    val analyses: List<AnalysisRun>,
    val selectedAnalysisId: String?,
    val backup: BackupState,
    val local: SessionInfo?,
    val remote: SessionDto?,
    /** True only when the full payload of the selected analysis is cached on this phone. */
    val resultDownloaded: Boolean,
) {
  val selectedAnalysis: AnalysisRun?
    get() = analyses.firstOrNull { it.id == selectedAnalysisId }

  val analysisId: String?
    get() = selectedAnalysis?.id

  val jobId: String?
    get() = selectedAnalysis?.jobId

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
    get() = status == NightStatus.READY

  val isRecording: Boolean
    get() = local?.state == SessionState.RECORDING

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
 * [overrides] carries a fresh session detail fetched by the night screen, so a night with several
 * analyses shows all of them before the next History refresh.
 */
fun buildNights(
    localSessions: List<SessionInfo>,
    records: Map<String, NightRecord>,
    remote: SessionListDto?,
    overrides: Map<String, NightRemoteOverride> = emptyMap(),
): List<Night> {
  val byClient = remote?.items?.associateBy { it.session.client_session_id }.orEmpty()
  val localIds = localSessions.map { it.id }.toSet()
  val nights = mutableListOf<Night>()
  for (session in localSessions) {
    nights +=
        buildNight(
            key = session.id,
            local = session,
            summary = byClient[session.id],
            record = records[session.id],
            override = overrides[session.id],
        )
  }
  for (summary in remote?.items.orEmpty()) {
    val client = summary.session.client_session_id
    if (client in localIds) continue
    val key = "remote-${summary.session.id}"
    nights +=
        buildNight(
            key = key,
            local = null,
            summary = summary,
            record = records[key],
            override = overrides[key],
        )
  }
  // A night opened directly by its remote id can be fresh before the next History refresh.
  for ((key, override) in overrides) {
    if (nights.none { it.key == key }) {
      nights += buildNight(key, null, null, records[key], override)
    }
  }
  return nights.sortedWith(compareByDescending<Night> { it.startUtcMs }.thenBy { it.device })
}

/** A fresh session detail from the server, kept only for the current process. */
data class NightRemoteOverride(
    val session: SessionDto,
    val runs: List<AnalysisRun>,
)

private fun buildNight(
    key: String,
    local: SessionInfo?,
    summary: SessionSummaryDto?,
    record: NightRecord?,
    override: NightRemoteOverride?,
): Night {
  val remoteSession = override?.session ?: summary?.session
  val start = local?.startUtcMs ?: parsePocketBaseDate(remoteSession?.started_at.orEmpty())
  val end =
      local?.endUtcMs
          ?: parsePocketBaseDate(remoteSession?.ended_at.orEmpty()).takeIf { it > start }
  val duration =
      local?.durationMs?.takeIf { it > 0 }
          ?: remoteSession?.duration_s?.times(1000.0)?.toLong()?.takeIf { it > 0 }
          ?: 0L
  val device =
      local?.sensorName?.takeIf { it.isNotBlank() }
          ?: remoteSession?.device?.takeIf { it.isNotBlank() }
          ?: ""
  val backupFromRecord = record?.backup ?: backupFromRemote(remoteSession)
  val remoteRuns =
      override?.runs
          ?: summary?.analyses?.map { it.toRun() }.orEmpty()
  val runs =
      mergeRuns(record?.analyses.orEmpty(), remoteRuns).map { run ->
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
      record?.selectedAnalysisId?.takeIf { id -> runs.any { it.id == id } }
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
          else
              failedRun?.let { UploadFailure.ANALYSIS }
                  ?: if (remoteSession?.upload_status == "failed") UploadFailure.SERVER else null
  val errorDetail =
      backup.detail
          ?: failedRun?.error?.takeIf { it.isNotBlank() }
          ?: remoteSession?.upload_error?.takeIf { it.isNotBlank() }
  return Night(
      key = key,
      localId = local?.id,
      remoteId = remoteSession?.id?.takeIf { it.isNotBlank() } ?: record?.remoteId,
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
    local: SessionInfo?,
    backup: BackupState,
    runs: List<AnalysisRun>,
    remote: SessionDto?,
): NightStatus {
  if (local?.state == SessionState.RECORDING) return NightStatus.RECORDING
  return when (backup.phase) {
    BackupPhase.QUEUED -> NightStatus.QUEUED
    BackupPhase.UPLOADING -> NightStatus.UPLOADING
    BackupPhase.FAILED -> NightStatus.FAILED
    BackupPhase.NOT_BACKED_UP ->
        when (remote?.upload_status) {
          "uploaded" -> deriveFromRuns(runs)
          "failed" -> NightStatus.FAILED
          "pending" -> NightStatus.UPLOADING
          else -> NightStatus.LOCAL_ONLY
        }
    BackupPhase.BACKED_UP -> deriveFromRuns(runs)
  }
}

private fun deriveFromRuns(runs: List<AnalysisRun>): NightStatus =
    when {
      runs.any { it.status == AnalysisStatus.DONE } -> NightStatus.READY
      runs.any { it.status == AnalysisStatus.QUEUED || it.status == AnalysisStatus.RUNNING } ->
          NightStatus.PROCESSING
      runs.any { it.status == AnalysisStatus.FAILED } -> NightStatus.FAILED
      // The upload is done; the analysis has either not been requested yet or is about to be.
      else -> NightStatus.PROCESSING
    }

private fun backupFromRemote(remote: SessionDto?): BackupState =
    when (remote?.upload_status) {
      "uploaded" ->
          BackupState(
              phase = BackupPhase.BACKED_UP,
              progress = 1f,
              uploadedAtMs = parsePocketBaseDate(remote.uploaded_at),
          )
      "failed" ->
          BackupState(
              phase = BackupPhase.FAILED,
              failure = UploadFailure.SERVER,
              detail = remote.upload_error.takeIf { it.isNotBlank() },
          )
      "pending" -> BackupState(phase = BackupPhase.UPLOADING)
      else -> BackupState()
    }

private fun mergeRuns(base: List<AnalysisRun>, fresh: List<AnalysisRun>): List<AnalysisRun> {
  if (base.isEmpty()) return fresh.sortedBy { it.createdAtMs }
  if (fresh.isEmpty()) return base.sortedBy { it.createdAtMs }
  val byId = LinkedHashMap<String, AnalysisRun>()
  base.forEach { byId[it.id] = it }
  fresh.forEach { incoming ->
    val existing = byId[incoming.id]
    byId[incoming.id] =
        if (existing == null) {
          incoming
        } else {
          // The server owns the status and the result summary. The local record owns facts the
          // server does not know: the job id, why the run exists, and whether its payload is cached.
          incoming.copy(
              origin = existing.origin,
              jobId = incoming.jobId ?: existing.jobId,
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

private fun defaultRun(runs: List<AnalysisRun>): AnalysisRun? {
  if (runs.isEmpty()) return null
  val done = runs.filter { it.status == AnalysisStatus.DONE }
  if (done.isNotEmpty()) {
    return done.sortedWith(
            compareBy<AnalysisRun> { if (it.origin == AnalysisOrigin.MANUAL) 0 else 1 }
                .thenBy { run ->
                  StagerPreference.indexOf(run.algorithm).let { if (it < 0) StagerPreference.size else it }
                }
                .thenByDescending { it.updatedAtMs }
        )
        .first()
  }
  return runs.maxByOrNull { it.updatedAtMs }
}

private fun AnalysisSummaryDto.toRun(): AnalysisRun {
  val stages = stages
  return AnalysisRun(
      id = id,
      algorithm = model,
      version = model_version,
      status = status.toAnalysisStatus(),
      error = error.takeIf { it.isNotBlank() },
      epochSeconds = stages?.epoch_seconds ?: 30,
      segments = stages?.segments.orEmpty(),
      sleepScore = sleep_quality?.score,
      createdAtMs = parsePocketBaseDate(created),
      updatedAtMs = parsePocketBaseDate(updated),
  )
}

/** Used by the repository when it fetches the full session detail for one night. */
fun AnalysisDto.toRun(jobId: String? = null, downloaded: Boolean = false): AnalysisRun =
    AnalysisRun(
        id = id,
        algorithm = model,
        version = model_version,
        status = status.toAnalysisStatus(),
        error = error.takeIf { it.isNotBlank() },
        jobId = jobId,
        epochSeconds = stages?.epoch_seconds ?: 30,
        segments = stages?.segments.orEmpty(),
        sleepScore = sleep_quality?.score,
        createdAtMs = parsePocketBaseDate(created),
        updatedAtMs = parsePocketBaseDate(updated),
        resultDownloaded = downloaded,
    )

private fun String.toAnalysisStatus(): AnalysisStatus =
    when (this) {
      "done" -> AnalysisStatus.DONE
      "failed" -> AnalysisStatus.FAILED
      "running" -> AnalysisStatus.RUNNING
      else -> AnalysisStatus.QUEUED
    }

/** PocketBase returns `yyyy-MM-dd HH:mm:ss.SSSZ` in UTC. */
fun parsePocketBaseDate(value: String): Long {
  if (value.isBlank()) return 0
  val formats =
      listOf(
          "yyyy-MM-dd HH:mm:ss.SSS'Z'",
          "yyyy-MM-dd HH:mm:ss'Z'",
          "yyyy-MM-dd HH:mm:ss.SSSXXX",
          "yyyy-MM-dd HH:mm:ssXXX",
      )
  for (pattern in formats) {
    val parsed =
        runCatching {
              SimpleDateFormat(pattern, Locale.US)
                  .apply { timeZone = TimeZone.getTimeZone("UTC") }
                  .parse(value)
            }
            .getOrNull()
    if (parsed != null) return parsed.time
  }
  return 0
}

fun formatNightDate(ms: Long): String =
    if (ms <= 0) "—"
    else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))

fun formatNightDateAndTime(ms: Long): String =
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
