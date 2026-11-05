package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

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
      saveFile(key, analysis)

  /** Saves one run's payload without replacing another run of the same night. */
  fun save(key: String, analysisId: String?, analysis: AnalysisDto): Boolean =
      saveFile(composite(key, analysisId), analysis)

  fun load(key: String): AnalysisDto? = loadFile(key)

  /**
   * Loads the cached payload of one run. A cache written before results were keyed per analysis
   * falls back to the night's single file, but only when it belongs to the requested run.
   */
  fun load(key: String, analysisId: String?): AnalysisDto? {
    if (analysisId.isNullOrBlank()) return load(key)
    loadFile(composite(key, analysisId))?.let {
      return it
    }
    return load(key)?.takeIf { it.id == analysisId }
  }

  fun has(key: String, analysisId: String?): Boolean {
    if (analysisId.isNullOrBlank()) return file(key).isFile
    return file(composite(key, analysisId)).isFile || load(key)?.id == analysisId
  }

  fun delete(key: String) {
    file(key).delete()
    val prefix = "${safe(key)}-"
    root.listFiles { file -> file.name.startsWith(prefix) && file.name.endsWith(".json") }
        ?.forEach { it.delete() }
  }

  private fun saveFile(key: String, analysis: AnalysisDto): Boolean =
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

  private fun loadFile(key: String): AnalysisDto? {
    val file = file(key)
    if (!file.isFile) return null
    return runCatching { decode(file.readText()) }.getOrNull()
  }

  /**
   * Reads an analysis as the API v1 shapes it. An older app cached the flat payload, where the
   * fields of the result sat beside the id; such a file is the result of a finished run.
   */
  private fun decode(text: String): AnalysisDto {
    val root = json.parseToJsonElement(text) as JsonObject
    if ("result" in root || "algorithm" in root) {
      return json.decodeFromJsonElement<AnalysisDto>(root)
    }
    val result = json.decodeFromJsonElement<AnalysisResultDto>(root)
    return AnalysisDto(
        id = root["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        recording = root["session"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        algorithm = result.model,
        version = result.model_version,
        status = "succeeded",
        result = result,
    )
  }

  private fun composite(key: String, analysisId: String?): String =
      if (analysisId.isNullOrBlank()) key else "$key-$analysisId"

  private fun file(key: String): File = File(root, "${safe(key)}.json")

  private fun safe(key: String): String =
      key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

  companion object {
    fun of(context: Context): ResultCache = ResultCache(File(context.filesDir, "analysis"))
  }
}
