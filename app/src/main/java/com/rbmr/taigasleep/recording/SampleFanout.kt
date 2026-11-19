@file:Suppress("CyclomaticComplexMethod", "ReturnCount", "TooManyFunctions")

package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.diagnostics.RecordingCost
import com.rbmr.taigasleep.recording.store.RecordingStream
import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.BleHrsCodec
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.PolarCodec
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener

/**
 * The service's one frame listener. It hands each frame to the writer at once, so the tracker's
 * delivery thread never waits for disk, then decodes it for the monitor and the wake-up watch. Raw
 * storage is never sacrificed to monitoring: a failure there is caught and reported once, and the
 * writer keeps its frames.
 *
 * The monitor and the watch are only called for streams they understand, so a bug there cannot
 * lose a frame. Events and notes keep going straight to the writer's `events.jsonl`.
 */
class SampleFanout(
    private val writer: RecordingWriter,
    private val monitor: SampleMonitor,
    /** The armed wake-up window of this recording; null when the user set none or switched it off. */
    @Volatile var wake: WakeWatch? = null,
    /** Called once per recording if the monitor throws; the service records it as an event. */
    private val onMonitorFailure: (String) -> Unit,
) : TrackerListener {
  @Volatile private var monitorFailureReported = false

  override fun onFrame(frame: TrackerFrame) {
    // One frame is one notification, and its payload is exactly what the tracker sent.
    RecordingCost.ble(frame.stream, frame.payload.size.toLong())
    writer.handOff(frame)
    val stream = writer.meta.stream(frame.stream) ?: return
    when (stream.effectiveType()) {
      StreamType.HEART_RATE -> {
        val sample = decodeHr(stream, frame) ?: return
        guard { monitor.onHr(frame.stamp, sample) }
        wake?.let { guard { it.onHr(frame.stamp, sample) } }
      }
      StreamType.RR_INTERVAL -> Unit
      StreamType.ECG -> {
        val batch = decodeEcg(stream, frame) ?: return
        guard { monitor.onEcg(frame.stamp, batch) }
      }
      StreamType.ACCELEROMETER -> {
        val batch = decodeAcc(stream, frame) ?: return
        guard { monitor.onAcc(frame.stamp, batch) }
        wake?.let { guard { it.onAcc(frame.stamp, batch) } }
      }
      StreamType.PULSE_INTERVAL -> {
        val sample = decodePpi(stream, frame) ?: return
        guard { monitor.onOther(StreamType.PULSE_INTERVAL, frame.stamp, sample.rrMs.size) }
        wake?.let { guard { it.onHr(frame.stamp, sample) } }
      }
      StreamType.PPG,
      StreamType.GYROSCOPE,
      StreamType.MAGNETOMETER ->
          guard { monitor.onOther(stream.type, frame.stamp, samplesIn(frame.payload)) }
      // The microphone's frames are stored and derive nothing for the live state yet.
      StreamType.SOUND -> Unit
    }
  }

  override fun onNegotiated(stream: String, rateHz: Double?, settings: Map<String, String>) =
      writer.negotiated(stream, rateHz, settings)

  override fun onError(message: String) = writer.event("error", message)

  override fun onNote(type: String, message: String) = writer.event(type, message)

  private fun decodeHr(stream: RecordingStream, frame: TrackerFrame): HrSample? =
      when (stream.codec) {
        Codec.POLAR_HR -> {
          val hr = PolarCodec.decodeHr(frame.payload)
          HrSample(
              hr.hr,
              hr.flags and PolarCodec.FLAG_CONTACT_SUPPORTED != 0,
              hr.flags and PolarCodec.FLAG_CONTACT != 0,
              hr.flags and PolarCodec.FLAG_RR_AVAILABLE != 0,
              hr.rrMs,
          )
        }
        Codec.BLE_HRS -> {
          val m = BleHrsCodec.decode(frame.payload)
          HrSample(m.hr, m.contactSupported, m.contact, m.rrPresent, m.rrMs)
        }
        else -> null
      }

  private fun decodeEcg(stream: RecordingStream, frame: TrackerFrame): EcgBatch? =
      if (stream.codec != Codec.POLAR_ECG && stream.codec != Codec.POLAR_ECG_V2) null
      else
          PolarCodec.decodeEcg(frame.payload).let {
            if (it.t.isEmpty()) null else EcgBatch(it.t, it.values)
          }

  private fun decodeAcc(stream: RecordingStream, frame: TrackerFrame): AccBatch? =
      if (stream.codec != Codec.POLAR_ACC) null
      else
          PolarCodec.decodeAcc(frame.payload).let {
            if (it.t.isEmpty()) null else AccBatch(it.t, it.values)
          }

  /** Pulse intervals serve as beat-to-beat data for the wake window, like the R-R of a strap. */
  private fun decodePpi(stream: RecordingStream, frame: TrackerFrame): HrSample? {
    if (stream.codec != Codec.POLAR_PPI) return null
    val b = java.nio.ByteBuffer.wrap(frame.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.long
    b.long
    val n = b.short.toInt() and 0xFFFF
    if (n <= 0) return null
    var hr = 0
    val rr = ArrayList<Int>(n)
    repeat(n) {
      b.long
      rr += b.short.toInt() and 0xFFFF
      b.short
      hr = b.get().toInt() and 0xFF
      b.get()
    }
    return HrSample(
        hr,
        contactSupported = false,
        contact = false,
        rrAvailable = rr.isNotEmpty(),
        rrMs = rr.toIntArray(),
    )
  }

  /** A recording written before streams existed has only its type; the codec is then Polar's. */
  private fun RecordingStream.effectiveType(): StreamType = type

  /** The sample count of a batch payload: a u16 after the two clocks and the sample count. */
  private fun samplesIn(payload: ByteArray): Int =
      if (payload.size < 20) 0
      else java.nio.ByteBuffer.wrap(payload, 16, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

  private inline fun guard(block: () -> Unit) {
    try {
      block()
    } catch (e: Throwable) {
      if (!monitorFailureReported) {
        monitorFailureReported = true
        runCatching {
          onMonitorFailure(
              "recording monitor failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
          )
        }
      }
    }
  }
}
