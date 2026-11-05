package com.rbmr.taigasleep.recording.store

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

data class Recording(
    val id: String,
    val dir: File,
    val state: RecordingPhase,
    val startUtcMs: Long,
    /** End time for finished sessions; for unfinished ones the time of the last HR frame. */
    val endUtcMs: Long?,
    val sizeBytes: Long,
    val sensorName: String,
    /** At least one raw stream file is on this phone. False after a retention cleanup. */
    val hasRaw: Boolean = true,
) {
  val durationMs: Long
    get() = ((endUtcMs ?: startUtcMs) - startUtcMs).coerceAtLeast(0)
}

/** Lists and manages the session directories under `<external files>/recordings/`. */
class RecordingStore(val root: File) {
  init {
    root.mkdirs()
  }

  fun dir(id: String) = File(root, id)

  /**
   * Local start time as `yyyyMMdd_HHmmss`, a dash and four random lowercase letters or digits,
   * for example `20261023_223000-k3f9`. The server keeps recordings under the phone's id, and ids
   * are unique across all accounts there, so the suffix keeps two phones that start in the same
   * second apart. A suffix whose directory exists is drawn again.
   */
  fun newRecordingId(nowMs: Long, random: Random = Random.Default): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
    while (true) {
      val suffix = buildString {
        repeat(SUFFIX_LENGTH) { append(SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)]) }
      }
      val id = "$stamp-$suffix"
      if (!File(root, id).exists()) return id
    }
  }

  /** Newest first. Directories without a readable meta.json are skipped. */
  fun list(): List<Recording> =
      (root.listFiles { f -> f.isDirectory } ?: emptyArray())
          .mapNotNull { info(it) }
          .sortedByDescending { it.startUtcMs }

  fun info(dir: File): Recording? {
    val meta = RecordingMeta.read(dir) ?: return null
    val end = meta.endUtcMs ?: lastFrameMs(dir)
    return Recording(
        id = meta.recordingId,
        dir = dir,
        state = meta.state,
        startUtcMs = meta.startUtcMs,
        endUtcMs = end,
        sizeBytes = dir.listFiles()?.sumOf { it.length() } ?: 0,
        sensorName = meta.sensorName,
        hasRaw = hasRaw(dir),
    )
  }

  /** True while the recorded streams are still on this phone; meta.json alone is not raw data. */
  fun hasRaw(dir: File): Boolean =
      RAW_FILES.any { name -> File(dir, name).let { it.isFile && it.length() > 0 } }

  /** Wall-clock ms of the last valid HR frame (about one per second), or null if none. */
  fun lastFrameMs(dir: File): Long? {
    val hr = File(dir, "hr.log")
    if (!hr.exists()) return null
    var last: ByteArray? = null
    FrameLog.scan(hr) { last = it }
    val payload = last ?: return null
    return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).long / 1_000_000L
  }

  /**
   * Marks every session still in state `recording` (except [liveId]) as `interrupted`: its process
   * died and nothing resumed it. Returns the ids that were changed.
   */
  fun markOrphans(liveId: String?): List<String> {
    val changed = mutableListOf<String>()
    for (dir in root.listFiles { f -> f.isDirectory } ?: emptyArray()) {
      val meta = RecordingMeta.read(dir) ?: continue
      if (meta.state != RecordingPhase.RECORDING || meta.recordingId == liveId) continue
      val end = lastFrameMs(dir) ?: meta.startUtcMs
      try {
        val now = System.currentTimeMillis() * 1_000_000L
        EventLog.append(dir, now, 0, "error", "unfinished session found on app start")
        meta.copy(state = RecordingPhase.INTERRUPTED, endUtcMs = end).write(dir)
        changed += meta.recordingId
      } catch (_: java.io.IOException) {}
    }
    return changed
  }

  fun delete(id: String): Boolean = File(root, id).takeIf { it.isDirectory }?.deleteRecursively() ?: false

  /** Zips the session directory to [out]; the archive is never kept beyond the share. */
  fun zip(id: String, out: File, onProgress: (Float) -> Unit = {}) {
    val dir = File(root, id)
    val files =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.sortedBy { it.name }
            ?: emptyList()
    val total = files.sumOf { it.length() }
    var written = 0L
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
      for (f in files) {
        zip.putNextEntry(ZipEntry("$id/${f.name}"))
        f.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            zip.write(buffer, 0, read)
            written += read
            if (total > 0) {
              onProgress((written.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f))
            }
          }
        }
        zip.closeEntry()
      }
    }
    onProgress(1f)
  }

  companion object {
    private const val SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val SUFFIX_LENGTH = 4

    /** The recorded stream files. meta.json and events.jsonl are not raw data. */
    val RAW_FILES = listOf("hr.log", "ecg.log", "acc.log")

    fun of(context: Context): RecordingStore {
      val base = context.getExternalFilesDir(null) ?: context.filesDir
      return RecordingStore(File(base, "recordings"))
    }
  }
}
