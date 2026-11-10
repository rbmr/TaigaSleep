package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.sync.db.AnalysisResultDao
import com.rbmr.taigasleep.sync.db.toAnalysisDto
import com.rbmr.taigasleep.sync.db.toResultRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A local copy of a finished analysis. The backend remains authoritative, but History and the
 * recording detail keep working when the server is temporarily unreachable or the app is offline.
 *
 * Results are kept per account: one account's payload is never shown for another, and it is still
 * there when the user logs back into it. The rows live in
 * [com.rbmr.taigasleep.sync.db.TaigaDatabase], one per account, recording key and run, so nothing is
 * read from or written to a file here. [observe] is the query a screen follows, and every read waits
 * for the one-time import of the payloads an older version kept in files.
 *
 * A recording key names the History row a run belongs to; the run is its analysis id. What older
 * versions kept as a payload per recording and a payload per analysis are both rows here, so the two
 * key schemes became one.
 */
class ResultCache internal constructor(
    private val dao: AnalysisResultDao,
    /** The one-time move of the files an older version wrote into the database. */
    private val awaitLegacyImport: suspend () -> Unit = {},
) {
  /** Every payload of [account], as the database publishes it, by the History key it belongs to. */
  fun observe(account: String): Flow<Map<String, List<AnalysisDto>>> =
      if (account.isBlank()) {
        flowOf(emptyMap())
      } else {
        flow {
          awaitLegacyImport()
          emitAll(
              dao.observe(account).map { rows ->
                rows.groupBy { it.recordingKey }.mapValues { (_, runs) ->
                  runs.sortedBy { it.analysisId }.map { it.toAnalysisDto() }
                }
              }
          )
        }
      }

  suspend fun save(account: String, key: String, analysis: AnalysisDto): Boolean =
      store(account, key, analysis.id, analysis)

  /** Saves one run's payload without replacing another run of the same recording. */
  suspend fun save(
      account: String,
      key: String,
      analysisId: String?,
      analysis: AnalysisDto,
  ): Boolean =
      store(account, key, analysisId?.takeIf { it.isNotBlank() } ?: analysis.id, analysis)

  /**
   * The cached payload of one run, keyed by its analysis id. Without one, the newest run this phone
   * holds for the recording, which is what its single payload used to be.
   */
  suspend fun load(account: String, key: String, analysisId: String? = null): AnalysisDto? {
    awaitLegacyImport()
    if (!analysisId.isNullOrBlank()) return dao.row(account, key, analysisId)?.toAnalysisDto()
    return dao.rowsFor(account, key).maxByOrNull { it.updated }?.toAnalysisDto()
  }

  suspend fun has(account: String, key: String, analysisId: String?): Boolean {
    awaitLegacyImport()
    if (analysisId.isNullOrBlank()) return dao.rowsFor(account, key).isNotEmpty()
    return dao.row(account, key, analysisId) != null
  }

  /** Every run whose full payload is on this phone for [key], one per row. */
  suspend fun cached(account: String, key: String): List<AnalysisDto> {
    awaitLegacyImport()
    return dao.rowsFor(account, key).map { it.toAnalysisDto() }
  }

  suspend fun delete(account: String, key: String) {
    awaitLegacyImport()
    dao.deleteRecording(account, key)
  }

  private suspend fun store(
      account: String,
      key: String,
      analysisId: String,
      analysis: AnalysisDto,
  ): Boolean {
    awaitLegacyImport()
    // A row that cannot be named would only make the read that looks for it look like a failure.
    if (account.isBlank() || key.isBlank() || analysisId.isBlank()) return false
    val row = if (analysis.id == analysisId) analysis else analysis.copy(id = analysisId)
    return runCatching { dao.upsert(row.toResultRow(account, key)) }.isSuccess
  }
}
