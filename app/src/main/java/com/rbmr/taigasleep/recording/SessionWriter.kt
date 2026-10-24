package com.rbmr.taigasleep.recording

import android.util.Log
import com.rbmr.taigasleep.sensor.AccBatch
import com.rbmr.taigasleep.sensor.Clock
import com.rbmr.taigasleep.sensor.EcgBatch
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.Stamp
import com.rbmr.taigasleep.storage.EventLog
import com.rbmr.taigasleep.storage.FrameLogWriter
import com.rbmr.taigasleep.storage.Payloads
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.storage.StreamId
import java.io.File
import java.io.IOException

/** Raw storage health, owned by the writer and read by the monitor. Times are elapsed-realtime ns. */
data class StorageHealth(
    val bytesWritten: Long = 0,
    val lastError: String? = null,
    /** When the last fsync succeeded, 0 before the first one. */
    val lastSyncNs: Long = 0,
)

/**
 * Owns one session directory: meta.json, events.jsonl and the stream logs. It encodes and appends
 * frames in the v1 format, fsyncs on a cadence and exposes [storageHealth]. It derives nothing:
 * contact, rates, staleness and quality belong to [RecordingMonitor].
 *
 * All methods are safe to call from any thread.
 */
class SessionWriter
private constructor(
    val dir: File,
    meta: SessionMeta,
    private val clock: Clock,
) {
  var meta: SessionMeta = meta
    private set

  private val logs = HashMap<Int, FrameLogWriter>()
  private var closed = false
  private var lastSyncMs = 0L
  private var lastSyncNs = 0L
  private var errorEvents = 0

  private var failure: String? = null

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
      )

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

  @Synchronized
  fun writeHr(stamp: Stamp, s: HrSample) {
    if (closed) return
    val flags = Payloads.hrFlags(s.contactSupported, s.contact, s.rrAvailable)
    val enc = Payloads.encodeHr(stamp.phoneNs, stamp.elapsedNs, s.hr, flags, s.rrMs)
    if (enc.clamped > 0) errorEvent("hr values out of range clamped (${enc.clamped})")
    append(StreamId.HR, enc.bytes)
  }

  @Synchronized
  fun writeEcg(stamp: Stamp, b: EcgBatch) {
    if (closed || b.timestampsNs.isEmpty()) return
    for (from in b.timestampsNs.indices step Payloads.MAX_BATCH) {
      val to = minOf(from + Payloads.MAX_BATCH, b.timestampsNs.size)
      val enc =
          Payloads.encodeEcg(
              stamp.phoneNs,
              stamp.elapsedNs,
              b.timestampsNs.copyOfRange(from, to),
              b.voltageUv.copyOfRange(from, to),
          )
      append(StreamId.ECG, enc.bytes)
    }
  }

  @Synchronized
  fun writeAcc(stamp: Stamp, b: AccBatch) {
    if (closed || b.timestampsNs.isEmpty()) return
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
      append(StreamId.ACC, enc.bytes)
    }
  }

  private fun append(stream: Int, payload: ByteArray) {
    val log = logs[stream] ?: return
    try {
      log.append(payload)
    } catch (e: IOException) {
      failure = e.message ?: "write failed"
      Log.e(TAG, "frame write failed", e)
    }
  }

  /** fsyncs all logs if [SYNC_INTERVAL_MS] passed since the last time. */
  @Synchronized
  fun syncIfDue(nowMs: Long) {
    if (closed || nowMs - lastSyncMs < SYNC_INTERVAL_MS) return
    lastSyncMs = nowMs
    sync()
  }

  @Synchronized
  fun sync() {
    try {
      logs.values.forEach { it.sync() }
      lastSyncNs = clock.elapsedNs()
    } catch (e: IOException) {
      failure = e.message ?: "sync failed"
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

  /** Closes the logs and rewrites meta.json with the final state. Idempotent. */
  @Synchronized
  fun finish(state: SessionState, message: String) {
    if (closed) return
    val type =
        when (state) {
          SessionState.FORCED_STOP -> "force_stop"
          else -> "stop"
        }
    event(type, message)
    close()
    meta = meta.copy(state = state, endUtcMs = clock.phoneNs() / 1_000_000L)
    meta.write(dir)
  }

  companion object {
    private const val TAG = "SessionWriter"
    const val HR_LOG = "hr.log"
    const val ECG_LOG = "ecg.log"
    const val ACC_LOG = "acc.log"
    const val SYNC_INTERVAL_MS = 30_000L
    private const val MAX_ERROR_EVENTS = 50

    /** Starts a new session directory under [root]; meta.json is written with state recording. */
    fun create(root: File, meta: SessionMeta, clock: Clock): SessionWriter {
      val dir = File(root, meta.sessionId)
      check(dir.mkdirs()) { "session dir exists: ${meta.sessionId}" }
      meta.write(dir)
      return SessionWriter(dir, meta, clock).also {
        it.openLogs()
        it.event("start", "recording started")
      }
    }

    /**
     * Reopens an unfinished session: truncates each log to its last valid frame and flips the state
     * back to recording. Returns null if the directory has no readable meta.json.
     */
    fun resume(dir: File, clock: Clock, message: String): SessionWriter? {
      val meta = SessionMeta.read(dir) ?: return null
      val resumed = meta.copy(state = SessionState.RECORDING, endUtcMs = null)
      resumed.write(dir)
      return SessionWriter(dir, resumed, clock).also {
        it.openLogs()
        it.event("service_restart", message)
      }
    }
  }
}
