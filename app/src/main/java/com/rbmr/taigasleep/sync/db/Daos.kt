package com.rbmr.taigasleep.sync.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
  @Query("SELECT * FROM remote_recording WHERE account = :account ORDER BY position")
  fun observe(account: String): Flow<List<RemoteRecordingRow>>

  @Query("SELECT * FROM remote_recording WHERE account = :account ORDER BY position")
  suspend fun rows(account: String): List<RemoteRecordingRow>

  @Query("SELECT * FROM remote_analysis WHERE account = :account ORDER BY recordingId, analysisId")
  fun observeAnalyses(account: String): Flow<List<RemoteAnalysisRow>>

  @Query("SELECT * FROM remote_analysis WHERE account = :account ORDER BY recordingId, analysisId")
  suspend fun analysisRows(account: String): List<RemoteAnalysisRow>

  @Query("SELECT * FROM remote_selection WHERE account = :account ORDER BY recordingKey")
  fun observeSelections(account: String): Flow<List<RemoteSelectionRow>>

  @Query("SELECT * FROM remote_selection WHERE account = :account ORDER BY recordingKey")
  suspend fun selections(account: String): List<RemoteSelectionRow>

  @Upsert suspend fun upsert(rows: List<RemoteRecordingRow>)

  @Upsert suspend fun upsertAnalyses(rows: List<RemoteAnalysisRow>)

  @Upsert suspend fun select(row: RemoteSelectionRow)

  @Query("DELETE FROM remote_recording WHERE account = :account AND recordingId = :recordingId")
  suspend fun delete(account: String, recordingId: String)

  @Query("DELETE FROM remote_analysis WHERE account = :account AND recordingId = :recordingId")
  suspend fun deleteAnalyses(account: String, recordingId: String)

  @Query("DELETE FROM remote_selection WHERE account = :account AND recordingKey = :recordingKey")
  suspend fun deleteSelection(account: String, recordingKey: String)
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

/** The markers of the migrations that rewrite what an older version stored. */
@Dao
interface MigrationDao {
  @Query("SELECT COUNT(*) FROM migration WHERE id = :id")
  suspend fun runs(id: String): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun mark(row: MigrationRow)
}
