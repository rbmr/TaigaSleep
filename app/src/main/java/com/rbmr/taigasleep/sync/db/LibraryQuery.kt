package com.rbmr.taigasleep.sync.db

import androidx.room.Dao
import androidx.room.Query
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.AnalysisSummaryDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.sync.LibraryEntry
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * One row of the library query: one recording key with the state the database holds for it, and one
 * of its runs, or a single row with no run when it has none. Every column that a left join can miss
 * is null, so a recording without a sync state, without a server row or with no run is one row with
 * the columns it does have.
 */
data class LibraryRow(
    val recordingKey: String,
    val stateId: String? = null,
    val onServer: Boolean? = null,
    val phase: String? = null,
    val progress: Float? = null,
    val failure: String? = null,
    val stateErrorDetail: String? = null,
    val stateWarning: String? = null,
    val stateUpdatedAtMs: Long? = null,
    val uploadedAtMs: Long? = null,
    val confirmedOnServer: Boolean? = null,
    val keepRawOnPhone: Boolean? = null,
    val manualAnalysisIds: String? = null,
    val remoteRecordingId: String? = null,
    val remoteState: String? = null,
    val remoteDevice: String? = null,
    val remoteStartedAt: String? = null,
    val remoteEndedAt: String? = null,
    val remoteDurationS: Double? = null,
    val remoteComplete: Boolean? = null,
    val remoteSizeBytes: Long? = null,
    val remoteCreated: String? = null,
    val remoteUpdated: String? = null,
    val remoteHidden: Boolean? = null,
    val selectedAnalysisId: String? = null,
    val pendingDeletes: Int = 0,
    val fromCache: Int? = null,
    val analysisId: String? = null,
    val analysisRecordingId: String? = null,
    val analysisAlgorithm: String? = null,
    val analysisVersion: String? = null,
    val analysisStatus: String? = null,
    val analysisError: String? = null,
    val analysisAttempts: Int? = null,
    val analysisCreated: String? = null,
    val analysisUpdated: String? = null,
    val analysisFinishedAt: String? = null,
    val analysisParamsJson: String? = null,
    val analysisSummaryJson: String? = null,
    val analysisResultJson: String? = null,
)

/**
 * The one query a recording list is read from. It joins, for the account that is active, the sync
 * state of every recording, the server's recording list and its runs, the run each recording shows,
 * the payloads this phone holds, and the deletes that are queued or done, in one statement.
 *
 * The key of a recording is the History key: the phone's own recording id when the phone has the
 * recording, the remote key otherwise. The phone's recordings are not in the database, so their
 * ids are a parameter: a server row whose id is one of them is joined by that id, and every other
 * server row is joined by its remote key.
 *
 * A key is read from every table that can name one, so a recording that only has a sync state or a
 * payload still comes back as one row with the facts it has. The runs come from two places with one
 * shape: the server's list when it has the recording, and the payloads this phone holds. The reader
 * decides which of the two a recording is drawn from, which is the same rule the list always had.
 */
@Dao
interface LibraryDao {
  @Query(LIBRARY_QUERY)
  fun observe(account: String, localIds: List<String>, remotePrefix: String): Flow<List<LibraryRow>>

  @Query(LIBRARY_QUERY)
  suspend fun rows(account: String, localIds: List<String>, remotePrefix: String): List<LibraryRow>
}

/**
 * The query behind [LibraryDao]. `keys` is every recording key of the account, `runs` the server's
 * runs and the payloads this phone holds under the key they belong to, and the outer select puts
 * the facts of one recording next to each of its runs.
 */
private const val LIBRARY_QUERY = """
WITH keys(k) AS (
  SELECT recordingId FROM upload_state WHERE account = :account
  UNION
  SELECT recordingKey FROM remote_selection WHERE account = :account
  UNION
  SELECT recordingKey FROM analysis_result WHERE account = :account
  UNION
  SELECT
    CASE
      WHEN recordingId IN (:localIds) THEN recordingId
      ELSE :remotePrefix || recordingId
    END
  FROM remote_recording WHERE account = :account
),
runs(
  k, fromCache, analysisId, recordingId, algorithm, version, status, error, attempts, created,
  updated, finishedAt, paramsJson, summaryJson, resultJson
) AS (
  SELECT
    CASE
      WHEN r.recordingId IN (:localIds) THEN r.recordingId
      ELSE :remotePrefix || r.recordingId
    END,
    0, ra.analysisId, ra.recordingId, ra.algorithm, ra.version, ra.status, ra.error, ra.attempts,
    ra.created, ra.updated, ra.finishedAt, ra.paramsJson, ra.summaryJson, NULL
  FROM remote_recording r
  JOIN remote_analysis ra ON ra.account = :account AND ra.recordingId = r.recordingId
  WHERE r.account = :account
  UNION ALL
  SELECT
    ar.recordingKey, 1, ar.analysisId, ar.recordingId, ar.algorithm, ar.version, ar.status,
    ar.error, ar.attempts, ar.created, ar.updated, ar.finishedAt, ar.paramsJson, ar.summaryJson,
    ar.resultJson
  FROM analysis_result ar
  WHERE ar.account = :account
)
SELECT
  keys.k AS recordingKey,
  u.recordingId AS stateId,
  u.onServer AS onServer,
  u.phase AS phase,
  u.progress AS progress,
  u.failure AS failure,
  u.errorDetail AS stateErrorDetail,
  u.warning AS stateWarning,
  u.updatedAtMs AS stateUpdatedAtMs,
  u.uploadedAtMs AS uploadedAtMs,
  u.confirmedOnServer AS confirmedOnServer,
  u.keepRawOnPhone AS keepRawOnPhone,
  u.manualAnalysisIds AS manualAnalysisIds,
  r.recordingId AS remoteRecordingId,
  r.state AS remoteState,
  r.device AS remoteDevice,
  r.startedAt AS remoteStartedAt,
  r.endedAt AS remoteEndedAt,
  r.durationS AS remoteDurationS,
  r.complete AS remoteComplete,
  r.sizeBytes AS remoteSizeBytes,
  r.created AS remoteCreated,
  r.updated AS remoteUpdated,
  r.hidden AS remoteHidden,
  s.analysisId AS selectedAnalysisId,
  (p.remoteId IS NOT NULL) AS pendingDeletes,
  runs.fromCache AS fromCache,
  runs.analysisId AS analysisId,
  runs.recordingId AS analysisRecordingId,
  runs.algorithm AS analysisAlgorithm,
  runs.version AS analysisVersion,
  runs.status AS analysisStatus,
  runs.error AS analysisError,
  runs.attempts AS analysisAttempts,
  runs.created AS analysisCreated,
  runs.updated AS analysisUpdated,
  runs.finishedAt AS analysisFinishedAt,
  runs.paramsJson AS analysisParamsJson,
  runs.summaryJson AS analysisSummaryJson,
  runs.resultJson AS analysisResultJson
FROM keys
LEFT JOIN upload_state u ON u.account = :account AND u.recordingId = keys.k
LEFT JOIN remote_recording r ON r.account = :account
  AND (
    CASE
      WHEN r.recordingId IN (:localIds) THEN r.recordingId
      ELSE :remotePrefix || r.recordingId
    END
  ) = keys.k
LEFT JOIN remote_selection s ON s.account = :account AND s.recordingKey = keys.k
LEFT JOIN pending_delete p ON p.account = :account
  AND (p.remoteId = keys.k OR :remotePrefix || p.remoteId = keys.k)
LEFT JOIN runs ON runs.k = keys.k
ORDER BY keys.k
"""

/** The rows of one key as one entry. */
internal fun List<LibraryRow>.toEntries(account: String): List<LibraryEntry> =
    groupBy { it.recordingKey }.map { (key, rows) ->
      val first = rows.first()
      LibraryEntry(
          key = key,
          record = first.toRecord(account),
          recording = first.toRecording(account),
          runs = rows.filter { it.fromCache == 0 }.mapNotNull { it.toAnalysis() },
          selectedAnalysisId = first.selectedAnalysisId,
          cachedResults = rows.filter { it.fromCache == 1 }.mapNotNull { it.toAnalysis() },
          hidden = first.remoteHidden == true || first.pendingDeletes != 0,
      )
    }

private fun LibraryRow.toRecord(account: String): UploadRecord? =
    stateId?.let { id ->
      UploadStateRow(
              account = account,
              recordingId = id,
              onServer = onServer ?: false,
              phase = phase?.let { UploadPhase.valueOf(it) } ?: UploadPhase.LOCAL,
              progress = progress ?: 0f,
              failure = failure?.let { UploadFailure.valueOf(it) },
              errorDetail = stateErrorDetail,
              warning = stateWarning,
              updatedAtMs = stateUpdatedAtMs ?: 0,
              uploadedAtMs = uploadedAtMs,
              confirmedOnServer = confirmedOnServer ?: false,
              keepRawOnPhone = keepRawOnPhone ?: false,
              manualAnalysisIds =
                  manualAnalysisIds?.let { rowJson.decodeFromString<List<String>>(it) }.orEmpty(),
          )
          .toUploadRecord()
    }

private fun LibraryRow.toRecording(account: String): RecordingDto? =
    remoteRecordingId?.let { id ->
      RemoteRecordingRow(
              account = account,
              recordingId = id,
              state = remoteState.orEmpty(),
              device = remoteDevice.orEmpty(),
              startedAt = remoteStartedAt.orEmpty(),
              endedAt = remoteEndedAt,
              durationS = remoteDurationS ?: 0.0,
              complete = remoteComplete ?: false,
              sizeBytes = remoteSizeBytes ?: 0,
              created = remoteCreated.orEmpty(),
              updated = remoteUpdated.orEmpty(),
              hidden = remoteHidden ?: false,
          )
          .toRecordingDto()
    }

private fun LibraryRow.toAnalysis(): AnalysisDto? =
    analysisId?.let { id ->
      AnalysisDto(
          id = id,
          recording = analysisRecordingId.orEmpty(),
          algorithm = analysisAlgorithm.orEmpty(),
          version = analysisVersion.orEmpty(),
          params = analysisParamsJson?.let { rowJson.decodeFromString<JsonObject>(it) },
          status = analysisStatus.orEmpty(),
          error = analysisError.orEmpty(),
          attempts = analysisAttempts ?: 0,
          created = analysisCreated.orEmpty(),
          updated = analysisUpdated.orEmpty(),
          finished_at = analysisFinishedAt,
          summary = analysisSummaryJson?.let { rowJson.decodeFromString<AnalysisSummaryDto>(it) },
          result = analysisResultJson?.let { rowJson.decodeFromString<AnalysisResultDto>(it) },
      )
    }
