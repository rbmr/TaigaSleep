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

/** What the writer has actually put on disk, for the UI. Times are elapsed-realtime ns. */
data class WriterStats(
    val hrSamples: Long = 0,
    val ecgSamples: Long = 0,
    val accSamples: Long = 0,
    val lastHrNs: Long = 0,
    val lastEcgNs: Long = 0,
    val lastAccNs: Long = 0,
    val lastHrBpm: Int? = null,
    val failure: String? = null,
    // Null until the sensor says (or when it cannot tell): whether the strap touches the skin.
    val contact: Boolean? = null,
    // The ECG is mostly pinned at the sensor's limits, which is what a loose or dry strap looks like.
    val ecgPoor: Boolean = false,
)

/**
 * Owns one session directory: meta.json, events.jsonl and the stream logs. Frames are written as
 * they arrive; [syncIfDue] fsyncs on a cadence. All methods are safe to call from any thread.
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
  private var errorEvents = 0

  private var hrSamples = 0L
  private var ecgSamples = 0L
  private var accSamples = 0L
  private var lastHrNs = 0L
  private var lastEcgNs = 0L
  private var lastAccNs = 0L
  private var lastHrBpm: Int? = null
  private var failure: String? = null
  private var contact: Boolean? = null
  private var ecgPoor = false
  private val clipHistory = ArrayDeque<Pair<Int, Int>>()

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
  fun stats() =
      WriterStats(
          hrSamples, ecgSamples, accSamples, lastHrNs, lastEcgNs, lastAccNs, lastHrBpm, failure,
          contact, ecgPoor,
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
    append(StreamId.HR, enc.bytes) {
      hrSamples++
      lastHrNs = stamp.elapsedNs
      lastHrBpm = s.hr
    }
    noteContact(if (s.contactSupported) s.contact else null)
  }

  // The strap keeps reporting a heart rate it makes up from noise when it loses the skin, so the
  // contact flag is the only way to tell; log the changes where a debugger will look.
  @Synchronized
  private fun noteContact(now: Boolean?) {
    if (now == null || now == contact) return
    val before = contact
    contact = now
    if (before != null || !now) event("contact", if (now) "skin contact restored" else "no skin contact")
  }

  // Looks at the last few batches (about five seconds): a clipped ECG means no usable signal.
  @Synchronized
  private fun noteEcgQuality(voltages: IntArray) {
    clipHistory.addLast(voltages.count { kotlin.math.abs(it) >= ECG_CLIP_UV } to voltages.size)
    while (clipHistory.size > CLIP_BATCHES) clipHistory.removeFirst()
    val total = clipHistory.sumOf { it.second }
    val fraction = if (total == 0) 0.0 else clipHistory.sumOf { it.first }.toDouble() / total
    val poor = if (ecgPoor) fraction >= CLIP_RECOVER else fraction >= CLIP_POOR
    if (poor != ecgPoor) {
      ecgPoor = poor
      event("ecg_signal", if (poor) "poor signal (ECG pinned at its limits)" else "signal recovered")
    }
  }

  @Synchronized
  fun writeEcg(stamp: Stamp, b: EcgBatch) {
    if (closed || b.timestampsNs.isEmpty()) return
    noteEcgQuality(b.voltageUv)
    for (from in b.timestampsNs.indices step Payloads.MAX_BATCH) {
      val to = minOf(from + Payloads.MAX_BATCH, b.timestampsNs.size)
      val enc =
          Payloads.encodeEcg(
              stamp.phoneNs,
              stamp.elapsedNs,
              b.timestampsNs.copyOfRange(from, to),
              b.voltageUv.copyOfRange(from, to),
          )
      append(StreamId.ECG, enc.bytes) {
        ecgSamples += to - from
        lastEcgNs = stamp.elapsedNs
      }
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
      append(StreamId.ACC, enc.bytes) {
        accSamples += to - from
        lastAccNs = stamp.elapsedNs
      }
    }
  }

  private inline fun append(stream: Int, payload: ByteArray, onWritten: () -> Unit) {
    val log = logs[stream] ?: return
    try {
      log.append(payload)
      onWritten()
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
    // The H10 saturates at about +-18.4 mV when nothing is picking up a heartbeat.
    private const val ECG_CLIP_UV = 18_000
    private const val CLIP_BATCHES = 8
    private const val CLIP_POOR = 0.25
    private const val CLIP_RECOVER = 0.05
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
