package com.rbmr.taigasleep.upload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingDelete(
    val remoteId: String,
    val requestedAtMs: Long,
)

/**
 * Remote deletes that could not be completed yet. The local recording and its results are already
 * gone; this only keeps the remote session id until the server can be reached.
 */
class PendingDeleteStore private constructor(private val prefs: SharedPreferences) {
  private val lock = Any()
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  private val _pending = MutableStateFlow(load())
  val pending: StateFlow<Map<String, PendingDelete>> = _pending.asStateFlow()

  fun add(remoteId: String) {
    if (remoteId.isBlank()) return
    update { it + (remoteId to PendingDelete(remoteId, System.currentTimeMillis())) }
  }

  fun remove(remoteId: String) {
    update { it - remoteId }
  }

  fun remoteIds(): Set<String> = _pending.value.keys

  private fun update(f: (Map<String, PendingDelete>) -> Map<String, PendingDelete>) {
    synchronized(lock) {
      val next = f(_pending.value)
      prefs.edit().putString(KEY, json.encodeToString<Map<String, PendingDelete>>(next)).apply()
      _pending.value = next
    }
  }

  private fun load(): Map<String, PendingDelete> {
    val raw = prefs.getString(KEY, null) ?: return emptyMap()
    return runCatching { json.decodeFromString<Map<String, PendingDelete>>(raw) }
        .getOrDefault(emptyMap())
  }

  companion object {
    private const val KEY = "pending_remote_deletes"

    @Volatile private var instance: PendingDeleteStore? = null

    fun of(context: Context): PendingDeleteStore =
        instance
            ?: synchronized(this) {
              instance
                  ?: PendingDeleteStore(
                          context.applicationContext.getSharedPreferences(
                              "deletes",
                              Context.MODE_PRIVATE,
                          )
                      )
                      .also { instance = it }
            }
  }
}
