package com.rbmr.taigasleep.sync.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rbmr.taigasleep.backend.AnalysisResultDto
import kotlinx.serialization.encodeToString

/**
 * The one store of what the app learned from the server: the sync state of every recording, the
 * server's recording list, the analyses and their results, and the deletes still owed to the
 * server. Every row is keyed by account, and the recordings on disk stay where they are: a row
 * refers to one by its recording id.
 *
 * The schema is exported to `app/schemas`, so a change to a table is reviewed as a diff and shipped
 * with a migration written against the schema that was released.
 */
@Database(
    entities =
        [
            UploadStateRow::class,
            RemoteRecordingRow::class,
            RemoteAnalysisRow::class,
            RemoteSelectionRow::class,
            AnalysisResultRow::class,
            PendingDeleteRow::class,
        ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(RowConverters::class)
abstract class TaigaDatabase : RoomDatabase() {
  abstract fun uploadStates(): UploadStateDao

  abstract fun remoteRecordings(): RemoteRecordingDao

  abstract fun library(): LibraryDao

  abstract fun analysisResults(): AnalysisResultDao

  abstract fun pendingDeletes(): PendingDeleteDao

  companion object {
    const val FILE_NAME = "taiga.db"

    /**
     * Version 1 also held a table of marker rows, one per step of an import the app no longer runs.
     * Dropping it leaves what every other table holds on an installed phone untouched.
     */
    private val DROP_IMPORT_MARKERS =
        object : Migration(1, 2) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS migration")
          }
        }

    /**
     * A payload saved before the row kept its own headline gets one derived from it, once, so the
     * library can draw every cached run from its summary without reading the payload.
     */
    private val FILL_MISSING_SUMMARIES =
        object : RoomDatabase.Callback() {
          override fun onOpen(db: SupportSQLiteDatabase) {
            fillMissingResultSummaries(db)
          }
        }

    fun open(context: Context): TaigaDatabase =
        Room.databaseBuilder(context.applicationContext, TaigaDatabase::class.java, FILE_NAME)
            .addMigrations(DROP_IMPORT_MARKERS)
            .addCallback(FILL_MISSING_SUMMARIES)
            .build()
  }
}

/**
 * Writes the headline of every stored payload that has none, from the payload itself. Rows the app
 * writes already carry it; this is for the ones that predate that.
 */
internal fun fillMissingResultSummaries(db: SupportSQLiteDatabase) {
  val missing = mutableListOf<Array<String>>()
  db.query(
          "SELECT account, recordingKey, analysisId, resultJson FROM analysis_result " +
              "WHERE summaryJson IS NULL AND resultJson IS NOT NULL"
      )
      .use { cursor ->
        while (cursor.moveToNext()) {
          val summary = summaryJsonOf(cursor.getString(3)) ?: continue
          missing +=
              arrayOf(summary, cursor.getString(0), cursor.getString(1), cursor.getString(2))
        }
      }
  for (args in missing) {
    db.execSQL(
        "UPDATE analysis_result SET summaryJson = ? WHERE account = ? " +
            "AND recordingKey = ? AND analysisId = ?",
        args,
    )
  }
}

/** The headline of [resultJson], or null when the payload cannot be read. */
private fun summaryJsonOf(resultJson: String): String? =
    runCatching {
      rowJson.encodeToString(summaryOf(rowJson.decodeFromString<AnalysisResultDto>(resultJson)))
    }
        .getOrNull()
