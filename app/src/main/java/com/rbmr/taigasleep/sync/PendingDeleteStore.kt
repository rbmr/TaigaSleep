package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.sync.db.PendingDeleteDao
import com.rbmr.taigasleep.sync.db.toPendingDelete
import com.rbmr.taigasleep.sync.db.toRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class PendingDelete(
    val remoteId: String,
    /** The account whose copy is to be deleted. A delete is never sent to another account. */
    val account: String = "",
    val requestedAtMs: Long,
)

/**
 * Remote deletes that could not be completed yet. The local recording and its results are already
 * gone; this only keeps the recording id, with the account it belongs to, until that account can be
 * reached. A delete queued for one account is kept for it and never sent while another is active.
 *
 * The queue is a table of [com.rbmr.taigasleep.sync.db.TaigaDatabase], so it is durable without a
 * document of its own. [pending] is the active account's queue as the database publishes it, and is
 * what History observes; reads and writes are suspend and wait for the one-time import of the queue
 * an older version kept in the preferences before they touch it.
 */
class PendingDeleteStore internal constructor(
    private val deletes: PendingDeleteDao,
    private val accounts: StateFlow<String?>,
    scope: CoroutineScope,
    /** The one-time move of the files an older version wrote into the database. */
    private val awaitLegacyImport: suspend () -> Unit = {},
) {
  /** The queued deletes of the account that is active now. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val pending: StateFlow<Map<String, PendingDelete>> =
      flow {
            awaitLegacyImport()
            emitAll(accounts.flatMapLatest { deletes.observe(it.orEmpty()) })
          }
          .map { rows -> rows.associate { it.remoteId to it.toPendingDelete() } }
          .stateIn(scope, SharingStarted.Eagerly, emptyMap())

  private val writes = Mutex()

  private fun active(): String = accounts.value.orEmpty()

  suspend fun add(remoteId: String, account: String? = null) {
    if (remoteId.isBlank()) return
    val target = account ?: active()
    // A delete that cannot be attributed to an account could only be sent to whoever logs in next.
    if (target.isBlank()) return
    awaitLegacyImport()
    writes.withLock {
      if (deletes.row(target, remoteId) != null) return
      deletes.upsert(PendingDelete(remoteId, target, System.currentTimeMillis()).toRow(target))
    }
  }

  suspend fun remove(remoteId: String, account: String? = null) {
    awaitLegacyImport()
    deletes.delete(account ?: active(), remoteId)
  }

  /** The remote ids queued for one account, the active one by default. */
  suspend fun remoteIds(account: String? = null): Set<String> {
    awaitLegacyImport()
    return deletes.rows(account ?: active()).map { it.remoteId }.toSet()
  }

  companion object {
    fun of(context: Context): PendingDeleteStore = context.appContainer.pendingDeleteStore
  }
}
