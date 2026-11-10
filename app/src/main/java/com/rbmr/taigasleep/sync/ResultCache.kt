package com.rbmr.taigasleep.sync

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
 * recording detail keep working when the server is temporarily unreachable or the app is offline.
 *
 * Results are kept per account: one account's payload is never shown for another, and it is still
 * there when the user logs back into it. Each account has its own directory under [root], which is
 * what a table keyed by account and recording key holds.
 */
class ResultCache(private val root: File) {
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }

  private val migrated = mutableSetOf<String>()

  init {
    root.mkdirs()
  }

  fun save(account: String, key: String, analysis: AnalysisDto): Boolean =
      saveFile(account, key, analysis)

  /** Saves one run's payload without replacing another run of the same recording. */
  fun save(
      account: String,
      key: String,
      analysisId: String?,
      analysis: AnalysisDto,
  ): Boolean = saveFile(account, composite(key, analysisId), analysis)

  fun load(account: String, key: String): AnalysisDto? = loadFile(account, key)

  /**
   * Loads the cached payload of one run. A cache written before results were keyed per analysis
   * falls back to the recording's single file, but only when it belongs to the requested run.
   */
  fun load(account: String, key: String, analysisId: String?): AnalysisDto? {
    if (analysisId.isNullOrBlank()) return load(account, key)
    loadFile(account, composite(key, analysisId))?.let {
      return it
    }
    return load(account, key)?.takeIf { it.id == analysisId }
  }

  fun has(account: String, key: String, analysisId: String?): Boolean {
    if (analysisId.isNullOrBlank()) return file(account, key).isFile
    return file(account, composite(key, analysisId)).isFile ||
        load(account, key)?.id == analysisId
  }

  /**
   * Every run whose full payload is on this phone for [key], one per cached file. A cache written
   * before results were keyed per analysis kept one payload per recording without the run in its name;
   * that one is included too, so a History row can still be drawn from it.
   */
  fun cached(account: String, key: String): List<AnalysisDto> {
    val dir = directory(account)
    val prefix = "${safe(key)}-"
    val runs =
        dir.listFiles { file ->
              file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".json")
            }
            .orEmpty()
            .mapNotNull { file -> runCatching { decode(file.readText()) }.getOrNull() }
            .filter { it.id.isNotBlank() }
    val single = load(account, key)?.takeIf { it.id.isNotBlank() }
    return if (single != null && runs.none { it.id == single.id }) runs + single else runs
  }

  fun delete(account: String, key: String) {
    val dir = directory(account)
    file(account, key).delete()
    val prefix = "${safe(key)}-"
    dir.listFiles { file -> file.name.startsWith(prefix) && file.name.endsWith(".json") }
        ?.forEach { it.delete() }
  }

  /**
   * Moves the payloads an older version kept directly under the root into [account]'s directory,
   * once: they were written while that account was the only one on the phone.
   */
  fun migrate(account: String) {
    if (account.isBlank()) return
    synchronized(migrated) {
      if (account in migrated) return
      migrated += account
      val leftovers =
          root.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
      if (leftovers.isEmpty()) return
      val target = File(root, safe(account))
      target.mkdirs()
      for (file in leftovers) {
        val destination = File(target, file.name)
        runCatching {
              if (destination.exists()) {
                file.delete()
              } else {
                Files.move(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
              }
            }
            .onFailure { file.delete() }
      }
    }
  }

  private fun saveFile(account: String, key: String, analysis: AnalysisDto): Boolean =
      runCatching {
            val target = file(account, key)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(analysis))
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
          }
          .isSuccess

  private fun loadFile(account: String, key: String): AnalysisDto? {
    val file = file(account, key)
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

  private fun file(account: String, key: String): File =
      File(directory(account), "${safe(key)}.json")

  /** The directory of one account; a blank account is the pre-account root, which is migrated. */
  private fun directory(account: String): File {
    if (account.isBlank()) return root
    migrate(account)
    return File(root, safe(account)).also { it.mkdirs() }
  }

  private fun safe(key: String): String =
      key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
