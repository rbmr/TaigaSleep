package com.rbmr.taigasleep.sync

import android.content.Context
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
)

/**
 * The last remote recording list per account, so History can still show remote-only nights when the
 * server is temporarily unreachable. It is replaced on every successful fetch and never shown for
 * a different account.
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
          val cached = json.decodeFromString<CachedRemoteHistory>(file.readText())
          cached.recordings.takeIf { cached.accountKey == accountKey }
        }
        .getOrNull()
  }

  fun save(accountKey: String, recordings: RecordingSummaryListDto) {
    if (accountKey.isBlank()) return
    runCatching {
          file.parentFile?.mkdirs()
          val tmp = File(file.parentFile, "${file.name}.tmp")
          tmp.writeText(json.encodeToString(CachedRemoteHistory(accountKey, recordings)))
          Files.move(
              tmp.toPath(),
              file.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
          )
        }
  }

  /**
   * The server no longer has this recording for this account: it leaves the saved list. With
   * [keepOut] every list fetched afterwards drops it too, which is what a completed delete does; a
   * night that is still on the phone can be uploaded again, so it is not kept out.
   */
  fun remove(accountKey: String, recordingId: String, keepOut: Boolean = true) {
    if (accountKey.isBlank() || recordingId.isBlank()) return
    if (keepOut) _removed.update { it + recordingId }
    val cached = load(accountKey) ?: return
    if (cached.items.none { it.recording.id == recordingId }) return
    save(accountKey, cached.withoutRecordings(setOf(recordingId)))
  }

  fun clear() {
    file.delete()
  }

  /** The cached list belongs to one account; the removals of a previous one do not apply to it. */
  fun resetRemoved() {
    _removed.value = emptySet()
  }

  companion object {
    @Volatile private var instance: RemoteHistoryCache? = null

    fun of(context: Context): RemoteHistoryCache =
        instance
            ?: synchronized(this) {
              instance
                  ?: RemoteHistoryCache(File(context.filesDir, "history/remote-recordings.json"))
                      .also {
                        // The list of an older app holds session summaries this version cannot read.
                        File(context.filesDir, "history/remote-sessions.json").delete()
                        instance = it
                      }
            }
  }
}

/** The list without the given recordings, matched by recording id. */
fun RecordingSummaryListDto.withoutRecordings(recordingIds: Set<String>): RecordingSummaryListDto =
    if (recordingIds.isEmpty()) this
    else RecordingSummaryListDto(items.filterNot { it.recording.id in recordingIds })
