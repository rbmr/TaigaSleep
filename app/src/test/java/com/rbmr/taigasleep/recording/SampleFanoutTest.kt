package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStream
import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.PolarCodec
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.tracker.TrackerFrame
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleFanoutTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun meta() =
      RecordingMeta(
          recordingId = "20261024_031138",
          state = RecordingPhase.RECORDING,
          startUtcMs = clock.phoneNs / 1_000_000L,
          endUtcMs = null,
          timezone = "UTC",
          appVersion = "test",
          appVersionCode = 1,
          phoneManufacturer = "JVM",
          phoneModel = "test",
          android = "0",
          sdk = 0,
          sensorKind = "simulated",
          sensorId = "SIM00001",
          sensorName = "Simulated sensor",
          sensorFirmware = null,
          sensorBatteryAtStart = 87,
          config = RecordingConfig(ecg = true, acc = true),
      )

  private fun metaWithPpi(): RecordingMeta {
    val base = meta()
    return base.copy(
        streams =
            base.streams +
                RecordingStream("ppi", StreamType.PULSE_INTERVAL, Codec.POLAR_PPI, "ppi.log")
    )
  }

  private fun frames(file: File): Int {
    var count = 0
    FrameLog.scan(file) { count++ }
    return count
  }

  @Test
  fun bothReceiveEverySampleAndTheSameInstances() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta(), clock)
    val seen = mutableListOf<Any>()
    var seenStamp: Stamp? = null
    val monitor =
        object : SampleMonitor {
          override fun onHr(stamp: Stamp, sample: HrSample) {
            seenStamp = stamp
            seen += sample
          }

          override fun onEcg(stamp: Stamp, batch: EcgBatch) {
            seenStamp = stamp
            seen += batch
          }

          override fun onAcc(stamp: Stamp, batch: AccBatch) {
            seenStamp = stamp
            seen += batch
          }
        }
    val fanout = SampleFanout(writer, monitor) { throw AssertionError("monitor should not fail") }
    val stamp = Stamp.now(clock)
    val hr = HrSample(60, true, true, true, intArrayOf(1000))
    val ecg = EcgBatch(LongArray(3) { it * 7_692_307L }, intArrayOf(1, -2, 3))
    val acc = AccBatch(LongArray(2) { it * 20_000_000L }, intArrayOf(1, 2, 3, 4, 5, 6))

    fanout.onFrame(hrFrame(stamp, hr))
    fanout.onFrame(ecgFrame(stamp, ecg))
    fanout.onFrame(accFrame(stamp, acc))
    writer.finish(RecordingPhase.STOPPED, "done")

    assertEquals(3, seen.size)
    assertEquals(60, (seen[0] as HrSample).hr)
    assertSame(stamp, seenStamp)
    assertEquals(1, frames(File(writer.dir, "hr.log")))
    assertEquals(1, frames(File(writer.dir, "ecg.log")))
    assertEquals(1, frames(File(writer.dir, "acc.log")))
  }

  @Test
  fun pulseIntervalsAreCountedUnderPpiAndNotHeartRate() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), metaWithPpi(), clock)
    var heartRateSamples = 0
    var other: Pair<StreamType, Int>? = null
    val monitor =
        object : SampleMonitor {
          override fun onHr(stamp: Stamp, sample: HrSample) {
            heartRateSamples++
          }

          override fun onEcg(stamp: Stamp, batch: EcgBatch) = Unit

          override fun onAcc(stamp: Stamp, batch: AccBatch) = Unit

          override fun onOther(type: StreamType, stamp: Stamp, samples: Int) {
            other = type to samples
          }
        }
    val fanout = SampleFanout(writer, monitor) { throw AssertionError("monitor should not fail") }
    val stamp = Stamp.now(clock)

    fanout.onFrame(ppiFrame(stamp, intArrayOf(1000, 900, 1100), intArrayOf(60, 61, 62)))
    writer.finish(RecordingPhase.STOPPED, "done")

    assertEquals(0, heartRateSamples)
    assertEquals(StreamType.PULSE_INTERVAL to 3, other)
    assertEquals(1, frames(File(writer.dir, "ppi.log")))
  }

  @Test
  fun pulseIntervalsStillFeedTheWakeWindow() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), metaWithPpi(), clock)
    val monitor =
        object : SampleMonitor {
          override fun onHr(stamp: Stamp, sample: HrSample) = Unit

          override fun onEcg(stamp: Stamp, batch: EcgBatch) = Unit

          override fun onAcc(stamp: Stamp, batch: AccBatch) = Unit
        }
    val fanout = SampleFanout(writer, monitor) { throw AssertionError("monitor should not fail") }
    val fired = mutableListOf<Long>()
    val windowStartMs = clock.phoneNs / 1_000_000L + 60_000
    fanout.wake =
        WakeWatch(
            WakeWindow(0, 60, heartRate = true),
            WakeBounds(windowStartMs, windowStartMs + 10 * 60_000L),
        ) {
          fired += it
        }

    // A minute at rest, then a sustained rise inside the window, all as pulse intervals.
    repeat(61) {
      fanout.onFrame(ppiFrame(Stamp.now(clock), intArrayOf(1000), intArrayOf(60)))
      clock.advanceMs(1_000)
    }
    repeat(61) {
      fanout.onFrame(ppiFrame(Stamp.now(clock), intArrayOf(1000), intArrayOf(72)))
      clock.advanceMs(1_000)
    }
    writer.finish(RecordingPhase.STOPPED, "done")

    assertEquals(1, fired.size)
  }

  @Test
  fun aThrowingOtherCallbackIsReportedOnceAndDoesNotEscape() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), metaWithPpi(), clock)
    val failures = mutableListOf<String>()
    val monitor =
        object : SampleMonitor {
          override fun onHr(stamp: Stamp, sample: HrSample) = Unit

          override fun onEcg(stamp: Stamp, batch: EcgBatch) = Unit

          override fun onAcc(stamp: Stamp, batch: AccBatch) = Unit

          override fun onOther(type: StreamType, stamp: Stamp, samples: Int) =
              throw IllegalStateException("boom")
        }
    val fanout = SampleFanout(writer, monitor) { failures += it }

    fanout.onFrame(ppiFrame(Stamp.now(clock), intArrayOf(1000), intArrayOf(60)))
    writer.finish(RecordingPhase.STOPPED, "done")

    assertEquals(1, failures.size)
    assertTrue(failures.single().contains("boom"))
    assertEquals(1, frames(File(writer.dir, "ppi.log")))
  }

  private fun hrFrame(stamp: Stamp, sample: HrSample): TrackerFrame {
    val enc =
        PolarCodec.encodeHr(
            stamp.phoneNs,
            stamp.elapsedNs,
            sample.hr,
            PolarCodec.hrFlags(sample.contactSupported, sample.contact, sample.rrAvailable),
            sample.rrMs,
        )
    return TrackerFrame("hr", stamp, enc.bytes, enc.clamped)
  }

  private fun ecgFrame(stamp: Stamp, batch: EcgBatch): TrackerFrame {
    val enc = PolarCodec.encodeEcg(stamp.phoneNs, stamp.elapsedNs, batch.timestampsNs, batch.voltageUv)
    return TrackerFrame("ecg", stamp, enc.bytes, enc.clamped)
  }

  private fun accFrame(stamp: Stamp, batch: AccBatch): TrackerFrame {
    val enc = PolarCodec.encodeAcc(stamp.phoneNs, stamp.elapsedNs, batch.timestampsNs, batch.xyzMg)
    return TrackerFrame("acc", stamp, enc.bytes, enc.clamped)
  }

  private fun ppiFrame(stamp: Stamp, intervalsMs: IntArray, hr: IntArray): TrackerFrame {
    val n = intervalsMs.size
    val enc =
        PolarCodec.encodePpi(
            stamp.phoneNs,
            stamp.elapsedNs,
            LongArray(n),
            intervalsMs,
            IntArray(n),
            hr,
            IntArray(n),
        )
    return TrackerFrame("ppi", stamp, enc.bytes, enc.clamped)
  }

  @Test
  fun aMonitorFailureIsReportedOnceAndNeverStopsTheWriter() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta(), clock)
    val failures = mutableListOf<String>()
    val monitor =
        object : SampleMonitor {
          override fun onHr(stamp: Stamp, sample: HrSample) = throw IllegalStateException("boom")

          override fun onEcg(stamp: Stamp, batch: EcgBatch) = throw IllegalStateException("boom")

          override fun onAcc(stamp: Stamp, batch: AccBatch) = throw IllegalStateException("boom")
        }
    val fanout = SampleFanout(writer, monitor) { failures += it }
    val stamp = Stamp.now(clock)
    fanout.onFrame(hrFrame(stamp, HrSample(60, true, true, true, intArrayOf(1000))))
    fanout.onFrame(hrFrame(stamp, HrSample(61, true, true, true, intArrayOf(1000))))
    fanout.onFrame(
        ecgFrame(stamp, EcgBatch(LongArray(3) { it * 7_692_307L }, intArrayOf(1, -2, 3)))
    )
    fanout.onFrame(
        accFrame(stamp, AccBatch(LongArray(2) { it * 20_000_000L }, intArrayOf(1, 2, 3, 4, 5, 6)))
    )
    writer.finish(RecordingPhase.STOPPED, "done")

    assertEquals(1, failures.size)
    assertEquals(true, failures.single().contains("boom"))
    assertEquals(2, frames(File(writer.dir, "hr.log")))
    assertEquals(1, frames(File(writer.dir, "ecg.log")))
    assertEquals(1, frames(File(writer.dir, "acc.log")))
  }
}
