package com.rbmr.taigasleep.sync.db

import android.content.SharedPreferences
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.sync.PendingDeleteStorage
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.PrefsPendingDeleteStorage
import com.rbmr.taigasleep.sync.RecordingDirectoryStorage
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.UploadRecordsStorage
import com.rbmr.taigasleep.sync.UploadStore
import com.rbmr.taigasleep.sync.resultCacheDirectoryName
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Moves what an older version kept in separate files into [TaigaDatabase], once. Every source it
 * reads still belongs to the version that wrote it: each recording's `sync.json`, the shared
 * document in the upload preferences, `remote-recordings.json`, the JSON files under `analysis` in
 * both key schemes and the flat format an older version wrote, and the queued deletes in their
 * preferences.
 *
 * The files are read through the stores that wrote them and are left exactly as they are. A marker
 * row records that the migration ran, so the next start does nothing; the writes are upserts, so an
 * interrupted run only repeats work.
 *
 * Rows are written under the account key of the session, which the app had while the files were
 * written. Without a session there is no account to write them for, so the migration waits for the
 * next start instead of writing state that cannot be attributed.
 */
class LegacyFilesMigration(
    private val recordingsRoot: File,
    private val uploadPreferences: SharedPreferences,
    private val historyFile: File,
    private val analysisRoot: File,
    private val deletePreferences: SharedPreferences,
    private val account: () -> String?,
    /** The address-and-email key older versions saved one account's list under, if it is known. */
    private val legacyAccount: () -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
  /** Runs the migration if it has not run yet, and returns whether it did. */
  suspend fun migrate(database: TaigaDatabase): Boolean {
    if (database.migrations().runs(MARKER) > 0) return false
    val active = account()?.takeIf { it.isNotBlank() } ?: return false
    val accounts = accountsOnDisk(active)

    val uploads = readUploadStates(active, accounts)
    val history = readHistory(active, legacyAccount()?.takeIf { it.isNotBlank() }, accounts)
    val results = readAnalysisResults(active, accounts)
    val deletes = readPendingDeletes(active, accounts)

    database.uploadStates().upsert(uploads)
    database.remoteRecordings().upsert(history.recordings)
    database.remoteRecordings().upsertAnalyses(history.analyses)
    history.selections.forEach { database.remoteRecordings().select(it) }
    database.analysisResults().upsert(results)
    database.pendingDeletes().upsert(deletes)
    database.migrations().mark(MigrationRow(MARKER, now()))

    Diagnostics.log(
        "legacy_migration",
        mapOf(
            "upload_states" to uploads.size,
            "remote_recordings" to history.recordings.size,
            "remote_analyses" to history.analyses.size,
            "analysis_results" to results.size,
            "pending_deletes" to deletes.size,
        ),
    )
    return true
  }

  /**
   * Every account the files name, and the active one first: a row written before facts carried an
   * account is adopted by the account that is logged in now, which is what the stores do on start.
   */
  private fun accountsOnDisk(active: String): Set<String> {
    val storage = RecordingDirectoryStorage(recordingsRoot, uploadPreferences)
    val found = mutableSetOf<String>()
    for (id in storage.ids()) {
      storage.read(id)?.let { found += accountKeys(it) }
    }
    if (historyFile.isFile) {
      runCatching { historyFile.readText() }.getOrNull()?.let {
        found += accountKeys(it)
        found += singleDocumentAccount(it)
      }
    }
    found += accountKeys(PrefsPendingDeleteStorage(deletePreferences).read().orEmpty())
    found += active
    return found
  }

  /** The active account first, then the rest: the first one adopts the state written without one. */
  private fun orderedAccounts(active: String, accounts: Set<String>): List<String> =
      listOf(active) + (accounts - active).sorted()

  private fun readUploadStates(active: String, accounts: Set<String>): List<UploadStateRow> {
    var current: String? = null
    val store =
        UploadStore(
            ReadOnlyUploadStorage(
                RecordingDirectoryStorage(recordingsRoot, uploadPreferences)
            ),
            account = { current },
        )
    val rows = mutableListOf<UploadStateRow>()
    for (account in orderedAccounts(active, accounts)) {
      current = account
      store.refreshAccount()
      rows += store.records.value.values.map { it.toRow(account) }
    }
    return rows
  }

  /**
   * The server's list of every account with the runs it carries, and the run each recording shows.
   * A list one account saved under the address and the email is written under its account key, which
   * is what the app does with it on start.
   */
  private fun readHistory(
      active: String,
      legacy: String?,
      accounts: Set<String>,
  ): HistoryRows {
    val cache = RemoteHistoryCache(historyFile, account = { active })
    val adopt =
        legacy?.takeIf { it != active }?.let { old ->
          if (cache.load(active) == null && cache.load(old) != null) old else null
        }
    val documents =
        accounts.mapNotNull { key ->
          when {
            adopt == null -> key to key
            key == active -> adopt to active
            key == adopt -> null
            else -> key to key
          }
        }
    val recordings = mutableListOf<RemoteRecordingRow>()
    val analyses = mutableListOf<RemoteAnalysisRow>()
    val selections = mutableListOf<RemoteSelectionRow>()
    for ((savedUnder, owner) in documents) {
      cache.load(savedUnder)?.items?.forEachIndexed { position, item ->
        recordings += item.recording.toRow(owner, position)
        analyses += item.analyses.map { run -> run.toRemoteAnalysisRow(owner) }
      }
      cache.selected(savedUnder).forEach { (key, analysisId) ->
        if (key.isNotBlank() && analysisId.isNotBlank()) {
          selections += RemoteSelectionRow(owner, key, analysisId)
        }
      }
    }
    return HistoryRows(recordings, analyses, selections)
  }

  /**
   * Every payload the result cache holds, for every account whose results directory can be named.
   * The files an older version left directly under [analysisRoot] belong to the account that is
   * logged in, which is the account the cache moves them into on its first read.
   */
  private fun readAnalysisResults(active: String, accounts: Set<String>): List<AnalysisResultRow> =
      readResults(analysisRoot, active) +
          (analysisRoot.listFiles { file -> file.isDirectory } ?: emptyArray())
              .sortedBy { it.name }
              .flatMap { directory ->
                val owner =
                    accounts.firstOrNull { resultCacheDirectoryName(it) == directory.name }
                        ?: return@flatMap emptyList()
                readResults(directory, owner)
              }

  private fun readResults(directory: File, account: String): List<AnalysisResultRow> {
    val cache = ResultCache(directory)
    // A run keyed per analysis wins over the recording's single payload of the same run.
    val rows = linkedMapOf<Pair<String, String>, AnalysisResultRow>()
    val perAnalysis = mutableSetOf<Pair<String, String>>()
    for (file in directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
        .orEmpty()
        .sortedBy { it.name }) {
      val stem = file.name.removeSuffix(".json")
      val analysis = cache.load(NO_ACCOUNT, stem) ?: continue
      if (analysis.id.isBlank()) continue
      val key = recordingKeyOf(stem, analysis.id)
      val id = key to analysis.id
      if (id in perAnalysis) continue
      rows[id] = analysis.toResultRow(account, key)
      if (key != stem) perAnalysis += id
    }
    return rows.values.toList()
  }

  private fun readPendingDeletes(active: String, accounts: Set<String>): List<PendingDeleteRow> {
    var current: String? = null
    val store =
        PendingDeleteStore(
            ReadOnlyPendingDeleteStorage(PrefsPendingDeleteStorage(deletePreferences)),
            account = { current },
        )
    val rows = mutableListOf<PendingDeleteRow>()
    for (account in orderedAccounts(active, accounts)) {
      current = account
      store.refreshAccount()
      rows += store.pending.value.values.map { it.toRow(account) }
    }
    return rows
  }

  /**
   * The recording key of one cached payload: its file is named after the key, and after the run
   * too when the cache keeps one payload per analysis rather than one per recording.
   */
  private fun recordingKeyOf(stem: String, analysisId: String): String =
      if (stem != analysisId && stem.endsWith("-$analysisId")) {
        stem.removeSuffix("-$analysisId")
      } else {
        stem
      }

  private data class HistoryRows(
      val recordings: List<RemoteRecordingRow>,
      val analyses: List<RemoteAnalysisRow>,
      val selections: List<RemoteSelectionRow>,
  )

  private companion object {
    const val MARKER = "files-v1"

    /** The key an older version wrote for facts that carried no account yet. */
    const val UNSCOPED = "unscoped"

    /** The accounts a document written in the account-keyed format holds. */
    fun accountKeys(document: String): Set<String> {
      val root = objectOf(document) ?: return emptySet()
      val accounts = root["accounts"] as? JsonObject ?: return emptySet()
      return accounts.keys.filter { it.isNotBlank() && it != UNSCOPED }.toSet()
    }

    /** The account of the single-account document an older version wrote, if this is one. */
    fun singleDocumentAccount(document: String?): Set<String> {
      val root = objectOf(document ?: return emptySet()) ?: return emptySet()
      val key = (root["accountKey"] as? JsonPrimitive)?.contentOrNull
      return key?.takeIf { it.isNotBlank() && it != UNSCOPED }?.let { setOf(it) } ?: emptySet()
    }

    fun objectOf(document: String): JsonObject? =
        runCatching { rowJson.parseToJsonElement(document) as? JsonObject }.getOrNull()
  }
}

/** Reads a store without writing anything back: the files stay as the older version left them. */
private class ReadOnlyUploadStorage(private val delegate: UploadRecordsStorage) :
    UploadRecordsStorage {
  override fun ids(): Set<String> = delegate.ids()

  override fun read(id: String): String? = delegate.read(id)

  override fun write(id: String, json: String) = Unit

  override fun delete(id: String) = Unit

  override fun hasRecording(id: String): Boolean = delegate.hasRecording(id)

  override fun legacy(): String? = delegate.legacy()

  override fun clearLegacy() = Unit
}

/** Reads the queued deletes without rewriting their preferences. */
private class ReadOnlyPendingDeleteStorage(private val delegate: PendingDeleteStorage) :
    PendingDeleteStorage {
  override fun read(): String? = delegate.read()

  override fun write(json: String) = Unit
}
