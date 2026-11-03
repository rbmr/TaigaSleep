package com.rbmr.taigasleep.upload

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
 * With [compress] enabled the stream and event files are sent as concatenated gzip members, one
 * complete member per chunk. The server stores those members and reports compressed offsets, so
 * the local [CompressionCache] maps them back to offsets in the uncompressed recording.
 *
 * This class has no Android dependency and is covered by JVM tests.
 */
class UploadEngine(
    private val client: TaigaClient,
    private val dir: File,
    private val onProgress: (Float) -> Unit = {},
    private val onRemoteSession: (String) -> Unit = {},
    private val compress: Boolean = false,
    private val cacheDir: File? = null,
) {
  suspend fun run(): UploadOutcome {
    val meta = File(dir, META).takeIf { it.isFile }?.readText()
    if (meta.isNullOrBlank()) throw UploadException("meta.json is missing")
    val metaRecord = SessionMeta.read(dir)

    val created = client.createSession(meta)
    val remoteId = created.session.id
    if (remoteId.isBlank()) throw UploadException("the server did not return a session id")
    onRemoteSession(remoteId)

    val manifest = created.upload
    val localFiles = recordingFiles()
    val totalBytes = localFiles.sumOf { it.length() }
    val gzip = useCompression(manifest)
    val cache =
        if (gzip) {
          CompressionCache(
              cacheDir ?: throw UploadException("compressed uploads need a cache directory")
          )
        } else {
          null
        }
    val uploaded = mutableMapOf<String, Long>()
    if (!manifest.completed && created.session.upload_status != "uploaded") {
      if (totalBytes <= 0) throw UploadException("the recording has no stream files")
      val lengths = manifest.fileLengths()
      for (file in localFiles) {
        if (gzip) {
          uploadCompressed(remoteId, file, manifest, cache!!, uploaded, totalBytes)
        } else {
          uploadPlain(remoteId, file, manifest, lengths, uploaded, totalBytes)
        }
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
    if (cache != null) cache.discard()
    onProgress(1f)

    val choice =
        selectInstalledStager(
            client,
            metaRecord?.config ?: SessionConfig(hr = true, ecg = false, acc = false),
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

  /**
   * Compression is used only when the setting allows it and the server advertises it. A session
   * that already holds compressed bytes keeps using them even if the setting was turned off,
   * because its encoding cannot change.
   */
  private fun useCompression(manifest: UploadManifestDto): Boolean =
      when (manifest.encoding) {
        ENCODING_GZIP -> true
        ENCODING_IDENTITY -> false
        else ->
            compress &&
                manifest.accepted_encodings.contains(ENCODING_GZIP) &&
                manifest.files.filterKeys { it != META }.values.all { it.length == 0L }
      }

  private suspend fun uploadPlain(
      remoteId: String,
      file: File,
      manifest: UploadManifestDto,
      lengths: MutableMap<String, Long>,
      uploaded: MutableMap<String, Long>,
      totalBytes: Long,
  ) {
    var currentManifest = manifest
    var conflicts = 0
    val localLength = file.length()
    uploaded[file.name] = lengths[file.name] ?: 0L
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
      uploaded[file.name] = response.length
      onProgress(progress(uploaded, totalBytes))
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

  private suspend fun uploadCompressed(
      remoteId: String,
      file: File,
      initialManifest: UploadManifestDto,
      cache: CompressionCache,
      uploaded: MutableMap<String, Long>,
      totalBytes: Long,
  ) {
    val name = file.name
    val localLength = file.length()
    var manifest = initialManifest
    var conflicts = 0
    var serverLength = manifest.files[name]?.length ?: 0L
    var offset =
        cache.resume(name, localLength, serverLength)
            ?: throw UploadException(
                "$name: the compressed upload cache does not match the server. " +
                    "Delete the remote session and upload again."
            )
    uploaded[name] = offset
    onProgress(progress(uploaded, totalBytes))

    while (offset < localLength) {
      val wanted = min(plainChunkLimit(manifest).toLong(), localLength - offset).toInt()
      val plain = readChunk(file, offset, wanted)
      var size = wanted
      var member = gzipMember(plain.copyOf(size))
      val compressedLimit = chunkLimit(manifest)
      while (member.size > compressedLimit && size > MIN_CHUNK) {
        size /= 2
        member = gzipMember(plain.copyOf(size))
      }
      if (member.size > compressedLimit) {
        throw UploadException("$name: a compressed chunk exceeds the server limit")
      }

      // Record the member and its boundary before sending it, so a crash can never leave the
      // server ahead of the cache.
      cache.append(name, localLength, member, offset + size)
      val response =
          try {
            client.appendFile(remoteId, name, serverLength, member, ENCODING_GZIP)
          } catch (e: TaigaConflictException) {
            if (++conflicts > MAX_CONFLICTS) throw e
            manifest = client.getUpload(remoteId).upload
            serverLength = manifest.files[name]?.length ?: 0L
            offset =
                cache.resume(name, localLength, serverLength)
                    ?: throw UploadException(
                        "$name: the compressed upload cache does not match the server. " +
                            "Delete the remote session and upload again."
                    )
            uploaded[name] = offset
            onProgress(progress(uploaded, totalBytes))
            continue
          }

      val previous = serverLength
      serverLength = response.length
      offset += size
      uploaded[name] = offset
      onProgress(progress(uploaded, totalBytes))
      if (response.state == "corrupt") return
      if (response.length <= previous) {
        throw UploadException(
            response.error.ifBlank { "$name: the server did not accept the chunk" }
        )
      }
    }
  }

  private fun recordingFiles(): List<File> =
      FILES.map { File(dir, it) }.filter { it.isFile && it.length() > 0 }

  private fun progress(uploaded: Map<String, Long>, totalBytes: Long): Float {
    if (totalBytes <= 0) return 1f
    val sent = uploaded.values.sum().coerceAtMost(totalBytes)
    return (sent.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
  }

  private fun chunkLimit(manifest: UploadManifestDto): Int {
    val max = manifest.limits.max_chunk_bytes.takeIf { it > 0 } ?: DEFAULT_MAX_CHUNK
    return min(max, PREFERRED_CHUNK).toInt().coerceAtLeast(1)
  }

  private fun plainChunkLimit(manifest: UploadManifestDto): Int {
    val max =
        manifest.limits.max_uncompressed_chunk_bytes.takeIf { it > 0 }
            ?: DEFAULT_MAX_UNCOMPRESSED_CHUNK
    return min(max, PREFERRED_CHUNK).toInt().coerceAtLeast(MIN_CHUNK)
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
    private const val META = "meta.json"
    private const val ENCODING_GZIP = "gzip"
    private const val ENCODING_IDENTITY = "identity"
    private val FILES = listOf("hr.log", "ecg.log", "acc.log", "events.jsonl")
    private const val MAX_CONFLICTS = 2
    private const val PREFERRED_CHUNK = 2L * 1024 * 1024
    private const val MIN_CHUNK = 1024
    private const val DEFAULT_MAX_CHUNK = 8L * 1024 * 1024
    private const val DEFAULT_MAX_UNCOMPRESSED_CHUNK = 16L * 1024 * 1024
  }
}
