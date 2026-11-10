package com.rbmr.taigasleep.recording

import android.util.Log
import com.rbmr.taigasleep.recording.store.EventLog
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.FrameLogWriter
import com.rbmr.taigasleep.recording.store.LogPosition
import com.rbmr.taigasleep.recording.store.Payloads
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.StreamId
import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.Stamp
import java.io.File
import java.io.IOException

/** Raw storage health, owned by the writer and read by the monitor. Times are elapsed-realtime ns. */
data class StorageHealth(
    val bytesWritten: Long = 0,
    val lastError: String? = null,
    /** When the last fsync succeeded, 0 before the first one. */
    val lastSyncNs: Long = 0,
    /** Frames a failed write cost so far; each was rolled back and is missing from the log. */
    val droppedFrames: Long = 0,
)

/**
 * Owns one recording directory: meta.json, events.jsonl and the stream logs. It encodes and appends
 * frames in the v1 format, fsyncs on a cadence and exposes [storageHealth]. It derives nothing:
 * contact, rates, staleness and quality belong to [RecordingMonitor].
 *
 * All methods are safe to call from any thread.
 */
class RecordingWriter
private constructor(
    val dir: File,
    meta: RecordingMeta,
    private val clock: Clock,
) {
  var meta: RecordingMeta = meta
    private set

  private val logs = HashMap<Int, FrameLogWriter>()
  private var closed = false
  private var lastSyncMs = 0L
  private var lastSyncNs = 0L
  private var errorEvents = 0

  private var failure: String? = null
  private var failing = false

  private fun open(stream: Int, name: String) {
    logs[stream] = FrameLogWriter(File(dir, name), stream)
  }

  private fun openLogs() {
    val c = meta.config
    if (c.hr) open(StreamId.HR, HR_LOG)
    if (c.ecg) open(StreamId.ECG, ECG_LOG)
    if (c.acc) open(StreamId.ACC, ACC_LOG)
  }

  @Synchronized
  fun storageHealth() =
      StorageHealth(
          bytesWritten = logs.values.sumOf { it.bytes },
          lastError = failure,
          lastSyncNs = lastSyncNs,
          droppedFrames = droppedFrames(),
      )

  /** Frames a failed write cost across all streams, so the loss is visible and not swallowed. */
  private fun droppedFrames(): Long = logs.values.sumOf { it.droppedFrames }

  @Synchronized
  fun event(type: String, message: String) {
    try {
      EventLog.append(dir, clock.phoneNs(), clock.elapsedNs(), type, message)
    } catch (e: IOException) {
      Log.w(TAG, "event write failed", e)
    }
  }

  private fun errorEvent(message: String) {
    // A saturated sensor would otherwise fill the event log with one line per batch.
    if (errorEvents < MAX_ERROR_EVENTS) {
      errorEvents++
      event("error", message)
      if (errorEvents == MAX_ERROR_EVENTS) event("error", "further errors suppressed")
    }
  }

  /** Returns false when the frame could not be written, so an importer can refuse to delete it. */
  @Synchronized
  fun writeHr(stamp: Stamp, s: HrSample): Boolean {
    if (closed) return false
    val flags = Payloads.hrFlags(s.contactSupported, s.contact, s.rrAvailable)
    val enc = Payloads.encodeHr(stamp.phoneNs, stamp.elapsedNs, s.hr, flags, s.rrMs)
    if (enc.clamped > 0) errorEvent("hr values out of range clamped (${enc.clamped})")
    return append(StreamId.HR, enc.bytes)
  }

  @Synchronized
  fun writeEcg(stamp: Stamp, b: EcgBatch): Boolean {
    if (closed) return false
    if (b.timestampsNs.isEmpty()) return true
    var ok = true
    for (from in b.timestampsNs.indices step Payloads.MAX_BATCH) {
      val to = minOf(from + Payloads.MAX_BATCH, b.timestampsNs.size)
      val enc =
          Payloads.encodeEcg(
              stamp.phoneNs,
              stamp.elapsedNs,
              b.timestampsNs.copyOfRange(from, to),
              b.voltageUv.copyOfRange(from, to),
          )
      ok = append(StreamId.ECG, enc.bytes) && ok
    }
    return ok
  }

  @Synchronized
  fun writeAcc(stamp: Stamp, b: AccBatch): Boolean {
    if (closed) return false
    if (b.timestampsNs.isEmpty()) return true
    var ok = true
    for (from in b.timestampsNs.indices step Payloads.MAX_BATCH) {
      val to = minOf(from + Payloads.MAX_BATCH, b.timestampsNs.size)
      val enc =
          Payloads.encodeAcc(
              stamp.phoneNs,
              stamp.elapsedNs,
              b.timestampsNs.copyOfRange(from, to),
              b.xyzMg.copyOfRange(from * 3, to * 3),
          )
      if (enc.clamped > 0) errorEvent("acc values outside i16 clamped (${enc.clamped})")
      ok = append(StreamId.ACC, enc.bytes) && ok
    }
    return ok
  }

  /**
   * Returns false when the frame could not be written. The log has already been set back to its last
   * good frame, so later frames are still stored; the failure and the dropped count are exposed
   * through [storageHealth] and recorded as events.
   */
  private fun append(stream: Int, payload: ByteArray): Boolean {
    if (closed) return false
    val log = logs[stream] ?: return false
    return try {
      log.append(payload)
      if (failing) {
        failing = false
        failure = null
        event("storage_ok", "writing to the phone works again (${droppedFrames()} frames dropped)")
      }
      true
    } catch (e: IOException) {
      failure = e.message ?: "write failed"
      Log.e(TAG, "frame write failed", e)
      if (!failing) {
        failing = true
        event("storage_error", "frame write failed (${droppedFrames()} frames dropped): $failure")
      }
      false
    }
  }

  /** fsyncs all logs if [SYNC_INTERVAL_MS] passed since the last time. */
  @Synchronized
  fun syncIfDue(nowMs: Long) {
    if (closed || nowMs - lastSyncMs < SYNC_INTERVAL_MS) return
    lastSyncMs = nowMs
    sync()
  }

  /** Flushes every log and returns false when the data is not safely on disk. */
  @Synchronized
  fun sync(): Boolean {
    if (closed) return false
    return try {
      logs.values.forEach { it.sync() }
      lastSyncNs = clock.elapsedNs()
      true
    } catch (e: IOException) {
      failure = e.message ?: "sync failed"
      false
    }
  }

  /**
   * The current end of every log, so an importer can remove a half-written entry again. It is a
   * position only: the frame format does not change.
   */
  @Synchronized
  fun mark(): Map<Int, LogPosition> = logs.mapValues { (_, log) -> LogPosition(log.bytes, log.frames) }

  /** Removes everything appended after [mark]. Returns false when the logs cannot be rolled back. */
  @Synchronized
  fun rollback(mark: Map<Int, LogPosition>): Boolean {
    var ok = true
    for ((stream, position) in mark) {
      val log = logs[stream] ?: continue
      try {
        log.truncate(position)
      } catch (e: IOException) {
        failure = e.message ?: "rollback failed"
        ok = false
      }
    }
    return ok
  }

  /**
   * Marks the recording as still waiting for the tracker before the fetch starts, so a process
   * kill during the fetch is found again as fetch pending after a restart.
   */
  @Synchronized
  fun markFetchPending(): Boolean {
    if (closed) return false
    val updated = meta.copy(state = RecordingPhase.FETCH_PENDING, fetchPending = true)
    if (!updated.write(dir)) {
      failure = "metadata write failed"
      Log.e(TAG, "fetch pending metadata write failed")
      return false
    }
    meta = updated
    return true
  }

  /** How many samples of each tracker entry are already on this phone. */
  @Synchronized fun importedOnDevice(): Map<String, Int> = meta.importedEntries

  /**
   * Remembers where the logs stood before one tracker entry is appended. If the process dies
   * during the import, the next fetch truncates back to this point and imports the entry once.
   */
  @Synchronized
  fun beginEntryImport(entryId: String): Boolean {
    val updated = meta.copy(importStarted = entryId, importStart = mark())
    if (!updated.write(dir)) {
      failure = "metadata write failed"
      Log.e(TAG, "import mark metadata write failed")
      return false
    }
    meta = updated
    return true
  }

  /** Rolls back a tracker entry that was being appended when the previous process died. */
  @Synchronized
  fun rollbackStartedImport(): Boolean {
    val start = meta.importStart
    if (meta.importStarted == null && start.isEmpty()) return true
    var ok = true
    for ((stream, position) in start) {
      try {
        logs[stream]?.truncate(position)
      } catch (e: IOException) {
        failure = e.message ?: "rollback failed"
        ok = false
      }
    }
    if (!ok) return false
    val updated = meta.copy(importStarted = null, importStart = emptyMap())
    if (!updated.write(dir)) {
      failure = "metadata write failed"
      Log.e(TAG, "import rollback metadata write failed")
      return false
    }
    meta = updated
    return true
  }

  /** Records the samples of one tracker entry that are safely stored. */
  @Synchronized
  fun markOnDeviceImported(entryId: String, samples: Int): Boolean {
    val updated =
        meta.copy(
            importedEntries = meta.importedEntries + (entryId to samples),
            importStarted = null,
            importStart = emptyMap(),
        )
    if (!updated.write(dir)) {
      failure = "metadata write failed"
      Log.e(TAG, "imported metadata write failed")
      return false
    }
    meta = updated
    return true
  }

  /**
   * Remembers the tracker entries whose removal is still to come, before the recording is finished,
   * so a process that dies between finishing and removing finds the removal again instead of a
   * fetch that can never succeed.
   */
  @Synchronized
  fun markTrackerRemoval(entryIds: List<String>): Boolean {
    if (closed) return false
    if (entryIds.isEmpty()) return true
    val updated =
        meta.copy(trackerRemovalPending = (meta.trackerRemovalPending + entryIds).distinct())
    if (!updated.write(dir)) {
      failure = "metadata write failed"
      Log.e(TAG, "tracker removal metadata write failed")
      return false
    }
    meta = updated
    return true
  }

  /** Frames in the HR log, which for an on-device recording are exactly the imported samples. */
  @Synchronized fun hrFrames(): Long = logs[StreamId.HR]?.frames ?: 0

  /**
   * Cuts the HR log back to its first [frames] frames. It removes samples the recording does not
   * count as imported, which are a half-written tracker entry from a process that died; importing
   * them again would store their samples twice. Returns false when the log is already shorter or
   * cannot be cut.
   */
  @Synchronized
  fun truncateHr(frames: Long): Boolean {
    val log = logs[StreamId.HR] ?: return frames == 0L
    if (frames == log.frames) return true
    if (frames > log.frames) return false
    var count = 0L
    var end = FrameLog.HEADER_SIZE
    FrameLog.scan(File(dir, HR_LOG)) { payload ->
      if (count < frames) {
        count++
        end += 8 + payload.size
      }
    }
    if (count != frames) return false
    return try {
      log.truncate(LogPosition(end, frames))
      true
    } catch (e: IOException) {
      failure = e.message ?: "truncate failed"
      Log.e(TAG, "hr log truncate failed", e)
      false
    }
  }

  /** Closes the logs without touching meta.json (the state stays whatever it was). */
  @Synchronized
  fun close() {
    if (closed) return
    closed = true
    logs.values.forEach {
      try {
        it.close()
      } catch (e: IOException) {
        Log.w(TAG, "close failed", e)
      }
    }
  }

  /**
   * Closes the logs and rewrites meta.json with the final state. Idempotent.
   *
   * A phone that cannot take the last metadata write must not take the app down at stop: the
   * recording is then remembered in memory as interrupted, and the event log gets the reason. The
   * next app start finds the directory still marked `recording` and closes it the same way.
   */
  @Synchronized
  fun finish(state: RecordingPhase, message: String) {
    if (closed) return
    val type =
        when (state) {
          RecordingPhase.FORCED_STOP -> "force_stop"
          else -> "stop"
        }
    event(type, message)
    close()
    // A retry must not move the end of a recording that already ended when the user stopped it.
    val ended =
        meta.copy(
            state = state,
            endUtcMs = meta.endUtcMs ?: clock.phoneNs() / 1_000_000L,
            fetchPending = state == RecordingPhase.FETCH_PENDING,
        )
    if (ended.write(dir)) {
      meta = ended
      return
    }
    failure = "the recording state could not be saved"
    meta = ended.copy(state = RecordingPhase.INTERRUPTED)
    Log.e(TAG, "final metadata write failed")
    event("error", "could not save the recording state: writing meta.json failed")
  }

  companion object {
    private const val TAG = "RecordingWriter"
    const val HR_LOG = "hr.log"
    const val ECG_LOG = "ecg.log"
    const val ACC_LOG = "acc.log"
    const val SYNC_INTERVAL_MS = 30_000L
    private const val MAX_ERROR_EVENTS = 50

    /** Starts a new recording directory under [root]; meta.json is written with state recording. */
    fun create(root: File, meta: RecordingMeta, clock: Clock): RecordingWriter {
      val dir = File(root, meta.recordingId)
      check(dir.mkdirs()) { "recording dir exists: ${meta.recordingId}" }
      if (!meta.write(dir)) throw IOException("could not write ${RecordingMeta.FILE_NAME}")
      return RecordingWriter(dir, meta, clock).also {
        it.openLogs()
        it.event("start", "recording started")
      }
    }

    /**
     * Reopens an unfinished recording: truncates each log to its last valid frame and flips the state
     * back to recording. Returns null if the directory has no readable meta.json or the phone will not
     * take the write, so the caller retries later instead of crashing.
     */
    fun resume(dir: File, clock: Clock, message: String): RecordingWriter? {
      val meta = RecordingMeta.read(dir) ?: return null
      val resumed = meta.copy(state = RecordingPhase.RECORDING, endUtcMs = null)
      if (!resumed.write(dir)) return null
      return RecordingWriter(dir, resumed, clock).also {
        it.openLogs()
        it.event("service_restart", message)
      }
    }

    /**
     * Reopens a fetch-pending recording without changing its state: a retry may still fail and the
     * state must survive that. The logs are truncated to their last valid frame first.
     *
     * Returns null when the recording cannot be read, or when a half-written tracker entry cannot
     * be cut back: importing on top of it would store its samples twice, so the fetch is refused
     * and the recording stays fetch pending for another attempt.
     */
    fun resumeFetch(dir: File, clock: Clock, message: String): RecordingWriter? {
      val meta = RecordingMeta.read(dir) ?: return null
      val writer = RecordingWriter(dir, meta, clock)
      writer.openLogs()
      // An entry that was half appended before the process died is written again from scratch.
      if (!writer.rollbackStartedImport()) {
        writer.event(
            "error",
            "the half-written tracker entry could not be removed, so the fetch was not started",
        )
        writer.close()
        return null
      }
      writer.event("fetch", message)
      return writer
    }
  }
}
