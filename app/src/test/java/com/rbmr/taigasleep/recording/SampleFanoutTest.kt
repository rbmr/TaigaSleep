package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.sensor.AccBatch
import com.rbmr.taigasleep.sensor.EcgBatch
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.Stamp
import com.rbmr.taigasleep.storage.FrameLog
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleFanoutTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun meta() =
      SessionMeta(
          sessionId = "20261024_031138",
          state = SessionState.RECORDING,
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
          config = SessionConfig(ecg = true, acc = true),
      )

  private fun frames(file: File): Int {
    var count = 0
    FrameLog.scan(file) { count++ }
    return count
  }

  @Test
  fun bothReceiveEverySampleAndTheSameInstances() {
    val writer = SessionWriter.create(tmp.newFolder("rec"), meta(), clock)
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

    fanout.onHr(stamp, hr)
    fanout.onEcg(stamp, ecg)
    fanout.onAcc(stamp, acc)
    writer.finish(SessionState.STOPPED, "done")

    assertEquals(listOf(hr, ecg, acc), seen)
    assertSame(hr, seen[0])
    assertSame(ecg, seen[1])
    assertSame(acc, seen[2])
    assertSame(stamp, seenStamp)
    assertEquals(1, frames(File(writer.dir, "hr.log")))
    assertEquals(1, frames(File(writer.dir, "ecg.log")))
    assertEquals(1, frames(File(writer.dir, "acc.log")))
  }

  @Test
  fun aMonitorFailureIsReportedOnceAndNeverStopsTheWriter() {
    val writer = SessionWriter.create(tmp.newFolder("rec"), meta(), clock)
    val failures = mutableListOf<String>()
    val monitor =
        object : SampleMonitor {
          override fun onHr(stamp: Stamp, sample: HrSample) = throw IllegalStateException("boom")

          override fun onEcg(stamp: Stamp, batch: EcgBatch) = throw IllegalStateException("boom")

          override fun onAcc(stamp: Stamp, batch: AccBatch) = throw IllegalStateException("boom")
        }
    val fanout = SampleFanout(writer, monitor) { failures += it }
    val stamp = Stamp.now(clock)
    fanout.onHr(stamp, HrSample(60, true, true, true, intArrayOf(1000)))
    fanout.onHr(stamp, HrSample(61, true, true, true, intArrayOf(1000)))
    fanout.onEcg(stamp, EcgBatch(LongArray(3) { it * 7_692_307L }, intArrayOf(1, -2, 3)))
    fanout.onAcc(stamp, AccBatch(LongArray(2) { it * 20_000_000L }, intArrayOf(1, 2, 3, 4, 5, 6)))
    writer.finish(SessionState.STOPPED, "done")

    assertEquals(1, failures.size)
    assertEquals(true, failures.single().contains("boom"))
    assertEquals(2, frames(File(writer.dir, "hr.log")))
    assertEquals(1, frames(File(writer.dir, "ecg.log")))
    assertEquals(1, frames(File(writer.dir, "acc.log")))
  }
}
