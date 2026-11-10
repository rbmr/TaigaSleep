package com.rbmr.taigasleep.sync.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The sync state of one account, one row per recording. */
@Dao
interface UploadStateDao {
  @Query("SELECT * FROM upload_state WHERE account = :account ORDER BY recordingId")
  fun observe(account: String): Flow<List<UploadStateRow>>

  @Query("SELECT * FROM upload_state WHERE account = :account ORDER BY recordingId")
  suspend fun rows(account: String): List<UploadStateRow>

  @Query("SELECT * FROM upload_state WHERE account = :account AND recordingId = :recordingId")
  suspend fun row(account: String, recordingId: String): UploadStateRow?

  /**
   * The accounts, other than [account], whose server copy of this recording is known. A recording
   * one account has uploaded is only uploaded for another after the user agrees.
   */
  @Query(
      "SELECT account FROM upload_state WHERE recordingId = :recordingId AND account != '' " +
          "AND account != :account AND (onServer = 1 OR confirmedOnServer = 1) ORDER BY account"
  )
  suspend fun otherAccounts(recordingId: String, account: String): List<String>

  @Upsert suspend fun upsert(row: UploadStateRow)

  @Upsert suspend fun upsert(rows: List<UploadStateRow>)

  @Query("DELETE FROM upload_state WHERE account = :account AND recordingId = :recordingId")
  suspend fun delete(account: String, recordingId: String)
}

/** The server's recording list of one account and the run each recording shows. */
@Dao
interface RemoteRecordingDao {
  @Query(
      "SELECT * FROM remote_recording WHERE account = :account AND hidden = 0 ORDER BY position"
  )
  fun observe(account: String): Flow<List<RemoteRecordingRow>>

  @Query(
      "SELECT * FROM remote_recording WHERE account = :account AND hidden = 0 ORDER BY position"
  )
  suspend fun rows(account: String): List<RemoteRecordingRow>

  /**
   * The recordings the server no longer has for this account, which no later list may show again.
   */
  @Query(
      "SELECT recordingId FROM remote_recording WHERE account = :account AND hidden = 1 " +
          "ORDER BY recordingId"
  )
  suspend fun hiddenIds(account: String): List<String>

  @Query(
      "SELECT COUNT(*) FROM remote_recording WHERE account = :account AND recordingId = :recordingId " +
          "AND hidden = 1"
  )
  suspend fun isHidden(account: String, recordingId: String): Int

  @Query("SELECT * FROM remote_analysis WHERE account = :account ORDER BY recordingId, analysisId")
  suspend fun analysisRows(account: String): List<RemoteAnalysisRow>

  @Query("SELECT * FROM remote_selection WHERE account = :account ORDER BY recordingKey")
  suspend fun selections(account: String): List<RemoteSelectionRow>

  @Query(
      "SELECT analysisId FROM remote_selection WHERE account = :account AND recordingKey = :recordingKey"
  )
  suspend fun selection(account: String, recordingKey: String): String?

  /** The start time the server reported for one recording of this account's list. */
  @Query(
      "SELECT startedAt FROM remote_recording WHERE account = :account AND recordingId = :recordingId"
  )
  suspend fun startedAt(account: String, recordingId: String): String?

  @Upsert suspend fun upsert(rows: List<RemoteRecordingRow>)

  @Upsert suspend fun upsertAnalyses(rows: List<RemoteAnalysisRow>)

  @Upsert suspend fun select(row: RemoteSelectionRow)

  @Query("DELETE FROM remote_recording WHERE account = :account AND recordingId = :recordingId")
  suspend fun delete(account: String, recordingId: String)

  /**
   * The server no longer has this recording: the row stays, marked hidden, so a list fetched before
   * the delete finished cannot show it again. A recording the saved list never held gets the marker
   * alone, which is what a 404 for a recording that was only shown from a fresh read leaves.
   */
  @Transaction
  suspend fun hide(account: String, recordingId: String) {
    upsert(listOf(RemoteRecordingRow(account = account, recordingId = recordingId, hidden = true)))
    deleteAnalyses(account, recordingId)
  }

  @Query("DELETE FROM remote_analysis WHERE account = :account AND recordingId = :recordingId")
  suspend fun deleteAnalyses(account: String, recordingId: String)

  @Query("DELETE FROM remote_selection WHERE account = :account AND recordingKey = :recordingKey")
  suspend fun deleteSelection(account: String, recordingKey: String)

  /**
   * Replaces the saved list of one account with what the server sent, leaving the run each recording
   * shows and the recordings the server no longer has untouched.
   */
  @Transaction
  suspend fun replace(
      account: String,
      rows: List<RemoteRecordingRow>,
      analyses: List<RemoteAnalysisRow>,
  ) {
    // Read first: the deletions below would otherwise take the markers with them.
    val hidden = hiddenIds(account).toSet()
    deleteVisible(account)
    deleteVisibleAnalyses(account)
    val kept = rows.filterNot { it.recordingId in hidden }
    val runs = analyses.filterNot { it.recordingId in hidden }
    if (kept.isNotEmpty()) upsert(kept)
    if (runs.isNotEmpty()) upsertAnalyses(runs)
  }

  @Query("DELETE FROM remote_recording WHERE account = :account AND hidden = 0")
  suspend fun deleteVisible(account: String)

  @Query(
      "DELETE FROM remote_analysis WHERE account = :account AND recordingId NOT IN " +
          "(SELECT recordingId FROM remote_recording WHERE account = :account AND hidden = 1)"
  )
  suspend fun deleteVisibleAnalyses(account: String)

  /** Replaces the runs one recording of one account lists, leaving its row where it is. */
  @Transaction
  suspend fun replaceAnalyses(
      account: String,
      recordingId: String,
      rows: List<RemoteAnalysisRow>,
  ) {
    deleteAnalyses(account, recordingId)
    if (rows.isNotEmpty()) upsertAnalyses(rows)
  }

}

/** The downloaded payloads of one account, one row per run and recording key. */
@Dao
interface AnalysisResultDao {
  @Query("SELECT * FROM analysis_result WHERE account = :account ORDER BY recordingKey, analysisId")
  fun observe(account: String): Flow<List<AnalysisResultRow>>

  @Query(
      "SELECT * FROM analysis_result WHERE account = :account AND recordingKey = :recordingKey " +
          "ORDER BY analysisId"
  )
  fun observeFor(account: String, recordingKey: String): Flow<List<AnalysisResultRow>>

  @Query("SELECT * FROM analysis_result WHERE account = :account ORDER BY recordingKey, analysisId")
  suspend fun rows(account: String): List<AnalysisResultRow>

  @Query(
      "SELECT * FROM analysis_result WHERE account = :account AND recordingKey = :recordingKey " +
          "ORDER BY analysisId"
  )
  suspend fun rowsFor(account: String, recordingKey: String): List<AnalysisResultRow>

  @Query(
      "SELECT * FROM analysis_result WHERE account = :account AND recordingKey = :recordingKey " +
          "AND analysisId = :analysisId"
  )
  suspend fun row(account: String, recordingKey: String, analysisId: String): AnalysisResultRow?

  @Upsert suspend fun upsert(row: AnalysisResultRow)

  @Upsert suspend fun upsert(rows: List<AnalysisResultRow>)

  @Query("DELETE FROM analysis_result WHERE account = :account AND recordingKey = :recordingKey")
  suspend fun deleteRecording(account: String, recordingKey: String)

  @Query(
      "DELETE FROM analysis_result WHERE account = :account AND recordingKey = :recordingKey " +
          "AND analysisId = :analysisId"
  )
  suspend fun delete(account: String, recordingKey: String, analysisId: String)
}

/** The remote deletes one account still owes the server. */
@Dao
interface PendingDeleteDao {
  @Query("SELECT * FROM pending_delete WHERE account = :account ORDER BY requestedAtMs, remoteId")
  fun observe(account: String): Flow<List<PendingDeleteRow>>

  @Query("SELECT * FROM pending_delete WHERE account = :account ORDER BY requestedAtMs, remoteId")
  suspend fun rows(account: String): List<PendingDeleteRow>

  @Query("SELECT * FROM pending_delete WHERE account = :account AND remoteId = :remoteId")
  suspend fun row(account: String, remoteId: String): PendingDeleteRow?

  @Upsert suspend fun upsert(rows: List<PendingDeleteRow>)

  @Upsert suspend fun upsert(row: PendingDeleteRow)

  @Query("DELETE FROM pending_delete WHERE account = :account AND remoteId = :remoteId")
  suspend fun delete(account: String, remoteId: String)
}
