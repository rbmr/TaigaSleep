package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.Stamp

/**
 * The service's one sample listener: it hands each sample to the writer first, then to the monitor
 * and the wake-up watch, all with the same [Stamp] and instance. Raw storage is never sacrificed to
 * monitoring: a failure there is caught and reported once, and the writer keeps its frames.
 *
 * The monitor and the watch are only called for samples, so a bug there cannot lose a frame. Events
 * and notes keep going straight to the writer's `events.jsonl`.
 */
class SampleFanout(
    private val writer: RecordingWriter,
    private val monitor: SampleMonitor,
    /** The armed wake-up window of this recording; null when the user set none. */
    private val wake: WakeWatch? = null,
    /** Called once per recording if the monitor throws; the service records it as an event. */
    private val onMonitorFailure: (String) -> Unit,
) : SampleListener {
  @Volatile private var monitorFailureReported = false

  override fun onHr(stamp: Stamp, sample: HrSample) {
    writer.writeHr(stamp, sample)
    guard { monitor.onHr(stamp, sample) }
    wake?.let { guard { it.onHr(stamp, sample) } }
  }

  override fun onEcg(stamp: Stamp, batch: EcgBatch) {
    writer.writeEcg(stamp, batch)
    guard { monitor.onEcg(stamp, batch) }
  }

  override fun onAcc(stamp: Stamp, batch: AccBatch) {
    writer.writeAcc(stamp, batch)
    guard { monitor.onAcc(stamp, batch) }
    wake?.let { guard { it.onAcc(stamp, batch) } }
  }

  override fun onError(message: String) = writer.event("error", message)

  override fun onNote(type: String, message: String) = writer.event(type, message)

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
