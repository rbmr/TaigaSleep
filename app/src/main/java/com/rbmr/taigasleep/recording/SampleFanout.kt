package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.sensor.AccBatch
import com.rbmr.taigasleep.sensor.EcgBatch
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.SampleListener
import com.rbmr.taigasleep.sensor.Stamp

/**
 * The service's one sample listener: it hands each sample to the writer first, then to the
 * monitor, both with the same [Stamp] and instance. Raw storage is never sacrificed to
 * monitoring: a monitor failure is caught and reported once, and the writer keeps its frames.
 *
 * The monitor is only called for samples, so a bug there cannot lose a frame. Events and notes
 * keep going straight to the writer's `events.jsonl`.
 */
class SampleFanout(
    private val writer: SessionWriter,
    private val monitor: SampleMonitor,
    /** Called once per session if the monitor throws; the service records it as an event. */
    private val onMonitorFailure: (String) -> Unit,
) : SampleListener {
  @Volatile private var monitorFailureReported = false

  override fun onHr(stamp: Stamp, sample: HrSample) {
    writer.writeHr(stamp, sample)
    guard { monitor.onHr(stamp, sample) }
  }

  override fun onEcg(stamp: Stamp, batch: EcgBatch) {
    writer.writeEcg(stamp, batch)
    guard { monitor.onEcg(stamp, batch) }
  }

  override fun onAcc(stamp: Stamp, batch: AccBatch) {
    writer.writeAcc(stamp, batch)
    guard { monitor.onAcc(stamp, batch) }
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
