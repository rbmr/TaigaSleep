package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
private data class CachedRemoteHistory(
    val recordings: RecordingSummaryListDto = RecordingSummaryListDto(),
    /** The run each recording shows, by recording key. */
    val selected: Map<String, String> = emptyMap(),
)

/**
 * The last remote recording list of every account, so History can still show remote-only recordings
 * when the server is temporarily unreachable. Each account has its own entry: a list fetched for one
 * is never shown for another, and logging back in finds it again.
 *
 * A recording whose server copy is gone is removed for the account it belongs to: [removed] holds the
 * ids a completed delete or a missing recording took out of the list of the active account, so a
 * fetch that started earlier cannot put it back.
 */
class RemoteHistoryCache(
    private val file: File,
    private val account: () -> String? = { null },
) {
  private val lock = Any()
  private val removedByAccount = mutableMapOf<String, MutableSet<String>>()
  private val _removed = MutableStateFlow<Set<String>>(emptySet())

  /** Recording ids that must not return to the active account's list for the rest of this process. */
  val removed: StateFlow<Set<String>> = _removed.asStateFlow()

  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  fun load(accountKey: String): RecordingSummaryListDto? {
    if (accountKey.isBlank()) return null
    return runCatching { read()?.accounts?.get(accountKey)?.recordings }.getOrNull()
  }

  /**
   * The run each recording shows, by recording key, for the account that cached the list. Empty for
   * another account or before the list was ever fetched.
   */
  fun selected(accountKey: String): Map<String, String> {
    if (accountKey.isBlank()) return emptyMap()
    return runCatching { read()?.accounts?.get(accountKey)?.selected }.getOrNull().orEmpty()
  }

  /** Remember the run a recording shows. Null forgets it, as when the server no longer has a run. */
  fun select(accountKey: String, key: String, analysisId: String?) {
    if (accountKey.isBlank() || key.isBlank()) return
    synchronized(lock) {
      val document = read() ?: return
      val cached = document.accounts[accountKey] ?: return
      val selected =
          if (analysisId.isNullOrBlank()) cached.selected - key
          else cached.selected + (key to analysisId)
      if (selected == cached.selected) return
      write(document.with(accountKey, cached.copy(selected = selected)))
    }
  }

  fun save(accountKey: String, recordings: RecordingSummaryListDto) {
    if (accountKey.isBlank()) return
    synchronized(lock) {
      val document = read() ?: StoredHistories()
      val previous = document.accounts[accountKey] ?: CachedRemoteHistory()
      write(document.with(accountKey, previous.copy(recordings = recordings)))
    }
  }

  /**
   * The server no longer has this recording for this account: it leaves the saved list. With
   * [keepOut] every list fetched afterwards drops it too, which is what a completed delete does; a
   * recording that is still on the phone can be uploaded again, so it is not kept out.
   */
  fun remove(accountKey: String, recordingId: String, keepOut: Boolean = true) {
    if (accountKey.isBlank() || recordingId.isBlank()) return
    synchronized(lock) {
      if (keepOut) {
        removedByAccount.getOrPut(accountKey) { mutableSetOf() } += recordingId
        if (accountKey == active()) _removed.update { it + recordingId }
      }
      val document = read() ?: return
      val cached = document.accounts[accountKey] ?: return
      val items = cached.recordings.withoutRecordings(setOf(recordingId))
      val selected = cached.selected - recordingId - remoteKey(recordingId)
      if (items == cached.recordings && selected == cached.selected) return
      write(document.with(accountKey, cached.copy(recordings = items, selected = selected)))
    }
  }

  fun clear() {
    file.delete()
  }

  /**
   * An older version kept only one account's list, keyed by the address and the email. The first time
   * this version sees that session, the saved list moves to the account key it now uses.
   */
  fun adopt(oldKey: String, accountKey: String) {
    if (oldKey.isBlank() || accountKey.isBlank() || oldKey == accountKey) return
    synchronized(lock) {
      val document = read() ?: return
      if (accountKey in document.accounts) return
      val cached = document.accounts[oldKey] ?: return
      write(document.without(oldKey).with(accountKey, cached))
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

  /** The saved document, or null when there is none or it cannot be read. */
  private fun read(): StoredHistories? {
    if (!file.isFile) return null
    return runCatching {
          val element = json.parseToJsonElement(file.readText())
          if ((element as? JsonObject)?.containsKey(KEY_ACCOUNTS) == true) {
            json.decodeFromJsonElement<StoredHistories>(element)
          } else {
            val legacy = json.decodeFromJsonElement<LegacyCachedRemoteHistory>(element)
            val cached = CachedRemoteHistory(legacy.recordings, legacy.selected)
            StoredHistories(mapOf(legacy.accountKey to cached))
          }
        }
        .getOrNull()
  }

  private fun write(document: StoredHistories) {
    runCatching {
          file.parentFile?.mkdirs()
          val tmp = File(file.parentFile, "${file.name}.tmp")
          tmp.writeText(json.encodeToString(document))
          Files.move(
              tmp.toPath(),
              file.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
          )
        }
  }

  companion object {
    private const val KEY_ACCOUNTS = "accounts"
    private const val UNSCOPED = "unscoped"

    fun of(context: Context): RemoteHistoryCache = context.appContainer.remoteHistoryCache
  }
}

/** The document of every account's list; it ports directly to a table keyed by account. */
@Serializable
private data class StoredHistories(val accounts: Map<String, CachedRemoteHistory> = emptyMap())

/** The single-account document an older version wrote. */
@Serializable
private data class LegacyCachedRemoteHistory(
    val accountKey: String = "",
    val recordings: RecordingSummaryListDto = RecordingSummaryListDto(),
    val selected: Map<String, String> = emptyMap(),
)

private fun StoredHistories.with(accountKey: String, cached: CachedRemoteHistory): StoredHistories =
    copy(accounts = accounts + (accountKey to cached))

private fun StoredHistories.without(accountKey: String): StoredHistories =
    copy(accounts = accounts - accountKey)

/** The list without the given recordings, matched by recording id. */
fun RecordingSummaryListDto.withoutRecordings(recordingIds: Set<String>): RecordingSummaryListDto =
    if (recordingIds.isEmpty()) this
    else RecordingSummaryListDto(items.filterNot { it.recording.id in recordingIds })
