package com.rbmr.taigasleep.recording.store

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.tracker.RecordingMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class Recording(
    val id: String,
    val dir: File,
    val state: RecordingPhase,
    val startUtcMs: Long,
    /** End time for finished recordings; for unfinished ones the time of the last HR frame. */
    val endUtcMs: Long?,
    val sizeBytes: Long,
    val sensorName: String,
    /** At least one raw stream file is on this phone. False after a retention cleanup. */
    val hasRaw: Boolean = true,
) {
  val durationMs: Long
    get() = ((endUtcMs ?: startUtcMs) - startUtcMs).coerceAtLeast(0)
}

/**
 * Lists and manages the recording directories under `<external files>/recordings/`.
 *
 * The queries a reconcile makes share one scan of the directory: every meta.json is read once into
 * an index, and the index is scanned again only after a recording changed. A year of nights is
 * hundreds of files, so this is the difference between five reads of each and one.
 *
 * [readMeta] exists so a test can count what one scan reads; the app uses [RecordingMeta.read].
 */
class RecordingStore(
    val root: File,
    private val readMeta: (File) -> RecordingMeta? = { dir -> RecordingMeta.read(dir) },
) {
  init {
    root.mkdirs()
  }

  /** One directory in the index: its path and the meta.json that was read for it. */
  private class Indexed(val dir: File, val meta: RecordingMeta)

  private val lock = Any()
  private var scannedVersion = -1L
  private var index: List<Indexed> = emptyList()

  fun dir(id: String) = File(root, id)

  /**
   * The recordings on disk as one scan found them. A meta.json written since the scan (or a
   * directory deleted or created) makes it stale, so the next query scans again.
   */
  private fun entries(): List<Indexed> =
      synchronized(lock) {
        val version = MetaWrites.version
        if (version != scannedVersion) {
          scannedVersion = version
          index = scan()
        }
        index
      }

  private fun scan(): List<Indexed> =
      (root.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { dir ->
        readMeta(dir)?.let { Indexed(dir, it) }
      }

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
      entries().mapNotNull { recording(it) }.sortedByDescending { it.startUtcMs }

  fun info(dir: File): Recording? = entries().firstOrNull { it.dir == dir }?.let { recording(it) }

  private fun recording(entry: Indexed): Recording {
    val meta = entry.meta
    val dir = entry.dir
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
  fun hasRaw(dir: File): Boolean = rawFiles(dir).isNotEmpty()

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
   * Marks every recording still in state `recording` (except [liveId]) as `interrupted`: its process
   * died and nothing resumed it. Returns the ids that were changed.
   */
  fun markOrphans(liveId: String?): List<String> {
    val changed = mutableListOf<String>()
    for (entry in entries()) {
      val meta = entry.meta
      if (meta.state != RecordingPhase.RECORDING || meta.recordingId == liveId) continue
      // The tracker keeps recording without the app; the recording is still going, not orphaned.
      if (meta.mode == RecordingMode.ON_DEVICE) continue
      val end = lastFrameMs(entry.dir) ?: meta.startUtcMs
      try {
        val now = System.currentTimeMillis() * 1_000_000L
        EventLog.append(entry.dir, now, 0, "error", "unfinished recording found on app start")
      } catch (_: java.io.IOException) {}
      if (meta.copy(state = RecordingPhase.INTERRUPTED, endUtcMs = end).write(entry.dir)) {
        changed += meta.recordingId
      }
    }
    return changed
  }

  /** The recording the tracker is storing on its own, if one is in progress. */
  fun onDeviceInProgress(): RecordingMeta? =
      entries()
          .map { it.meta }
          .filter { it.state == RecordingPhase.RECORDING && it.mode == RecordingMode.ON_DEVICE }
          .maxByOrNull { it.startUtcMs }

  /** The newest recording whose tracker copy was not fetched yet, if any. */
  fun fetchPending(): RecordingMeta? =
      entries()
          .map { it.meta }
          .filter { it.fetchPending }
          .maxByOrNull { it.startUtcMs }

  /** The recordings whose copy is still on the tracker, newest first. */
  fun pendingTrackerRemovals(): List<RecordingMeta> =
      entries()
          .map { it.meta }
          .filter { it.trackerRemovalPending.isNotEmpty() }
          .sortedByDescending { it.startUtcMs }

  fun delete(id: String): Boolean {
    val deleted = File(root, id).takeIf { it.isDirectory }?.deleteRecursively() ?: false
    if (deleted) MetaWrites.note()
    return deleted
  }

  companion object {
    private const val SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val SUFFIX_LENGTH = 4

    /** A stream log is raw data; meta.json and events.jsonl are not. */
    fun isRawName(name: String): Boolean = name.endsWith(".log")

    /** The recorded stream logs that are on this phone, in name order. */
    fun rawFiles(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 0 && isRawName(it.name) }
            .sortedBy { it.name }

    fun of(context: Context): RecordingStore = context.appContainer.recordingStore
  }
}

/**
 * Moves whenever a recording changed on disk: a meta.json was written or a directory was removed.
 * A [RecordingStore] scans the directory again only after the version it scanned at is behind, so
 * the reads a reconcile makes share one scan.
 */
internal object MetaWrites {
  private val count = AtomicLong()

  val version: Long
    get() = count.get()

  fun note() {
    count.incrementAndGet()
  }
}
