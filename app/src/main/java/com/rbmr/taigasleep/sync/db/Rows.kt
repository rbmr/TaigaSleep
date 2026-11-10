package com.rbmr.taigasleep.sync.db

import androidx.room.Entity
import androidx.room.Index
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.AnalysisSummaryDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.sync.PendingDelete
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The sync state of one recording for one account. */
@Entity(tableName = "upload_state", primaryKeys = ["account", "recordingId"])
data class UploadStateRow(
    val account: String,
    val recordingId: String,
    val onServer: Boolean = false,
    val phase: UploadPhase = UploadPhase.LOCAL,
    val progress: Float = 0f,
    val failure: UploadFailure? = null,
    val errorDetail: String? = null,
    val warning: String? = null,
    val updatedAtMs: Long = 0,
    val uploadedAtMs: Long? = null,
    val confirmedOnServer: Boolean = false,
    val keepRawOnPhone: Boolean = false,
    val manualAnalysisIds: List<String> = emptyList(),
)

/** One recording of one account's server list, as the list the server sent has it. */
@Entity(tableName = "remote_recording", primaryKeys = ["account", "recordingId"])
data class RemoteRecordingRow(
    val account: String,
    val recordingId: String,
    /** The place of the recording in the list as the server sent it. */
    val position: Int = 0,
    val state: String = "",
    val device: String = "",
    val startedAt: String = "",
    val endedAt: String? = null,
    val durationS: Double = 0.0,
    val complete: Boolean = false,
    val sizeBytes: Long = 0,
    val created: String = "",
    val updated: String = "",
    /**
     * The server no longer has this recording for the account. The row stays so a list fetched
     * before the delete finished cannot put the recording back; it is read only for that.
     */
    val hidden: Boolean = false,
)

/** The run one recording of one account shows, the row behind the saved selection. */
@Entity(tableName = "remote_selection", primaryKeys = ["account", "recordingKey"])
data class RemoteSelectionRow(
    val account: String,
    val recordingKey: String,
    val analysisId: String,
)

/**
 * One run the server's list carries for one recording, with the headline a History row is drawn
 * from. The full payload of a run lives in [AnalysisResultRow] once it is downloaded.
 */
@Entity(tableName = "remote_analysis", primaryKeys = ["account", "recordingId", "analysisId"])
data class RemoteAnalysisRow(
    val account: String,
    val recordingId: String,
    val analysisId: String,
    val algorithm: String = "",
    val version: String = "",
    val status: String = "",
    val error: String = "",
    val attempts: Int = 0,
    val created: String = "",
    val updated: String = "",
    val finishedAt: String? = null,
    val paramsJson: String? = null,
    val summaryJson: String? = null,
)

/**
 * One downloaded analysis of one recording for one account. The row holds what the app shows about
 * the run as columns, [summaryJson] for the headline the server sent with it, and [resultJson] for
 * the full payload.
 */
@Entity(
    tableName = "analysis_result",
    primaryKeys = ["account", "recordingKey", "analysisId"],
    indices = [Index(value = ["account", "recordingId"])],
)
data class AnalysisResultRow(
    val account: String,
    val recordingKey: String,
    val analysisId: String,
    val recordingId: String = "",
    val algorithm: String = "",
    val version: String = "",
    val status: String = "",
    val error: String = "",
    val attempts: Int = 0,
    val created: String = "",
    val updated: String = "",
    val finishedAt: String? = null,
    val paramsJson: String? = null,
    val summaryJson: String? = null,
    val resultJson: String? = null,
)

/** One remote delete that could not be sent yet, for the account whose copy it removes. */
@Entity(tableName = "pending_delete", primaryKeys = ["account", "remoteId"])
data class PendingDeleteRow(
    val account: String,
    val remoteId: String,
    val requestedAtMs: Long,
)

/** The JSON the rows use, the same shape the stores wrote so a row round-trips what they read. */
internal val rowJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

fun UploadRecord.toRow(account: String): UploadStateRow =
    UploadStateRow(
        account = account,
        recordingId = id,
        onServer = onServer,
        phase = phase,
        progress = progress,
        failure = failure,
        errorDetail = errorDetail,
        warning = warning,
        updatedAtMs = updatedAtMs,
        uploadedAtMs = uploadedAtMs,
        confirmedOnServer = confirmedOnServer,
        keepRawOnPhone = keepRawOnPhone,
        manualAnalysisIds = manualAnalysisIds,
    )

fun UploadStateRow.toUploadRecord(): UploadRecord =
    UploadRecord(
        id = recordingId,
        onServer = onServer,
        phase = phase,
        progress = progress,
        failure = failure,
        errorDetail = errorDetail,
        warning = warning,
        updatedAtMs = updatedAtMs,
        uploadedAtMs = uploadedAtMs,
        confirmedOnServer = confirmedOnServer,
        keepRawOnPhone = keepRawOnPhone,
        manualAnalysisIds = manualAnalysisIds,
    )

fun RecordingDto.toRow(account: String, position: Int): RemoteRecordingRow =
    RemoteRecordingRow(
        account = account,
        recordingId = id,
        position = position,
        state = state,
        device = device,
        startedAt = started_at,
        endedAt = ended_at,
        durationS = duration_s,
        complete = complete,
        sizeBytes = size_bytes,
        created = created,
        updated = updated,
    )

fun RemoteRecordingRow.toRecordingDto(): RecordingDto =
    RecordingDto(
        id = recordingId,
        state = state,
        device = device,
        started_at = startedAt,
        ended_at = endedAt,
        duration_s = durationS,
        complete = complete,
        size_bytes = sizeBytes,
        created = created,
        updated = updated,
    )

/**
 * The row of one run the server's list carries. [recordingId] is the server's recording id, which
 * is what the list groups its runs by.
 */
fun AnalysisDto.toRemoteAnalysisRow(account: String): RemoteAnalysisRow =
    RemoteAnalysisRow(
        account = account,
        recordingId = recording,
        analysisId = id,
        algorithm = algorithm,
        version = version,
        status = status,
        error = error,
        attempts = attempts,
        created = created,
        updated = updated,
        finishedAt = finished_at,
        paramsJson = params?.toString(),
        summaryJson = summary?.let { rowJson.encodeToString(it) },
    )

fun RemoteAnalysisRow.toAnalysisDto(): AnalysisDto =
    AnalysisDto(
        id = analysisId,
        recording = recordingId,
        algorithm = algorithm,
        version = version,
        params = paramsJson?.let { rowJson.decodeFromString<JsonObject>(it) },
        status = status,
        error = error,
        attempts = attempts,
        created = created,
        updated = updated,
        finished_at = finishedAt,
        summary = summaryJson?.let { rowJson.decodeFromString<AnalysisSummaryDto>(it) },
    )

/**
 * The row of one downloaded run. [recordingKey] is the History key the run is shown under, which
 * the caller knows: the phone's recording id when the phone has the recording, the remote key
 * otherwise. The payload and its headline are kept as the server sent them, so a row reproduces the
 * payload the cache wrote.
 */
fun AnalysisDto.toResultRow(account: String, recordingKey: String): AnalysisResultRow =
    AnalysisResultRow(
        account = account,
        recordingKey = recordingKey,
        analysisId = id,
        recordingId = recording,
        algorithm = algorithm,
        version = version,
        status = status,
        error = error,
        attempts = attempts,
        created = created,
        updated = updated,
        finishedAt = finished_at,
        paramsJson = params?.toString(),
        summaryJson = summary?.let { rowJson.encodeToString(it) },
        resultJson = result?.let { rowJson.encodeToString(it) },
    )

fun AnalysisResultRow.toAnalysisDto(): AnalysisDto =
    AnalysisDto(
        id = analysisId,
        recording = recordingId,
        algorithm = algorithm,
        version = version,
        params = paramsJson?.let { rowJson.decodeFromString<JsonObject>(it) },
        status = status,
        error = error,
        attempts = attempts,
        created = created,
        updated = updated,
        finished_at = finishedAt,
        summary = summaryJson?.let { rowJson.decodeFromString<AnalysisSummaryDto>(it) },
        result = resultJson?.let { rowJson.decodeFromString<AnalysisResultDto>(it) },
    )

fun PendingDelete.toRow(account: String): PendingDeleteRow =
    PendingDeleteRow(account = account, remoteId = remoteId, requestedAtMs = requestedAtMs)

fun PendingDeleteRow.toPendingDelete(): PendingDelete =
    PendingDelete(remoteId = remoteId, account = account, requestedAtMs = requestedAtMs)
