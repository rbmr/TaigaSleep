package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.sync.db.RemoteAnalysisRow
import com.rbmr.taigasleep.sync.db.RemoteRecordingDao
import com.rbmr.taigasleep.sync.db.RemoteRecordingRow
import com.rbmr.taigasleep.sync.db.RemoteSelectionRow
import com.rbmr.taigasleep.sync.db.toAnalysisDto
import com.rbmr.taigasleep.sync.db.toRecordingDto
import com.rbmr.taigasleep.sync.db.toRemoteAnalysisRow
import com.rbmr.taigasleep.sync.db.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The last remote recording list of every account, so History can still show remote-only recordings
 * when the server is temporarily unreachable. Each account has its own rows: a list fetched for one
 * is never shown for another, and logging back in finds it again.
 *
 * The list, its runs and the run each recording shows live in
 * [com.rbmr.taigasleep.sync.db.TaigaDatabase], so nothing is read from or written to a file here.
 * [observe] and [observeSelected] are the queries a screen follows, and every read waits for the
 * one-time import of the list an older version kept in a file.
 *
 * A list is the rows of one account. An account with no rows has no saved list yet, which is also
 * what the server answering with nothing leaves behind: a History row is never drawn from a list
 * that does not exist.
 *
 * A recording whose server copy is gone is removed for the account it belongs to: [removed] holds the
 * ids a completed delete or a missing recording took out of the list of the active account, so a
 * fetch that started earlier cannot put it back.
 */
class RemoteHistoryCache internal constructor(
    private val dao: RemoteRecordingDao,
    private val account: () -> String? = { null },
    /** The one-time move of the files an older version wrote into the database. */
    private val awaitLegacyImport: suspend () -> Unit = {},
) {
  private val lock = Any()
  private val removedByAccount = mutableMapOf<String, MutableSet<String>>()
  private val _removed = MutableStateFlow<Set<String>>(emptySet())

  /** Recording ids that must not return to the active account's list for the rest of this process. */
  val removed: StateFlow<Set<String>> = _removed.asStateFlow()

  /** Serialises the writes of one account's list, so two of them cannot lose each other. */
  private val writes = Mutex()

  /** The saved list of [accountKey] as the database publishes it; null when it has none. */
  fun observe(accountKey: String): Flow<RecordingSummaryListDto?> =
      if (accountKey.isBlank()) {
        flowOf(null)
      } else {
        flow {
          awaitLegacyImport()
          emitAll(
              combine(dao.observe(accountKey), dao.observeAnalyses(accountKey)) { rows, runs ->
                rows.toList(runs)
              }
          )
        }
      }

  /** The run each recording of [accountKey] shows, as the database publishes it. */
  fun observeSelected(accountKey: String): Flow<Map<String, String>> =
      if (accountKey.isBlank()) {
        flowOf(emptyMap())
      } else {
        flow {
          awaitLegacyImport()
          emitAll(
              dao.observeSelections(accountKey).map { rows ->
                rows.associate { it.recordingKey to it.analysisId }
              }
          )
        }
      }

  suspend fun load(accountKey: String): RecordingSummaryListDto? {
    if (accountKey.isBlank()) return null
    awaitLegacyImport()
    return dao.rows(accountKey).toList(dao.analysisRows(accountKey))
  }

  /**
   * The run each recording shows, by recording key, for the account that cached the list. Empty for
   * another account or before the list was ever fetched.
   */
  suspend fun selected(accountKey: String): Map<String, String> {
    if (accountKey.isBlank()) return emptyMap()
    awaitLegacyImport()
    return dao.selections(accountKey).associate { it.recordingKey to it.analysisId }
  }

  /** Remember the run a recording shows. Null forgets it, as when the server no longer has a run. */
  suspend fun select(accountKey: String, key: String, analysisId: String?) {
    if (accountKey.isBlank() || key.isBlank()) return
    awaitLegacyImport()
    writes.withLock {
      if (analysisId.isNullOrBlank()) {
        dao.deleteSelection(accountKey, key)
      } else {
        dao.select(RemoteSelectionRow(accountKey, key, analysisId))
      }
    }
  }

  /** Replaces the server's list for [accountKey], keeping the run each recording shows. */
  suspend fun save(accountKey: String, recordings: RecordingSummaryListDto) {
    if (accountKey.isBlank()) return
    awaitLegacyImport()
    val rows =
        recordings.items.mapIndexed { position, item ->
          item.recording.toRow(accountKey, position)
        }
    val runs =
        recordings.items.flatMap { item ->
          item.analyses.map { run ->
            run.copy(recording = item.recording.id).toRemoteAnalysisRow(accountKey)
          }
        }
    writes.withLock { dao.replace(accountKey, rows, runs) }
  }

  /**
   * One recording of the saved list, as a read of that recording just found it: its row keeps the
   * place it has, a recording the list did not reach is added at the end, and the runs are the ones
   * the server lists now. Nothing is written while the account has no saved list, because a list
   * that does not exist is not extended by one recording.
   */
  suspend fun saveSummary(accountKey: String, summary: RecordingSummaryDto) {
    if (accountKey.isBlank()) return
    awaitLegacyImport()
    val rows = dao.rows(accountKey)
    val existing = rows.firstOrNull { it.recordingId == summary.recording.id }
    if (existing == null && rows.isEmpty()) return
    val position = existing?.position ?: (rows.maxOfOrNull { it.position } ?: -1) + 1
    writes.withLock {
      dao.upsert(listOf(summary.recording.toRow(accountKey, position)))
      dao.replaceAnalyses(
          accountKey,
          summary.recording.id,
          summary.analyses.map {
            it.copy(recording = summary.recording.id).toRemoteAnalysisRow(accountKey)
          },
      )
    }
  }

  /**
   * The server no longer has this recording for this account: it leaves the saved list. With
   * [keepOut] every list fetched afterwards drops it too, which is what a completed delete does; a
   * recording that is still on the phone can be uploaded again, so it is not kept out.
   */
  suspend fun remove(accountKey: String, recordingId: String, keepOut: Boolean = true) {
    if (accountKey.isBlank() || recordingId.isBlank()) return
    if (keepOut) {
      synchronized(lock) {
        removedByAccount.getOrPut(accountKey) { mutableSetOf() } += recordingId
        if (accountKey == active()) _removed.update { it + recordingId }
      }
    }
    awaitLegacyImport()
    writes.withLock {
      dao.delete(accountKey, recordingId)
      dao.deleteAnalyses(accountKey, recordingId)
      dao.deleteSelection(accountKey, recordingId)
      dao.deleteSelection(accountKey, remoteKey(recordingId))
    }
  }

  /**
   * An older version kept only one account's list, keyed by the address and the email. The first time
   * this version sees that session, the saved list moves to the account key it now uses.
   */
  suspend fun adopt(oldKey: String, accountKey: String) {
    if (oldKey.isBlank() || accountKey.isBlank() || oldKey == accountKey) return
    awaitLegacyImport()
    writes.withLock {
      if (dao.count(accountKey) > 0) return@withLock
      dao.adopt(oldKey, accountKey)
    }
    synchronized(lock) {
      removedByAccount.remove(oldKey)?.let { removed ->
        removedByAccount.getOrPut(accountKey) { mutableSetOf() } += removed
      }
    }
  }

  /** The active account changed: publish the removals of the new one. */
  fun refreshAccount() {
    synchronized(lock) {
      val target = active()
      if (target.isNotBlank()) adoptUnscoped(target)
      _removed.value = removedByAccount[target].orEmpty()
    }
  }

  private fun active(): String = account().orEmpty()

  /** A removal recorded before removals carried an account belongs to the account active now. */
  private fun adoptUnscoped(target: String) {
    val unscoped = removedByAccount.remove(UNSCOPED) ?: return
    removedByAccount.getOrPut(target) { mutableSetOf() } += unscoped
  }

  companion object {
    private const val UNSCOPED = "unscoped"

    fun of(context: Context): RemoteHistoryCache = context.appContainer.remoteHistoryCache
  }
}

/** The list the rows hold, in the order the server sent it, with the runs each recording lists. */
private fun List<RemoteRecordingRow>.toList(
    runs: List<RemoteAnalysisRow>,
): RecordingSummaryListDto? {
  if (isEmpty()) return null
  val byRecording = runs.groupBy { it.recordingId }
  return RecordingSummaryListDto(
      sortedBy { it.position }.map { row ->
        RecordingSummaryDto(
            recording = row.toRecordingDto(),
            analyses = byRecording[row.recordingId].orEmpty().sortedBy { it.analysisId }.map {
              it.toAnalysisDto()
            },
        )
      }
  )
}

/** The list without the given recordings, matched by recording id. */
fun RecordingSummaryListDto.withoutRecordings(recordingIds: Set<String>): RecordingSummaryListDto =
    if (recordingIds.isEmpty()) this
    else RecordingSummaryListDto(items.filterNot { it.recording.id in recordingIds })
