package com.rbmr.taigasleep.sync.db

import android.content.SharedPreferences
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.PendingDelete
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.resultCacheDirectoryName
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Moves what an older version kept in separate files into [TaigaDatabase], once. Every source it
 * reads still belongs to the version that wrote it: each recording's `sync.json`, the shared
 * document in the upload preferences, `remote-recordings.json`, the JSON files under `analysis` in
 * both key schemes and the flat format an older version wrote, and the queued deletes in their
 * preferences.
 *
 * The files are read here and nowhere else, and are left exactly as they are. A marker row records
 * that the migration ran, so the next start does nothing; the writes are upserts, so an interrupted
 * run only repeats work.
 *
 * Rows are written under the account key of the session, which the app had while the files were
 * written. Without a session there is no account to write them for, so the migration waits for the
 * next start instead of writing state that cannot be attributed. That is also what the stores wait
 * for: [awaitMigrated] runs this first, and [start] retries it whenever an account becomes
 * available, so no store reads or writes the database before the state the files hold is in it.
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
  private val importLock = Mutex()

  @Volatile private var imported = false

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
   * Runs [migrate] unless it already ran, and returns when the old files are no longer waiting.
   * Every store call awaits this before it touches the database. Without a session the files cannot
   * be attributed yet, so nothing is imported and the next call tries again.
   */
  suspend fun awaitMigrated(database: TaigaDatabase) {
    if (imported) return
    withContext(Dispatchers.IO) {
      importLock.withLock {
        if (imported) return@withLock
        val ran =
            try {
              migrate(database)
            } catch (e: Exception) {
              // The writes are upserts, so an interrupted run only repeats work next time.
              Diagnostics.log("legacy_migration_failed", mapOf("error" to e.toString()))
              false
            }
        imported = ran || database.migrations().runs(MARKER) > 0
      }
    }
  }

  /**
   * Starts the import at app start and retries it whenever an account appears, so a phone that is
   * upgraded while logged out still moves its state as soon as someone logs in.
   */
  fun start(scope: CoroutineScope, database: TaigaDatabase, accounts: Flow<String?>) {
    scope.launch {
      accounts.distinctUntilChanged().collect { awaitMigrated(database) }
    }
  }

  /**
   * Every account the files name, and the active one first: a row written before facts carried an
   * account is adopted by the account that is logged in now, which is what the stores did on start.
   */
  private fun accountsOnDisk(active: String): Set<String> {
    val files = LegacyUploadFiles(recordingsRoot, uploadPreferences)
    val found = mutableSetOf<String>()
    for (id in files.ids()) {
      files.read(id)?.let { found += accountKeys(it) }
    }
    if (historyFile.isFile) {
      runCatching { historyFile.readText() }.getOrNull()?.let {
        found += accountKeys(it)
        found += singleDocumentAccount(it)
      }
    }
    found += accountKeys(LegacyPendingDeleteFile(deletePreferences).read().orEmpty())
    found += active
    return found
  }

  /** The active account first, then the rest: the first one adopts the state written without one. */
  private fun orderedAccounts(active: String, accounts: Set<String>): List<String> =
      listOf(active) + (accounts - active).sorted()

  /**
   * The sync state of every recording the files hold, one row per account. A document written
   * before facts carried an account belongs to the account that is logged in, exactly as the store
   * that wrote it adopted it on start.
   */
  private fun readUploadStates(active: String, accounts: Set<String>): List<UploadStateRow> {
    val documents = uploadDocuments()
    for (byAccount in documents.values) {
      val unscoped = byAccount.remove(UNSCOPED) ?: continue
      if (byAccount[active] == null) byAccount[active] = unscoped
    }
    val rows = mutableListOf<UploadStateRow>()
    for (account in orderedAccounts(active, accounts)) {
      for (byAccount in documents.values) {
        val record = byAccount[account] ?: continue
        rows += record.toRow(account)
      }
    }
    return rows
  }

  /**
   * Every recording whose state a file holds, by account. The document each recording wrote is read
   * first, then the shared document of an older version, which only counts for a recording that is
   * still on this phone and only where it is newer.
   */
  private fun uploadDocuments(): Map<String, MutableMap<String, UploadRecord>> {
    val files = LegacyUploadFiles(recordingsRoot, uploadPreferences)
    val documents = linkedMapOf<String, MutableMap<String, UploadRecord>>()
    for (id in files.ids().sorted()) {
      val raw = files.read(id) ?: continue
      val element =
          runCatching { rowJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: continue
      val stored =
          if (KEY_ACCOUNTS in element) {
            runCatching { rowJson.decodeFromJsonElement<StoredRecords>(element) }.getOrNull()
                ?: continue
          } else {
            val legacy =
                runCatching { rowJson.decodeFromString<UploadRecord>(raw) }.getOrNull() ?: continue
            StoredRecords(mapOf(UNSCOPED to legacy))
          }
      documents[id] = stored.accounts.toMutableMap()
    }
    val shared = files.shared() ?: return documents
    val old =
        runCatching { rowJson.decodeFromString<Map<String, UploadRecord>>(shared) }
            .getOrDefault(emptyMap())
    for ((id, record) in old) {
      // Only a recording on this phone can hold its state there now.
      if (!files.hasRecording(id)) continue
      val byAccount = documents.getOrPut(id) { mutableMapOf() }
      val at = if (byAccount.isEmpty()) UNSCOPED else byAccount.keys.first()
      val merged = byAccount[at]?.let { current -> current.copyFrom(record) } ?: record
      byAccount[at] = merged
    }
    return documents
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

  /**
   * The remote deletes every file still owes the server, one row per account. A queue written
   * before deletes carried an account belongs to the account that is logged in now.
   */
  private fun readPendingDeletes(active: String, accounts: Set<String>): List<PendingDeleteRow> {
    val document = LegacyPendingDeleteFile(deletePreferences).read() ?: return emptyList()
    val element =
        runCatching { rowJson.parseToJsonElement(document) }.getOrNull() as? JsonObject
            ?: return emptyList()
    val stored =
        if (KEY_ACCOUNTS in element) {
          runCatching { rowJson.decodeFromJsonElement<StoredPendingDeletes>(element) }.getOrNull()
              ?: return emptyList()
        } else {
          val legacy =
              runCatching { rowJson.decodeFromString<Map<String, PendingDelete>>(document) }
                  .getOrNull() ?: return emptyList()
          StoredPendingDeletes(mapOf(UNSCOPED to legacy))
        }
    val byAccount = stored.accounts.mapValues { it.value.toMutableMap() }.toMutableMap()
    val unscoped = byAccount.remove(UNSCOPED)
    if (unscoped != null) {
      val target = byAccount.getOrPut(active) { mutableMapOf() }
      for ((remoteId, entry) in unscoped) {
        target.putIfAbsent(remoteId, entry.copy(account = active))
      }
    }
    return orderedAccounts(active, accounts).flatMap { account ->
      byAccount[account].orEmpty().values.map { it.toRow(account) }
    }
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

  private fun UploadRecord.copyFrom(old: UploadRecord): UploadRecord =
      if (updatedAtMs >= old.updatedAtMs) this else old

  private data class HistoryRows(
      val recordings: List<RemoteRecordingRow>,
      val analyses: List<RemoteAnalysisRow>,
      val selections: List<RemoteSelectionRow>,
  )

  private companion object {
    const val MARKER = "files-v1"

    /** The key an older version wrote for facts that carried no account yet. */
    const val UNSCOPED = "unscoped"

    /** The key of the document the account-keyed format holds its accounts under. */
    const val KEY_ACCOUNTS = "accounts"

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

/**
 * The `sync.json` files of the recordings, and the single document an older version kept them in.
 * Only [LegacyFilesMigration] reads these.
 */
internal class LegacyUploadFiles(private val root: File, private val prefs: SharedPreferences) {
  fun ids(): Set<String> =
      root.listFiles { file -> file.isDirectory && File(file, RecordingStore.SYNC_FILE).isFile }
          ?.map { it.name }
          ?.toSet()
          .orEmpty()

  fun read(id: String): String? {
    val file = File(root, "$id/${RecordingStore.SYNC_FILE}")
    return if (file.isFile) file.readText() else null
  }

  /** Whether this id is a recording on this phone, and can therefore hold its own state file. */
  fun hasRecording(id: String): Boolean = File(root, id).isDirectory

  /** The old single-document format, read once so an upgrade does not lose queued uploads. */
  fun shared(): String? = prefs.getString(KEY_RECORDS, null)

  private companion object {
    const val KEY_RECORDS = "records"
  }
}

/** The queued deletes an older version kept under one preference key. Only the migration reads it. */
internal class LegacyPendingDeleteFile(private val prefs: SharedPreferences) {
  fun read(): String? = prefs.getString(KEY, null)

  private companion object {
    const val KEY = "pending_remote_deletes"
  }
}

/**
 * The account-keyed document of one recording, as an older version wrote it. It ports directly to
 * the table keyed by account and recording id.
 */
@Serializable
private data class StoredRecords(val accounts: Map<String, UploadRecord> = emptyMap())

/** The account-keyed queue document an older version wrote. */
@Serializable
private data class StoredPendingDeletes(
    val accounts: Map<String, Map<String, PendingDelete>> = emptyMap()
)
