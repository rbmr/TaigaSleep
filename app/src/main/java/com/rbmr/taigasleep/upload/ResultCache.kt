package com.rbmr.taigasleep.upload

import android.content.Context
import com.rbmr.taigasleep.remote.AnalysisDto
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A local copy of a finished analysis. The backend remains authoritative, but History and the
 * night detail keep working when the server is temporarily unreachable or the app is offline.
 */
class ResultCache(private val root: File) {
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  init {
    root.mkdirs()
  }

  fun save(key: String, analysis: AnalysisDto): Boolean =
      runCatching {
            val target = file(key)
            val tmp = File(root, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(analysis))
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
          }
          .isSuccess

  fun load(key: String): AnalysisDto? {
    val file = file(key)
    if (!file.isFile) return null
    return runCatching { json.decodeFromString<AnalysisDto>(file.readText()) }.getOrNull()
  }

  fun delete(key: String) {
    file(key).delete()
  }

  private fun file(key: String): File = File(root, "${safe(key)}.json")

  private fun safe(key: String): String =
      key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

  companion object {
    fun of(context: Context): ResultCache = ResultCache(File(context.filesDir, "analysis"))
  }
}
