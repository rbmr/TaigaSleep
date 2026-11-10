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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The last remote recording list of every account, so History can still show remote-only recordings
 * when the server is temporarily unreachable. Each account has its own rows: a list fetched for one
 * is never shown for another, and logging back in finds it again.
 *
 * The list, its runs, the run each recording shows and the recordings the server no longer has live
 * in [com.rbmr.taigasleep.sync.db.TaigaDatabase], so nothing is read from or written to a file here
 * and nothing of it is held in this process either: a recording whose server copy is gone is a row
 * marked hidden.
 *
 * A list is the rows of one account. An account with no rows has no saved list yet, which is also
 * what the server answering with nothing leaves behind: a History row is never drawn from a list
 * that does not exist.
 */
class RemoteHistoryCache internal constructor(private val dao: RemoteRecordingDao) {
  /** Serialises the writes of one account's list, so two of them cannot lose each other. */
  private val writes = Mutex()

  suspend fun load(accountKey: String): RecordingSummaryListDto? {
    if (accountKey.isBlank()) return null
    return dao.rows(accountKey).toList(dao.analysisRows(accountKey))
  }

  /**
   * The run each recording shows, by recording key, for the account that cached the list. Empty for
   * another account or before the list was ever fetched.
   */
  suspend fun selected(accountKey: String): Map<String, String> {
    if (accountKey.isBlank()) return emptyMap()
    return dao.selections(accountKey).associate { it.recordingKey to it.analysisId }
  }

  /** The run one recording shows, or null when no choice was saved for it. */
  suspend fun selected(accountKey: String, key: String): String? {
    if (accountKey.isBlank() || key.isBlank()) return null
    return dao.selection(accountKey, key)
  }

  /**
   * The server's start time of one recording in this account's saved list, or null when the list
   * does not have it. The start date of a recording the phone no longer has is derived from it.
   */
  suspend fun startedAt(accountKey: String, recordingId: String): String? {
    if (accountKey.isBlank() || recordingId.isBlank()) return null
    return dao.startedAt(accountKey, recordingId)
  }

  /** Remember the run a recording shows. Null forgets it, as when the server no longer has a run. */
  suspend fun select(accountKey: String, key: String, analysisId: String?) {
    if (accountKey.isBlank() || key.isBlank()) return
    writes.withLock {
      if (analysisId.isNullOrBlank()) {
        dao.deleteSelection(accountKey, key)
      } else if (dao.selection(accountKey, key) != analysisId) {
        dao.select(RemoteSelectionRow(accountKey, key, analysisId))
      }
    }
  }

  /** Replaces the server's list for [accountKey], keeping the run each recording shows. */
  suspend fun save(accountKey: String, recordings: RecordingSummaryListDto) {
    if (accountKey.isBlank()) return
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
   * the server lists now. Nothing is written for a recording the server no longer has, because the
   * list must not show it again.
   */
  suspend fun saveSummary(accountKey: String, summary: RecordingSummaryDto) {
    if (accountKey.isBlank()) return
    if (dao.isHidden(accountKey, summary.recording.id) > 0) return
    val rows = dao.rows(accountKey)
    val existing = rows.firstOrNull { it.recordingId == summary.recording.id }
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
   * The server no longer has this recording for this account: it leaves the list. With [keepOut] the
   * row stays as a marker, so every list fetched afterwards drops it too, which is what a completed
   * delete does; a recording that is still on the phone can be uploaded again, so it is not kept out.
   */
  suspend fun remove(accountKey: String, recordingId: String, keepOut: Boolean = true) {
    if (accountKey.isBlank() || recordingId.isBlank()) return
    writes.withLock {
      if (keepOut) dao.hide(accountKey, recordingId) else dao.delete(accountKey, recordingId)
      dao.deleteSelection(accountKey, recordingId)
      dao.deleteSelection(accountKey, remoteKey(recordingId))
    }
  }

  /**
   * The recording ids the server no longer has for [accountKey]. They are rows, not a memory of this
   * process: a delete that finished before a list fetch returns cannot be undone by that fetch.
   */
  suspend fun removed(accountKey: String): Set<String> {
    if (accountKey.isBlank()) return emptySet()
    return dao.hiddenIds(accountKey).toSet()
  }

  /** Whether the server no longer has this recording for [accountKey]. */
  suspend fun isHidden(accountKey: String, recordingId: String): Boolean {
    if (accountKey.isBlank() || recordingId.isBlank()) return false
    return dao.isHidden(accountKey, recordingId) > 0
  }

  companion object {
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
