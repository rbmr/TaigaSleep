package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.ApiCode
import com.rbmr.taigasleep.remote.RemoteFile
import com.rbmr.taigasleep.remote.TaigaClient
import com.rbmr.taigasleep.remote.TaigaConflictException
import com.rbmr.taigasleep.remote.TaigaHttpException
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Uploads one finished recording directory with byte-offset resume. The server is the source of
 * truth: every attempt starts by asking how many bytes it already has of each file, so killing the
 * worker, losing the network or force-stopping the app never restarts the transfer from zero.
 *
 * The recording is created on the server at [id], the phone's own recording id, from `meta.json`.
 * That request can repeat while the recording is incomplete, and the files follow one by one.
 *
 * With [compress] enabled the stream and event files are sent as concatenated gzip members, one
 * complete member per chunk. The server stores those members and reports compressed offsets, so
 * the local [CompressionCache] maps them back to offsets in the uncompressed recording. The
 * encoding is fixed per file by its first chunk, so a file that already holds compressed bytes
 * keeps using them even if the setting was turned off.
 *
 * This class has no Android dependency and is covered by JVM tests.
 */
class UploadEngine(
    private val client: TaigaClient,
    private val id: String,
    private val dir: File,
    private val onProgress: (Float) -> Unit = {},
    private val onCreated: () -> Unit = {},
    private val compress: Boolean = false,
    private val cacheDir: File? = null,
) {
  /** The largest chunk to send; the server's limit is not advertised, so a 413 lowers it. */
  private var chunkLimit = PREFERRED_CHUNK

  suspend fun run() {
    val meta = File(dir, META).takeIf { it.isFile }?.readText()
    if (meta.isNullOrBlank()) throw UploadException("meta.json is missing")

    val recording =
        try {
          client.putRecording(id, meta)
        } catch (e: TaigaConflictException) {
          // A complete recording cannot change: an earlier attempt already finished the upload.
          if (e.code != ApiCode.RECORDING_COMPLETE) throw e
          client.getRecording(id)
        }
    onCreated()

    if (!recording.complete) {
      val localFiles = recordingFiles()
      val totalBytes = localFiles.sumOf { it.length() }
      if (totalBytes <= 0) throw UploadException("the recording has no stream files")
      val uploaded = mutableMapOf<String, Long>()
      val cache by lazy {
        val root = cacheDir ?: throw UploadException("compressed uploads need a cache directory")
        CompressionCache(root)
      }
      for (file in localFiles) {
        val stored = client.headFile(id, file.name)
        if (useCompression(stored)) {
          uploadCompressed(file, stored, cache, uploaded, totalBytes)
        } else {
          uploadPlain(file, stored, uploaded, totalBytes)
        }
      }

      if (!client.completeRecording(id).complete) {
        throw UploadException("the server did not finish the upload")
      }
    }
    cacheDir?.deleteRecursively()
    onProgress(1f)
  }

  /**
   * A file that already has bytes keeps the encoding of its first chunk. A new file is compressed
   * only when the setting allows it.
   */
  private fun useCompression(stored: RemoteFile?): Boolean =
      if (stored == null) compress else stored.encoding == ENCODING_GZIP

  private suspend fun uploadPlain(
      file: File,
      initial: RemoteFile?,
      uploaded: MutableMap<String, Long>,
      totalBytes: Long,
  ) {
    var conflicts = 0
    val localLength = file.length()
    var serverLength = initial?.size ?: 0L
    uploaded[file.name] = serverLength
    while (true) {
      if (serverLength > localLength) {
        throw UploadException(
            "${file.name}: the server has $serverLength bytes but the phone only has $localLength"
        )
      }
      if (serverLength == localLength) return

      val chunkSize = min(chunkLimit.toLong(), localLength - serverLength).toInt()
      val bytes = readChunk(file, serverLength, chunkSize)
      val newLength =
          try {
            client.appendFile(id, file.name, serverLength, bytes)
          } catch (e: TaigaConflictException) {
            // Another attempt moved the offset. Continue from where the server says it is, once
            // or twice, instead of failing the whole upload.
            if (e.code != ApiCode.OFFSET_MISMATCH || ++conflicts > MAX_CONFLICTS) throw e
            serverLength = e.uploadOffset ?: client.headFile(id, file.name)?.size ?: 0L
            uploaded[file.name] = serverLength
            continue
          } catch (e: TaigaHttpException) {
            if (!lowerChunkLimit(e)) throw e
            continue
          }

      val previous = serverLength
      serverLength = newLength
      uploaded[file.name] = serverLength
      onProgress(progress(uploaded, totalBytes))
      if (serverLength <= previous) {
        throw UploadException("${file.name}: the server did not accept the chunk")
      }
    }
  }

  private suspend fun uploadCompressed(
      file: File,
      initial: RemoteFile?,
      cache: CompressionCache,
      uploaded: MutableMap<String, Long>,
      totalBytes: Long,
  ) {
    val name = file.name
    val localLength = file.length()
    var conflicts = 0
    var serverLength = initial?.size ?: 0L
    var offset = resume(cache, name, localLength, serverLength)
    uploaded[name] = offset
    onProgress(progress(uploaded, totalBytes))

    while (offset < localLength) {
      val wanted = min(chunkLimit.toLong(), localLength - offset).toInt()
      val plain = readChunk(file, offset, wanted)
      var size = wanted
      var member = gzipMember(plain.copyOf(size))
      while (member.size > chunkLimit && size > MIN_CHUNK) {
        size /= 2
        member = gzipMember(plain.copyOf(size))
      }
      if (member.size > chunkLimit) {
        throw UploadException("$name: a compressed chunk exceeds the server limit")
      }

      // Record the member and its boundary before sending it, so a crash can never leave the
      // server ahead of the cache.
      cache.append(name, localLength, member, offset + size)
      val newLength =
          try {
            client.appendFile(id, name, serverLength, member, ENCODING_GZIP)
          } catch (e: TaigaConflictException) {
            if (e.code != ApiCode.OFFSET_MISMATCH || ++conflicts > MAX_CONFLICTS) throw e
            serverLength = e.uploadOffset ?: client.headFile(id, name)?.size ?: 0L
            offset = resume(cache, name, localLength, serverLength)
            uploaded[name] = offset
            onProgress(progress(uploaded, totalBytes))
            continue
          } catch (e: TaigaHttpException) {
            if (!lowerChunkLimit(e)) throw e
            // The member that was just cached was never accepted; cut the cache back to the server.
            offset = resume(cache, name, localLength, serverLength)
            continue
          }

      val previous = serverLength
      serverLength = newLength
      offset += size
      uploaded[name] = offset
      onProgress(progress(uploaded, totalBytes))
      if (serverLength <= previous) {
        throw UploadException("$name: the server did not accept the chunk")
      }
    }
  }

  /** The local offset behind the server's compressed length, or a clear failure. */
  private fun resume(cache: CompressionCache, name: String, localLength: Long, serverLength: Long) =
      cache.resume(name, localLength, serverLength)
          ?: throw UploadException(
              "$name: the compressed upload cache does not match the server. " +
                  "Delete the recording on the server and upload again."
          )

  /** Halves the chunk size after a 413 so the transfer can continue; false for any other error. */
  private fun lowerChunkLimit(e: TaigaHttpException): Boolean {
    if (e.code != ApiCode.CHUNK_TOO_LARGE || chunkLimit <= MIN_CHUNK) return false
    chunkLimit = (chunkLimit / 2).coerceAtLeast(MIN_CHUNK)
    return true
  }

  private fun recordingFiles(): List<File> =
      FILES.map { File(dir, it) }.filter { it.isFile && it.length() > 0 }

  private fun progress(uploaded: Map<String, Long>, totalBytes: Long): Float {
    if (totalBytes <= 0) return 1f
    val sent = uploaded.values.sum().coerceAtMost(totalBytes)
    return (sent.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
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

  companion object {
    private const val META = "meta.json"
    private const val ENCODING_GZIP = "gzip"
    private val FILES = listOf("hr.log", "ecg.log", "acc.log", "events.jsonl")
    private const val MAX_CONFLICTS = 2
    private const val PREFERRED_CHUNK = 2 * 1024 * 1024
    private const val MIN_CHUNK = 1024
  }
}
