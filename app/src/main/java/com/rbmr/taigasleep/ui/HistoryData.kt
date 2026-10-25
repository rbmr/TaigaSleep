package com.rbmr.taigasleep.ui

import com.rbmr.taigasleep.remote.AnalysisSummaryDto
import com.rbmr.taigasleep.remote.SessionListDto
import com.rbmr.taigasleep.remote.SessionSummaryDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.upload.UploadFailure
import com.rbmr.taigasleep.upload.UploadPhase
import com.rbmr.taigasleep.upload.UploadRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One row in History; local recordings and remote-only records share the same shape. */
data class NightItem(
    val key: String,
    val localId: String?,
    val remoteId: String?,
    val analysisId: String?,
    val jobId: String?,
    val startUtcMs: Long,
    val durationMs: Long,
    val device: String,
    val phase: UploadPhase,
    val progress: Float,
    val failure: UploadFailure?,
    val errorDetail: String?,
    val warning: String?,
    val epochSeconds: Int,
    val segments: List<StageSegmentDto>,
    val qualityScore: Double?,
    val model: String?,
    val modelVersion: String?,
    /** The full analysis payload is on this phone, not merely summarized from the server. */
    val resultDownloaded: Boolean,
    val local: SessionInfo?,
) {
  val isDone: Boolean
    get() = phase == UploadPhase.DONE

  val isRecording: Boolean
    get() =
        local?.state == SessionState.RECORDING &&
            (phase == UploadPhase.LOCAL || failure == UploadFailure.RECORDING)
}

/**
 * Merge the local recording list, the app's durable upload state and the server's summary. The
 * server wins when it has a finished analysis; the local state wins while the recording is still
 * being moved or when the server is unreachable.
 */
fun buildNightItems(
    localSessions: List<SessionInfo>,
    uploads: Map<String, UploadRecord>,
    remote: SessionListDto?,
): List<NightItem> {
  val byClient = remote?.items?.associateBy { it.session.client_session_id }.orEmpty()
  val localIds = localSessions.map { it.id }.toSet()
  val items = buildList {
    for (session in localSessions) {
      add(localItem(session, byClient[session.id], uploads[session.id]))
    }
    for (summary in remote?.items.orEmpty()) {
      val client = summary.session.client_session_id
      if (client !in localIds) {
        add(remoteItem(summary, uploads["remote-${summary.session.id}"]))
      }
    }
  }
  return items.sortedWith(compareByDescending<NightItem> { it.startUtcMs }.thenBy { it.device })
}

private fun localItem(
    session: SessionInfo,
    remote: SessionSummaryDto?,
    record: UploadRecord?,
): NightItem {
  val done = remote?.doneAnalysis()
  val failed = remote?.failedAnalysis()
  val remoteStatus = remote?.session?.upload_status.orEmpty()
  val remotePending =
      remote?.analyses?.any { it.status == "queued" || it.status == "running" } == true

  val phase =
      when {
        done != null -> UploadPhase.DONE
        record?.phase == UploadPhase.DONE -> UploadPhase.DONE
        remoteStatus == "failed" -> UploadPhase.FAILED
        failed != null -> UploadPhase.FAILED
        record?.phase == UploadPhase.FAILED -> UploadPhase.FAILED
        remoteStatus == "uploaded" -> UploadPhase.PROCESSING
        remotePending -> UploadPhase.PROCESSING
        record?.phase == UploadPhase.PROCESSING -> UploadPhase.PROCESSING
        remoteStatus == "pending" -> UploadPhase.UPLOADING
        else -> record?.phase ?: UploadPhase.LOCAL
      }

  val analysisId = done?.id ?: failed?.id ?: record?.analysisId
  return NightItem(
      key = session.id,
      localId = session.id,
      remoteId = remote?.session?.id ?: record?.remoteId,
      analysisId = analysisId,
      jobId = record?.jobId,
      startUtcMs = session.startUtcMs,
      durationMs = session.durationMs,
      device = session.sensorName.ifBlank { "Polar" },
      phase = phase,
      progress = if (phase == UploadPhase.DONE) 1f else record?.progress ?: 0f,
      failure =
          when {
            phase == UploadPhase.FAILED && failed != null -> UploadFailure.ANALYSIS
            phase == UploadPhase.FAILED && remoteStatus == "failed" -> UploadFailure.SERVER
            phase == UploadPhase.FAILED -> record?.failure ?: UploadFailure.SERVER
            phase == UploadPhase.QUEUED && record?.failure == UploadFailure.RECORDING ->
                UploadFailure.RECORDING
            else -> null
          },
      errorDetail =
          when (phase) {
            UploadPhase.FAILED ->
                failed?.error?.takeIf { it.isNotBlank() }
                    ?: remote?.session?.upload_error?.takeIf { it.isNotBlank() }
                    ?: record?.errorDetail
            else -> record?.errorDetail
          },
      warning = record?.warning,
      epochSeconds = done?.stages?.epoch_seconds ?: record?.epochSeconds ?: 30,
      segments = done?.stages?.segments ?: record?.segments.orEmpty(),
      qualityScore = done?.quality?.score ?: record?.qualityScore,
      model = done?.model ?: record?.model,
      modelVersion = done?.model_version ?: record?.modelVersion,
      resultDownloaded = record?.resultDownloaded == true,
      local = session,
  )
}

private fun remoteItem(remote: SessionSummaryDto, record: UploadRecord?): NightItem {
  val done = remote.doneAnalysis()
  val failed = remote.failedAnalysis()
  val status = remote.session.upload_status
  val phase =
      when {
        done != null -> UploadPhase.DONE
        record?.phase == UploadPhase.DONE -> UploadPhase.DONE
        status == "failed" || record?.phase == UploadPhase.FAILED -> UploadPhase.FAILED
        failed != null -> UploadPhase.FAILED
        status == "uploaded" || record?.phase == UploadPhase.PROCESSING -> UploadPhase.PROCESSING
        record?.phase == UploadPhase.QUEUED -> UploadPhase.QUEUED
        else -> UploadPhase.UPLOADING
      }
  return NightItem(
      key = "remote-${remote.session.id}",
      localId = null,
      remoteId = remote.session.id,
      analysisId = done?.id ?: failed?.id ?: record?.analysisId,
      jobId = record?.jobId,
      startUtcMs = parsePocketBaseDate(remote.session.started_at),
      durationMs = (remote.session.duration_s * 1000.0).toLong(),
      device = remote.session.device.ifBlank { "Polar" },
      phase = phase,
      progress = if (phase == UploadPhase.DONE) 1f else record?.progress ?: 0f,
      failure =
          when {
            phase != UploadPhase.FAILED -> null
            failed != null -> UploadFailure.ANALYSIS
            status == "failed" -> UploadFailure.SERVER
            else -> record?.failure ?: UploadFailure.SERVER
          },
      errorDetail = record?.errorDetail ?: failed?.error ?: remote.session.upload_error,
      warning = record?.warning,
      epochSeconds = done?.stages?.epoch_seconds ?: record?.epochSeconds ?: 30,
      segments = done?.stages?.segments ?: record?.segments.orEmpty(),
      qualityScore = done?.quality?.score ?: record?.qualityScore,
      model = done?.model ?: record?.model,
      modelVersion = done?.model_version ?: record?.modelVersion,
      resultDownloaded = record?.resultDownloaded == true,
      local = null,
  )
}

private fun SessionSummaryDto.doneAnalysis(): AnalysisSummaryDto? =
    analyses.filter { it.status == "done" }.maxByOrNull { it.updated }

private fun SessionSummaryDto.failedAnalysis(): AnalysisSummaryDto? =
    analyses.firstOrNull { it.status == "failed" }

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
    hours > 0 -> "${hours}h"
    else -> "${minutes}m"
  }
}
