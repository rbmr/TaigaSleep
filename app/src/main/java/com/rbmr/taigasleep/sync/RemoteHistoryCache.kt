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

@Serializable
private data class CachedRemoteHistory(
    val accountKey: String = "",
    val recordings: RecordingSummaryListDto = RecordingSummaryListDto(),
    /** The run each recording shows, by recording key. */
    val selected: Map<String, String> = emptyMap(),
)

/**
 * The last remote recording list per account, so History can still show remote-only recordings when the
 * server is temporarily unreachable. It is replaced on every successful fetch and never shown for
 * a different account. It also remembers which run each recording shows, so a chosen analysis survives
 * a process death without being a fact about the analysis itself.
 *
 * A recording whose server copy is gone is removed for good: [removed] remembers every id a
 * completed delete or a missing recording took out of the list, so a fetch that started earlier
 * cannot put it back.
 */
class RemoteHistoryCache(private val file: File) {
  private val _removed = MutableStateFlow<Set<String>>(emptySet())

  /** Recording ids that must not return to the list for the rest of this process. */
  val removed: StateFlow<Set<String>> = _removed.asStateFlow()

  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  fun load(accountKey: String): RecordingSummaryListDto? {
    if (accountKey.isBlank() || !file.isFile) return null
    return runCatching {
          read()?.takeIf { it.accountKey == accountKey }?.recordings
        }
        .getOrNull()
  }

  /**
   * The run each recording shows, by recording key, for the account that cached the list. Empty for
   * another account or before the list was ever fetched.
   */
  fun selected(accountKey: String): Map<String, String> {
    if (accountKey.isBlank() || !file.isFile) return emptyMap()
    return runCatching { read()?.takeIf { it.accountKey == accountKey }?.selected.orEmpty() }
        .getOrDefault(emptyMap())
  }

  /** Remember the run a recording shows. Null forgets it, as when the server no longer has a run. */
  fun select(accountKey: String, key: String, analysisId: String?) {
    if (accountKey.isBlank() || key.isBlank()) return
    val cached = runCatching { read() }.getOrNull() ?: return
    if (cached.accountKey != accountKey) return
    val selected =
        if (analysisId.isNullOrBlank()) cached.selected - key
        else cached.selected + (key to analysisId)
    if (selected == cached.selected) return
    write(cached.copy(selected = selected))
  }

  fun save(accountKey: String, recordings: RecordingSummaryListDto) {
    if (accountKey.isBlank()) return
    write(CachedRemoteHistory(accountKey, recordings, selected(accountKey)))
  }

  /**
   * The server no longer has this recording for this account: it leaves the saved list. With
   * [keepOut] every list fetched afterwards drops it too, which is what a completed delete does; a
   * recording that is still on the phone can be uploaded again, so it is not kept out.
   */
  fun remove(accountKey: String, recordingId: String, keepOut: Boolean = true) {
    if (accountKey.isBlank() || recordingId.isBlank()) return
    if (keepOut) _removed.update { it + recordingId }
    val cached = runCatching { read() }.getOrNull()?.takeIf { it.accountKey == accountKey } ?: return
    val items = cached.recordings.withoutRecordings(setOf(recordingId))
    val selected = cached.selected - recordingId - remoteKey(recordingId)
    if (items == cached.recordings && selected == cached.selected) return
    write(cached.copy(recordings = items, selected = selected))
  }

  fun clear() {
    file.delete()
  }

  /** The cached list belongs to one account; the removals of a previous one do not apply to it. */
  fun resetRemoved() {
    _removed.value = emptySet()
  }

  /** The saved document, or null when there is none or it cannot be read. */
  private fun read(): CachedRemoteHistory? {
    if (!file.isFile) return null
    return runCatching { json.decodeFromString<CachedRemoteHistory>(file.readText()) }.getOrNull()
  }

  private fun write(cached: CachedRemoteHistory) {
    runCatching {
          file.parentFile?.mkdirs()
          val tmp = File(file.parentFile, "${file.name}.tmp")
          tmp.writeText(json.encodeToString(cached))
          Files.move(
              tmp.toPath(),
              file.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
          )
        }
  }

  companion object {
    fun of(context: Context): RemoteHistoryCache = context.appContainer.remoteHistoryCache
  }
}

/** The list without the given recordings, matched by recording id. */
fun RecordingSummaryListDto.withoutRecordings(recordingIds: Set<String>): RecordingSummaryListDto =
    if (recordingIds.isEmpty()) this
    else RecordingSummaryListDto(items.filterNot { it.recording.id in recordingIds })
