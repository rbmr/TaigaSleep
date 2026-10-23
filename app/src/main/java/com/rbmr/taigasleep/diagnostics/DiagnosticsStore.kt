package com.rbmr.taigasleep.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Append-only JSONL files, one file per process run (`diag_<runId>.jsonl`, `diag_<runId>_002.jsonl`
 * when a run exceeds [maxFileBytes]). Every line is written with a single unbuffered write so a
 * process kill loses at most the line in flight; [sync] forces it to disk.
 *
 * Total size and age are capped by [trim], which never deletes the file being written. All methods
 * swallow I/O errors: diagnostics must never take the app down.
 */
class DiagnosticsStore(
    private val dir: File,
    private val runId: String,
    private val maxTotalBytes: Long = 20L * 1024 * 1024,
    private val maxAgeMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val maxFileBytes: Long = 4L * 1024 * 1024,
) {
  private var out: FileOutputStream? = null
  private var current: File? = null
  private var segment = 1
  private var written = 0L

  /** Number of lines dropped because the file could not be written. */
  @get:Synchronized
  var failures = 0
    private set

  @Synchronized
  fun append(line: String) {
    try {
      val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
      if (out == null) open(roll = false)
      else if (written > 0 && written + bytes.size > maxFileBytes) open(roll = true)
      out?.write(bytes)
      written += bytes.size
    } catch (_: IOException) {
      failures++
      closeQuietly()
    }
  }

  @Synchronized
  fun sync() {
    try {
      out?.fd?.sync()
    } catch (_: IOException) {}
  }

  @Synchronized
  fun close() {
    closeQuietly()
  }

  /** Name of the file currently being written, or null before the first append. */
  @Synchronized fun currentFile(): File? = current

  /** Deletes files older than the age cap, then oldest files until the size cap holds. */
  @Synchronized
  fun trim(nowMs: Long) {
    try {
      val files = listFiles().filter { it != current }
      var total = files.sumOf { it.length() } + (current?.length() ?: 0L)
      for (f in files) {
        if (nowMs - f.lastModified() > maxAgeMs || total > maxTotalBytes) {
          val size = f.length()
          if (f.delete()) total -= size
        }
      }
    } catch (_: Exception) {}
  }

  private fun listFiles(): List<File> =
      (dir.listFiles { f -> isDiagFile(f.name) } ?: emptyArray()).sortedBy { it.name }

  private fun open(roll: Boolean) {
    closeQuietly()
    dir.mkdirs()
    if (roll) segment++
    val name = if (segment == 1) "diag_$runId.jsonl" else "diag_${runId}_%03d.jsonl".format(segment)
    val file = File(dir, name)
    out = FileOutputStream(file, true)
    current = file
    written = file.length()
    // A new segment is the natural moment to enforce the caps; it is on the writer thread anyway.
    if (roll) trim(System.currentTimeMillis())
  }

  private fun closeQuietly() {
    try {
      out?.close()
    } catch (_: IOException) {}
    out = null
  }

  companion object {
    fun isDiagFile(name: String): Boolean = name.startsWith("diag_") && name.endsWith(".jsonl")

    /** Diagnostics files in [dir], oldest first (names sort chronologically). */
    fun list(dir: File): List<File> =
        (dir.listFiles { f -> isDiagFile(f.name) } ?: emptyArray()).sortedBy { it.name }
  }
}
