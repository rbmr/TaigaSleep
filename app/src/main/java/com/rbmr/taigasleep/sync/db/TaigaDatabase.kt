package com.rbmr.taigasleep.sync.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    fun open(context: Context): TaigaDatabase =
        Room.databaseBuilder(context.applicationContext, TaigaDatabase::class.java, FILE_NAME)
            .addMigrations(DROP_IMPORT_MARKERS)
            .build()
  }
}
