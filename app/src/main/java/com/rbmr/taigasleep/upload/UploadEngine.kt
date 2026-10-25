package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AlgorithmListDto
import com.rbmr.taigasleep.remote.TaigaClient
import com.rbmr.taigasleep.remote.TaigaConflictException
import com.rbmr.taigasleep.remote.UploadManifestDto
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class UploadOutcome(
    val remoteId: String,
    val analysisId: String?,
    val jobId: String?,
    val warning: String?,
    val analysisStatus: String?,
)

/**
 * Uploads one finished recording directory with byte-offset resume. The server manifest is the
 * source of truth: every attempt starts by asking how many bytes it already has, so killing the
 * worker, losing the network or force-stopping the app never restarts the transfer from zero.
 *
 * This class has no Android dependency and is covered by JVM tests.
 */
class UploadEngine(
    private val client: TaigaClient,
    private val dir: File,
    private val onProgress: (Float) -> Unit = {},
    private val onRemoteSession: (String) -> Unit = {},
) {
  suspend fun run(): UploadOutcome {
    val meta = File(dir, META).takeIf { it.isFile }?.readText()
    if (meta.isNullOrBlank()) throw UploadException("meta.json is missing")
    val metaRecord = SessionMeta.read(dir)

    val created = client.createSession(meta)
    val remoteId = created.session.id
    if (remoteId.isBlank()) throw UploadException("the server did not return a session id")
    onRemoteSession(remoteId)

    var manifest = created.upload
    val localFiles = recordingFiles()
    val totalBytes = localFiles.sumOf { it.length() }
    if (!manifest.completed && created.session.upload_status != "uploaded") {
      if (totalBytes <= 0) throw UploadException("the recording has no stream files")
      val lengths = manifest.fileLengths()
      for (file in localFiles) {
        uploadFile(remoteId, file, manifest, lengths, totalBytes)
      }
    }

    val complete =
        if (manifest.completed || created.session.upload_status == "uploaded") {
          client.getUpload(remoteId)
        } else {
          client.completeUpload(remoteId)
        }
    if (!complete.upload.completed && complete.session.upload_status != "uploaded") {
      throw UploadException(
          complete.upload.upload_error.ifBlank { "the server did not finish the upload" }
      )
    }
    onProgress(1f)

    val algorithms =
        runCatching { client.getAlgorithms() }.getOrDefault(AlgorithmListDto())
    val choice =
        selectStager(
            config = metaRecord?.config ?: SessionConfig(hr = true, ecg = false, acc = false),
            algorithms = algorithms.algorithms,
        )
    val requested = client.requestAnalysis(remoteId, choice.id, choice.version)
    val analysis = requested.analyses.firstOrNull()
    return UploadOutcome(
        remoteId = remoteId,
        analysisId = analysis?.id,
        jobId = requested.jobs.firstOrNull()?.id,
        warning = complete.upload.upload_error.takeIf { it.isNotBlank() },
        analysisStatus = analysis?.status,
    )
  }

  private suspend fun uploadFile(
      remoteId: String,
      file: File,
      manifest: UploadManifestDto,
      lengths: MutableMap<String, Long>,
      totalBytes: Long,
  ) {
    var currentManifest = manifest
    var conflicts = 0
    val localLength = file.length()
    while (true) {
      val serverLength =
          lengths[file.name] ?: currentManifest.files[file.name]?.length ?: 0L
      if (serverLength > localLength) {
        throw UploadException(
            "${file.name}: the server has ${serverLength} bytes but the phone only has $localLength"
        )
      }
      if (serverLength == localLength) return

      val chunkSize = min(chunkLimit(currentManifest).toLong(), localLength - serverLength).toInt()
      val bytes = readChunk(file, serverLength, chunkSize)
      val response =
          try {
            client.appendFile(remoteId, file.name, serverLength, bytes)
          } catch (e: TaigaConflictException) {
            // Another attempt, another worker or a truncated frame moved the offset. Re-read once
            // or twice instead of failing the whole upload.
            if (++conflicts > MAX_CONFLICTS) throw e
            currentManifest = client.getUpload(remoteId).upload
            lengths.clear()
            lengths.putAll(currentManifest.fileLengths())
            continue
          }

      val previous = serverLength
      lengths[file.name] = response.length
      onProgress(progress(lengths, totalBytes))
      // A bad frame is cut back to the valid prefix. Completing with that prefix is valid; sending
      // the same bytes again would only find the same corruption.
      if (response.state == "corrupt") return
      if (response.length <= previous) {
        throw UploadException(
            response.error.ifBlank { "${file.name}: the server did not accept the chunk" }
        )
      }
    }
  }

  private fun recordingFiles(): List<File> =
      FILES.map { File(dir, it) }.filter { it.isFile && it.length() > 0 }

  private fun progress(lengths: Map<String, Long>, totalBytes: Long): Float {
    if (totalBytes <= 0) return 1f
    val uploaded = lengths.values.sum().coerceAtMost(totalBytes)
    return (uploaded.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
  }

  private fun chunkLimit(manifest: UploadManifestDto): Int {
    val max = manifest.limits.max_chunk_bytes.takeIf { it > 0 } ?: DEFAULT_MAX_CHUNK
    return min(max, PREFERRED_CHUNK).toInt().coerceAtLeast(1)
  }

  private fun readChunk(file: File, offset: Long, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    RandomAccessFile(file, "r").use { raf ->
      raf.seek(offset)
      val bytes = ByteArray(length)
      raf.readFully(bytes)
      return bytes
    }
  }

  private fun UploadManifestDto.fileLengths(): MutableMap<String, Long> =
      files.mapValues { it.value.length }.toMutableMap()

  companion object {
    const val MODEL = "baseline"
    const val MODEL_VERSION = "1.0.0"

    private const val META = "meta.json"
    private val FILES = listOf("hr.log", "ecg.log", "acc.log", "events.jsonl")
    private const val MAX_CONFLICTS = 2
    private const val PREFERRED_CHUNK = 2L * 1024 * 1024
    private const val DEFAULT_MAX_CHUNK = 8L * 1024 * 1024
  }
}
