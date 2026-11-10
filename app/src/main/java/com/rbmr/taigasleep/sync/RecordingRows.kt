package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.sync.db.LibraryDao
import com.rbmr.taigasleep.sync.db.toEntries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * One recording as the database describes the library: its sync state, its server row with the runs
 * the server lists, the run it shows, and the payloads this phone holds for it.
 *
 * The recordings on disk are not in the database, so an entry never carries one: the library joins
 * an entry with the recording of the same id.
 */
data class LibraryEntry(
    /** The recording's key: the phone's id when the phone has it, the remote key otherwise. */
    val key: String,
    val record: UploadRecord?,
    /** The server's copy, or null when the server does not list this recording for the account. */
    val recording: RecordingDto?,
    /** The runs the server lists for [recording]. */
    val runs: List<AnalysisDto>,
    /** The run the user chose, when the choice is still one of [runs]. */
    val selectedAnalysisId: String?,
    /** The payloads of this phone, by the run they belong to. */
    val cachedResults: List<AnalysisDto>,
    /**
     * The server copy is on its way out or already gone: it is never drawn as remote. A recording
     * that is still on this phone stays as the recording it is.
     */
    val hidden: Boolean,
)

/**
 * The one read of the library: every fact the screens show about a recording, joined in one query
 * of [com.rbmr.taigasleep.sync.db.TaigaDatabase] and parameterised by the account that is active.
 *
 * The ids of the recordings on this phone are a parameter of the query, because they decide the key
 * a server row belongs to. A write anywhere in the app reaches the screen through [observe] without
 * a reload.
 */
class RecordingRows internal constructor(
    private val dao: LibraryDao,
    /** The one-time move of the files an older version wrote into the database. */
    private val awaitLegacyImport: suspend () -> Unit = {},
) {
  /** The library of [account], with [localIds] the recordings on this phone. */
  fun observe(account: String, localIds: List<String>): Flow<List<LibraryEntry>> =
      flow {
        awaitLegacyImport()
        emitAll(dao.observe(account, localIds, REMOTE_KEY_PREFIX).map { it.toEntries(account) })
      }

  /** The same read, once, for a step that is not drawing a screen. */
  suspend fun load(account: String, localIds: List<String>): List<LibraryEntry> {
    awaitLegacyImport()
    return dao.rows(account, localIds, REMOTE_KEY_PREFIX).toEntries(account)
  }
}
