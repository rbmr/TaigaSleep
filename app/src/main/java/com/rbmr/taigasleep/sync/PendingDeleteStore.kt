package com.rbmr.taigasleep.sync

import android.content.Context
import android.content.SharedPreferences
import com.rbmr.taigasleep.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class PendingDelete(
    val remoteId: String,
    /** The account whose copy is to be deleted. A delete is never sent to another account. */
    val account: String = "",
    val requestedAtMs: Long,
)

/** Where the queued deletes are kept: one document under a preference key. */
internal interface PendingDeleteStorage {
  fun read(): String?

  fun write(json: String)
}

internal class PrefsPendingDeleteStorage(private val prefs: SharedPreferences) :
    PendingDeleteStorage {
  override fun read(): String? = prefs.getString(KEY, null)

  override fun write(json: String) {
    prefs.edit().putString(KEY, json).apply()
  }

  private companion object {
    const val KEY = "pending_remote_deletes"
  }
}

/**
 * Remote deletes that could not be completed yet. The local recording and its results are already
 * gone; this only keeps the recording id, with the account it belongs to, until that account can be
 * reached. A delete queued for one account is kept for it and never sent while another is active.
 */
class PendingDeleteStore internal constructor(
    private val storage: PendingDeleteStorage,
    private val account: () -> String? = { null },
) {
  private val lock = Any()
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  /** Account key -> remote id -> the delete queued for that account. */
  private val accounts = mutableMapOf<String, MutableMap<String, PendingDelete>>()

  private val _pending = MutableStateFlow<Map<String, PendingDelete>>(emptyMap())
  val pending: StateFlow<Map<String, PendingDelete>> = _pending.asStateFlow()

  init {
    load()
    refreshAccount()
  }

  private fun active(): String = account().orEmpty()

  fun add(remoteId: String, account: String? = null) {
    if (remoteId.isBlank()) return
    val target = account ?: active()
    // A delete that cannot be attributed to an account could only be sent to whoever logs in next.
    if (target.isBlank()) return
    synchronized(lock) {
      val byAccount = accounts.getOrPut(target) { mutableMapOf() }
      if (byAccount[remoteId] != null) return
      byAccount[remoteId] = PendingDelete(remoteId, target, System.currentTimeMillis())
      write()
      publish()
    }
  }

  fun remove(remoteId: String, account: String? = null) {
    synchronized(lock) {
      val target = account ?: active()
      val byAccount = accounts[target] ?: return
      byAccount.remove(remoteId) ?: return
      if (byAccount.isEmpty()) accounts.remove(target)
      write()
      publish()
    }
  }

  /** The remote ids queued for one account, the active one by default. */
  fun remoteIds(account: String? = null): Set<String> {
    synchronized(lock) {
      val target = account ?: active()
      return accounts[target]?.keys?.toSet().orEmpty()
    }
  }

  /** The active account changed: publish its queue. */
  fun refreshAccount() {
    synchronized(lock) {
      val target = active()
      if (target.isNotBlank()) adoptUnscoped(target)
      publish()
    }
  }

  private fun publish() {
    _pending.value = accounts[active()]?.toMap().orEmpty()
  }

  /** A queue written before deletes carried an account belongs to the account active now. */
  private fun adoptUnscoped(target: String) {
    val unscoped = accounts.remove(UNSCOPED) ?: return
    val byAccount = accounts.getOrPut(target) { mutableMapOf() }
    for ((remoteId, entry) in unscoped) {
      byAccount.putIfAbsent(remoteId, entry.copy(account = target))
    }
    write()
  }

  private fun load() {
    val raw = storage.read() ?: return
    val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return
    val stored =
        if (KEY_ACCOUNTS in element) {
          runCatching { json.decodeFromJsonElement<StoredPendingDeletes>(element) }.getOrNull()
              ?: return
        } else {
          val legacy =
              runCatching { json.decodeFromString<Map<String, PendingDelete>>(raw) }.getOrNull()
                  ?: return
          StoredPendingDeletes(mapOf(UNSCOPED to legacy))
        }
    for ((accountKey, entries) in stored.accounts) {
      accounts[accountKey] = entries.toMutableMap()
    }
  }

  private fun write() {
    storage.write(json.encodeToString(StoredPendingDeletes(accounts)))
  }

  companion object {
    private const val KEY_ACCOUNTS = "accounts"
    private const val UNSCOPED = "unscoped"

    fun of(context: Context): PendingDeleteStore = context.appContainer.pendingDeleteStore
  }
}

/** The account-keyed queue document; it ports directly to a table keyed by account and remote id. */
@Serializable
private data class StoredPendingDeletes(
    val accounts: Map<String, Map<String, PendingDelete>> = emptyMap()
)
