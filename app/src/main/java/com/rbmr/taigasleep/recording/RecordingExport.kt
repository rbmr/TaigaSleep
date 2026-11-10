package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingStore
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one recording's chosen contents into one zip.
 *
 * The stream logs are stored without compression: they are the bulk of an export and they barely
 * compress, so deflating them spent seconds of CPU for a few percent. The small text entries
 * (metadata, analysis results) are deflated, where compression does pay.
 */
object RecordingExport {
  /**
   * Writes [contents] of the recording [id] to [out]. [results] are only written when analysis
   * results are selected; nothing here reads the cache or the network.
   *
   * A file that is still being written is exported up to the size it had when the export started,
   * so the archive matches the progress it reports.
   */
  fun write(
      id: String,
      dir: File,
      out: File,
      contents: ExportContents,
      results: List<ExportResult> = emptyList(),
      onProgress: (ExportProgress) -> Unit = {},
  ) {
    val files = archiveFiles(dir, contents)
    val resultEntries =
        if (contents.results) results.map { it.name to it.json.toByteArray(Charsets.UTF_8) }
        else emptyList()
    val total = files.sumOf { it.length() } + resultEntries.sumOf { it.second.size.toLong() }
    var done = 0L
    fun advance(bytes: Long) {
      done += bytes
      onProgress(ExportProgress(done, total))
    }
    out.parentFile?.mkdirs()
    ZipOutputStream(out.outputStream().buffered(BUFFER_BYTES)).use { zip ->
      for (file in files) {
        writeFile(
            zip,
            "$id/${file.name}",
            file,
            stored = file.name in RecordingStore.RAW_FILES,
            onWritten = ::advance,
        )
      }
      for ((name, bytes) in resultEntries) {
        zip.putNextEntry(ZipEntry("$id/analysis/$name").apply { method = ZipEntry.DEFLATED })
        zip.write(bytes)
        zip.closeEntry()
        advance(bytes.size.toLong())
      }
    }
    onProgress(ExportProgress(total, total))
  }

  /**
   * The files of [dir] the selected contents ask for, in archive order. Everything that is not a raw
   * stream log counts as metadata, so a file a newer version adds is exported with the metadata, and
   * partial writes stay out.
   */
  private fun archiveFiles(dir: File, contents: ExportContents): List<File> =
      (dir.listFiles() ?: emptyArray())
          .filter { it.isFile && !it.name.endsWith(".tmp") }
          .filter { contents.raw || it.name !in RecordingStore.RAW_FILES }
          .filter { contents.metadata || it.name in RecordingStore.RAW_FILES }
          .sortedBy { it.name }

  private fun writeFile(
      zip: ZipOutputStream,
      name: String,
      file: File,
      stored: Boolean,
      onWritten: (Long) -> Unit,
  ) {
    val size = file.length()
    val entry = ZipEntry(name)
    if (stored) {
      entry.method = ZipEntry.STORED
      entry.size = size
      entry.compressedSize = size
      entry.crc = crc(file, size)
    } else {
      entry.method = ZipEntry.DEFLATED
    }
    zip.putNextEntry(entry)
    var remaining = size
    val buffer = ByteArray(BUFFER_BYTES)
    file.inputStream().use { input ->
      while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read <= 0) break
        zip.write(buffer, 0, read)
        remaining -= read
        onWritten(read.toLong())
      }
    }
    zip.closeEntry()
  }

  private fun crc(file: File, size: Long): Long {
    val crc = CRC32()
    var remaining = size
    val buffer = ByteArray(BUFFER_BYTES)
    file.inputStream().use { input ->
      while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read <= 0) break
        crc.update(buffer, 0, read)
        remaining -= read
      }
    }
    return crc.value
  }

  private const val BUFFER_BYTES = 64 * 1024
}
