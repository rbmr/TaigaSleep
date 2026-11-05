package com.rbmr.taigasleep.night

import com.rbmr.taigasleep.analysis.StagerPreference
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.RecordingDto
import com.rbmr.taigasleep.remote.RecordingSummaryDto
import com.rbmr.taigasleep.remote.RecordingSummaryListDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.upload.UploadFailure
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

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
    /** The server has a copy of this recording, so it exists there under [id]. */
    val onServer: Boolean = false,
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
    val remote: RecordingDto?,
    /** True only when the full payload of the selected analysis is cached on this phone. */
    val resultDownloaded: Boolean,
) {
  val selectedAnalysis: AnalysisRun?
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
 * [overrides] carries a fresh recording and analysis list fetched by the night screen, so a night
 * with several analyses shows all of them before the next History refresh.
 *
 * The phone's recording id is the server's recording id, so a local night and its server copy are
 * matched by id.
 */
fun buildNights(
    localSessions: List<SessionInfo>,
    records: Map<String, NightRecord>,
    remote: RecordingSummaryListDto?,
    overrides: Map<String, NightRemoteOverride> = emptyMap(),
): List<Night> {
  val byId = remote?.items?.associateBy { it.recording.id }.orEmpty()
  val localIds = localSessions.map { it.id }.toSet()
  val nights = mutableListOf<Night>()
  for (session in localSessions) {
    nights +=
        buildNight(
            key = session.id,
            local = session,
            summary = byId[session.id],
            record = records[session.id],
            override = overrides[session.id],
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
  return nights.sortedWith(compareByDescending<Night> { it.startUtcMs }.thenBy { it.device })
}

/** The History key of a night that exists only on the server. */
fun remoteKey(recordingId: String): String = "$REMOTE_KEY_PREFIX$recordingId"

/** The recording id behind a night key: the local id, or the id after the remote prefix. */
fun recordingIdOf(key: String): String = key.removePrefix(REMOTE_KEY_PREFIX)

private const val REMOTE_KEY_PREFIX = "remote-"

/** A fresh recording and analysis list from the server, kept only for the current process. */
data class NightRemoteOverride(
    val recording: RecordingDto,
    val runs: List<AnalysisRun>,
)

private fun buildNight(
    key: String,
    local: SessionInfo?,
    summary: RecordingSummaryDto?,
    record: NightRecord?,
    override: NightRemoteOverride?,
): Night {
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
      backup.failure ?: if (hasDone) null else failedRun?.let { UploadFailure.ANALYSIS }
  val errorDetail = backup.detail ?: failedRun?.error?.takeIf { it.isNotBlank() }
  return Night(
      key = key,
      localId = local?.id,
      remoteId =
          remoteSession?.id?.takeIf { it.isNotBlank() }
              ?: recordingIdOf(key).takeIf { record?.onServer == true },
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
    remote: RecordingDto?,
): NightStatus {
  if (local?.state == SessionState.RECORDING) return NightStatus.RECORDING
  return when (backup.phase) {
    BackupPhase.QUEUED -> NightStatus.QUEUED
    BackupPhase.UPLOADING -> NightStatus.UPLOADING
    BackupPhase.FAILED -> NightStatus.FAILED
    BackupPhase.NOT_BACKED_UP ->
        when {
          remote == null -> NightStatus.LOCAL_ONLY
          remote.complete -> deriveFromRuns(runs)
          // The recording exists on the server but is not sealed yet: its files are still moving.
          else -> NightStatus.UPLOADING
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

/**
 * A run as the app shows it. The stage timeline and the sleep score come from the full result when
 * it is present and from the summary a list carries otherwise; the server derives the second from
 * the first.
 */
fun AnalysisDto.toRun(downloaded: Boolean = false): AnalysisRun {
  val stages = result?.stages
  val summarized = summary?.stages
  return AnalysisRun(
      id = id,
      algorithm = algorithm,
      version = version,
      status = status.toAnalysisStatus(),
      error = error.takeIf { it.isNotBlank() },
      epochSeconds = stages?.epoch_seconds ?: summarized?.epoch_seconds ?: 30,
      segments = stages?.segments ?: summarized?.segments.orEmpty(),
      sleepScore = result?.sleep_quality?.score ?: summary?.sleep_quality?.score,
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
