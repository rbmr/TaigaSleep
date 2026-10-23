package com.rbmr.taigasleep.storage

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SessionInfo(
    val id: String,
    val dir: File,
    val state: SessionState,
    val startUtcMs: Long,
    /** End time for finished sessions; for unfinished ones the time of the last HR frame. */
    val endUtcMs: Long?,
    val sizeBytes: Long,
    val sensorName: String,
) {
  val durationMs: Long
    get() = ((endUtcMs ?: startUtcMs) - startUtcMs).coerceAtLeast(0)
}

/** Lists and manages the session directories under `<external files>/recordings/`. */
class SessionStore(val root: File) {
  init {
    root.mkdirs()
  }

  fun dir(id: String) = File(root, id)

  /** Local start time as `yyyyMMdd_HHmmss`, bumped by a second until the directory is free. */
  fun newSessionId(nowMs: Long): String {
    val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    var t = nowMs
    while (File(root, fmt.format(Date(t))).exists()) t += 1000
    return fmt.format(Date(t))
  }

  /** Newest first. Directories without a readable meta.json are skipped. */
  fun list(): List<SessionInfo> =
      (root.listFiles { f -> f.isDirectory } ?: emptyArray())
          .mapNotNull { info(it) }
          .sortedByDescending { it.startUtcMs }

  fun info(dir: File): SessionInfo? {
    val meta = SessionMeta.read(dir) ?: return null
    val end = meta.endUtcMs ?: lastFrameMs(dir)
    return SessionInfo(
        id = meta.sessionId,
        dir = dir,
        state = meta.state,
        startUtcMs = meta.startUtcMs,
        endUtcMs = end,
        sizeBytes = dir.listFiles()?.sumOf { it.length() } ?: 0,
        sensorName = meta.sensorName,
    )
  }

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
      val meta = SessionMeta.read(dir) ?: continue
      if (meta.state != SessionState.RECORDING || meta.sessionId == liveId) continue
      val end = lastFrameMs(dir) ?: meta.startUtcMs
      try {
        val now = System.currentTimeMillis() * 1_000_000L
        EventLog.append(dir, now, 0, "error", "unfinished session found on app start")
        meta.copy(state = SessionState.INTERRUPTED, endUtcMs = end).write(dir)
        changed += meta.sessionId
      } catch (_: java.io.IOException) {}
    }
    return changed
  }

  fun delete(id: String): Boolean = File(root, id).takeIf { it.isDirectory }?.deleteRecursively() ?: false

  /** Zips the session directory to [out]; the archive is never kept beyond the share. */
  fun zip(id: String, out: File) {
    val dir = File(root, id)
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
      for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
        if (!f.isFile || f.name.endsWith(".tmp")) continue
        zip.putNextEntry(ZipEntry("$id/${f.name}"))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }

  companion object {
    fun of(context: Context): SessionStore {
      val base = context.getExternalFilesDir(null) ?: context.filesDir
      return SessionStore(File(base, "recordings"))
    }
  }
}
