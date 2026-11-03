package com.rbmr.taigasleep.upload

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One deterministic gzip member for one upload chunk.
 *
 * The API stores one complete member per appended chunk, so the member boundary is the resume
 * boundary. The header is written by hand instead of relying on [java.util.zip.GZIPOutputStream],
 * because its header and deflate output must be identical on every attempt: a resumed upload
 * recompresses the chunk that follows the server's offset, never the members already stored.
 */
internal fun gzipMember(data: ByteArray): ByteArray {
  val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
  try {
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream(data.size / 2 + 32)
    // magic, deflate, no flags, mtime 0, XFL 0, OS unknown
    out.write(
        byteArrayOf(
            0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xff.toByte(),
        )
    )
    val buffer = ByteArray(64 * 1024)
    while (!deflater.finished()) {
      val count = deflater.deflate(buffer)
      out.write(buffer, 0, count)
    }
    val crc = CRC32().apply { update(data) }.value
    writeLe32(out, crc)
    writeLe32(out, data.size.toLong() and 0xffffffffL)
    return out.toByteArray()
  } finally {
    deflater.end()
  }
}

private fun writeLe32(out: ByteArrayOutputStream, value: Long) {
  out.write((value and 0xff).toInt())
  out.write(((value shr 8) and 0xff).toInt())
  out.write(((value shr 16) and 0xff).toInt())
  out.write(((value shr 24) and 0xff).toInt())
}

@Serializable
internal data class CompressionBoundary(val compressed: Long = 0, val uncompressed: Long = 0)

@Serializable
internal data class CompressionFile(
    val localLength: Long = 0,
    val boundaries: List<CompressionBoundary> = emptyList(),
)

@Serializable
internal data class CompressionIndex(
    val version: Int = 1,
    val files: Map<String, CompressionFile> = emptyMap(),
)

/**
 * The compressed upload cache for one local session.
 *
 * The server only knows the compressed byte offset, so the phone must remember which local byte
 * offset each compressed member ends at. The cache appends every member locally before sending it,
 * records the boundary, and then sends. A crash can therefore leave the cache ahead of the server,
 * never behind it, and the next attempt cuts the cache back to the boundary the server reports.
 */
internal class CompressionCache(private val root: File) {
  private val json =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
      }
  private val indexFile = File(root, "index.json")

  fun dataFile(name: String): File = File(root, "$name.gz")

  /**
   * Return the local uncompressed offset that corresponds to [serverLength], or null when the
   * cache cannot explain the server state. The server is authoritative: the cache is cut back to
   * the matching boundary, discarding members that were never accepted.
   */
  fun resume(name: String, localLength: Long, serverLength: Long): Long? {
    var entry = loadIndex().files[name]
    if (entry != null && entry.localLength != localLength) {
      if (serverLength != 0L) return null
      entry = null
    }
    val boundaries = entry?.boundaries?.ifEmpty { listOf(CompressionBoundary()) } ?: listOf(CompressionBoundary())
    val match = boundaries.lastOrNull { it.compressed == serverLength }
    if (match == null) {
      if (serverLength != 0L) return null
      reset(name, localLength)
      return 0L
    }
    val file = dataFile(name)
    if (file.length() > match.compressed) {
      RandomAccessFile(file, "rw").use { it.setLength(match.compressed) }
    } else if (file.length() < match.compressed) {
      return null
    }
    val kept = boundaries.takeWhile { it.compressed <= match.compressed }
    if (entry == null || kept.size != boundaries.size) {
      writeFile(name, CompressionFile(localLength, kept))
    }
    return match.uncompressed
  }

  /** Append [member] to the cache and record the boundary before it is sent. */
  fun append(name: String, localLength: Long, member: ByteArray, uncompressedAfter: Long): Long {
    root.mkdirs()
    val file = dataFile(name)
    FileOutputStream(file, true).use { it.write(member) }
    val entry =
        loadIndex().files[name]?.takeIf { it.localLength == localLength }
            ?: CompressionFile(localLength, listOf(CompressionBoundary()))
    val boundaries = entry.boundaries + CompressionBoundary(file.length(), uncompressedAfter)
    writeFile(name, entry.copy(localLength = localLength, boundaries = boundaries))
    return file.length()
  }

  fun reset(name: String, localLength: Long) {
    dataFile(name).delete()
    writeFile(name, CompressionFile(localLength, listOf(CompressionBoundary())))
  }

  fun discard() {
    root.deleteRecursively()
  }

  private fun loadIndex(): CompressionIndex =
      try {
        if (indexFile.isFile) json.decodeFromString<CompressionIndex>(indexFile.readText())
        else CompressionIndex()
      } catch (_: Exception) {
        CompressionIndex()
      }

  private fun writeFile(name: String, entry: CompressionFile) {
    root.mkdirs()
    val index = loadIndex()
    val updated = index.copy(files = index.files + (name to entry))
    val tmp = File(root, "index.json.tmp")
    tmp.writeText(json.encodeToString(updated))
    if (!tmp.renameTo(indexFile)) {
      indexFile.delete()
      tmp.renameTo(indexFile)
    }
  }
}
