package com.rbmr.taigasleep.upload

import android.content.Context
import com.rbmr.taigasleep.remote.SessionListDto
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class CachedRemoteHistory(
    val accountKey: String = "",
    val sessions: SessionListDto = SessionListDto(),
)

/**
 * The last remote session list per account, so History can still show remote-only nights when the
 * server is temporarily unreachable. It is replaced on every successful fetch and never shown for
 * a different account.
 */
class RemoteHistoryCache(private val file: File) {
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  fun load(accountKey: String): SessionListDto? {
    if (accountKey.isBlank() || !file.isFile) return null
    return runCatching {
          val cached = json.decodeFromString<CachedRemoteHistory>(file.readText())
          cached.sessions.takeIf { cached.accountKey == accountKey }
        }
        .getOrNull()
  }

  fun save(accountKey: String, sessions: SessionListDto) {
    if (accountKey.isBlank()) return
    runCatching {
          file.parentFile?.mkdirs()
          val tmp = File(file.parentFile, "${file.name}.tmp")
          tmp.writeText(json.encodeToString(CachedRemoteHistory(accountKey, sessions)))
          Files.move(
              tmp.toPath(),
              file.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
          )
        }
  }

  fun clear() {
    file.delete()
  }

  companion object {
    fun of(context: Context): RemoteHistoryCache =
        RemoteHistoryCache(File(context.filesDir, "history/remote-sessions.json"))
  }
}
