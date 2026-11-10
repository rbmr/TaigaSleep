package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SimulatedSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real [RecordingWriter] and [RecordingMonitor] behind one [SampleFanout] against the
 * [SimulatedSensor] on a fake clock for two minutes, leaves the session under build/fixtures/ and,
 * if python3 is installed, has tools/read_recording.py validate the raw files. It also asserts the
 * published state: the raw bytes and the live state must describe the same run.
 */
class SimulatedRecordingTest {
  private fun meta(clock: TestClock) =
      RecordingMeta(
          recordingId = "20261023_223000",
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

  @Test
  fun simulatedNightValidatesWithPythonReader() {
    val clock = TestClock()
    val root = File("build/fixtures").apply { deleteRecursively(); mkdirs() }
    val writer = RecordingWriter.create(root, meta(clock), clock)
    lateinit var monitor: RecordingMonitor
    monitor =
        RecordingMonitor(
            meta = writer.meta,
            clock = clock,
            event = writer::event,
            storageHealth = writer::storageHealth,
        )
    val sensor =
        SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 50_000, disconnectForMs = 8_000)
    val fanout =
        SampleFanout(writer, monitor) { message -> writer.event("error", message) }
    sensor.connect()
    sensor.startStreams(StreamConfig(ecg = true, acc = true), fanout)
    monitor.onLink(sensor.connection.value)
    monitor.onBattery(sensor.battery.value)

    var state = sensor.connection.value
    repeat(1200) { step -> // 120 s in 100 ms steps
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
      val now = sensor.connection.value
      if (now != state) {
        monitor.onLink(now)
        state = now
      }
      if (step % 10 == 9) monitor.tick(clock.elapsedNs())
    }
    sensor.stopStreams()
    val live = monitor.state.value
    writer.finish(RecordingPhase.STOPPED, "test stop")

    // The raw side: the same frames as before this refactor.
    val hrFrames = frames(File(writer.dir, "hr.log"))
    val ecgFrames = frames(File(writer.dir, "ecg.log"))
    val accFrames = frames(File(writer.dir, "acc.log"))
    val hrSamples = samples(File(writer.dir, "hr.log"), 1)
    val ecgSamples = samples(File(writer.dir, "ecg.log"), 2)
    val accSamples = samples(File(writer.dir, "acc.log"), 3)
    assertTrue("simulated sensor should have dropped and come back", live.link.reconnects >= 1)
    // 120 s minus the gaps: roughly 1 Hz HR, 130 Hz ECG, 50 Hz ACC.
    assertTrue("hr $hrSamples", hrSamples in 90..118)
    assertTrue("ecg $ecgSamples", ecgSamples in 90 * 130L..120 * 130L)
    assertTrue("acc $accSamples", accSamples in 90 * 50L..120 * 50L)

    // The live side describes the same run: same bytes, connected, on body, measured rates.
    assertEquals(RecordingState.LinkState.CONNECTED, live.link.state)
    assertEquals(RecordingState.ContactState.ON_BODY, live.contact.state)
    assertEquals(RecordingState.OverallState.OK, live.overall.state)
    assertEquals(RecordingState.PowerState.OK, live.power.state)
    assertEquals(87, live.power.percent)
    val storage = writer.storageHealth()
    assertEquals(storage.bytesWritten, live.storage.bytesWritten)
    assertEquals(
        File(writer.dir, "hr.log").length() +
            File(writer.dir, "ecg.log").length() +
            File(writer.dir, "acc.log").length(),
        live.storage.bytesWritten,
    )
    assertTrue(hrFrames > 0 && ecgFrames > 0 && accFrames > 0)
    val hrRate = live.streams.getValue(RecordingState.StreamKind.HR).rateHz!!
    val ecgRate = live.streams.getValue(RecordingState.StreamKind.ECG).rateHz!!
    val accRate = live.streams.getValue(RecordingState.StreamKind.ACC).rateHz!!
    assertTrue("hr $hrRate", hrRate in 0.8..1.2)
    assertTrue("ecg $ecgRate", ecgRate in 125.0..135.0)
    assertTrue("acc $accRate", accRate in 47.0..53.0)

    val script = File("../tools/read_recording.py")
    assumeTrue("tools/read_recording.py not found", script.exists())
    val python = listOf("python3", "python").firstOrNull { available(it) }
    assumeTrue("python is not installed", python != null)
    val session = File(root, "20261023_223000")
    val (code, out) = run(python!!, script, session)
    println(out)
    assertEquals(out, 0, code)
    assertTrue(out, out.contains("hr.log") && out.contains("ecg.log") && out.contains("acc.log"))
    assertTrue(out, out.contains("[ok]"))
    assertTrue("reader should see the simulated drop as a gap\n$out", out.contains("gap at"))
    assertTrue(out, out.contains("reconnect="))
    assertTrue(out, out.contains("rate=130.") && out.contains("rate=50."))

    // A copy with a torn ECG tail must still read, report the damage and exit with 3.
    val torn = File(root, "torn").apply { mkdirs() }
    session.listFiles()!!.forEach { it.copyTo(File(torn, it.name)) }
    val ecg = File(torn, "ecg.log")
    java.io.RandomAccessFile(ecg, "rw").use { it.setLength(it.length() - 5) }
    val (tornCode, tornOut) = run(python, script, torn)
    println(tornOut)
    assertEquals(tornOut, 3, tornCode)
    assertTrue(tornOut, tornOut.contains("DAMAGED TAIL"))
    assertTrue(tornOut, tornOut.contains("acc.log") && tornOut.contains("hr.log"))
  }


  /**
   * A strap that keeps the link up but stops notifying is the failure the SDK cannot see: no error,
   * no disconnect, just silence. The policy restarts the streams after 30 s, and the same recording
   * writes again. The steps are performed here the way the service performs them.
   */
  @Test
  fun aConnectedStreamThatStopsDeliveringIsRecoveredAndWritesAgain() = runBlocking {
    val clock = TestClock()
    val root = File("build/fixtures-stall").apply { deleteRecursively(); mkdirs() }
    val writer = RecordingWriter.create(root, meta(clock), clock)
    val monitor =
        RecordingMonitor(
            meta = writer.meta,
            clock = clock,
            event = writer::event,
            storageHealth = writer::storageHealth,
        )
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val fanout = SampleFanout(writer, monitor) { message -> writer.event("error", message) }
    sensor.connect()
    sensor.startStreams(StreamConfig(ecg = true, acc = true), fanout)
    monitor.onPhase(RecordingState.Phase.RECORDING)
    monitor.onLink(sensor.connection.value)
    monitor.onBattery(sensor.battery.value)

    val recoveries = mutableListOf<RecoveryStep>()
    var link = sensor.connection.value
    var msSinceTick = 0L
    suspend fun run(ms: Long) {
      repeat((ms / 100).toInt()) {
        clock.advanceMs(100)
        sensor.pump(clock.elapsedNs())
        if (sensor.connection.value != link) {
          link = sensor.connection.value
          monitor.onLink(link)
        }
        msSinceTick += 100
        if (msSinceTick < 1_000) return@repeat
        msSinceTick = 0
        for (step in monitor.tick(clock.elapsedNs())) {
          recoveries += step
          executeRecovery(
              step,
              sensor,
              StreamConfig(ecg = true, acc = true),
              fanout,
              reconnectPauseMs = 0,
          )
        }
      }
    }

    // Ten seconds reach the phone, then the strap stays connected and goes silent.
    run(10_000)
    val before = samples(File(writer.dir, "hr.log"), 1)
    assertTrue("the recording should write while the streams run", before > 0)
    sensor.stopEmittingUntilRestarted()
    run(29_000)
    assertEquals(ConnectionState.CONNECTED, sensor.connection.value)
    assertEquals("nothing recovers the stream on its own", 0, recoveries.size)
    assertEquals(before, samples(File(writer.dir, "hr.log"), 1))

    // The restart the policy asks for brings the notifications back: the recording writes again.
    run(5_000)
    assertEquals(RecoveryStepKind.RESTART_STREAMS, recoveries.single().kind)
    assertEquals(ConnectionState.CONNECTED, sensor.connection.value)
    assertTrue(
        "the recording should write after the recovery",
        samples(File(writer.dir, "hr.log"), 1) > before,
    )
    val events = File(writer.dir, "events.jsonl").readLines()
    assertTrue(events.toString(), events.any { it.contains("\"recovery\"") && it.contains("30 s") })

    sensor.stopStreams()
    writer.finish(RecordingPhase.STOPPED, "test stop")
    assertEquals(RecordingState.OverallState.OK, monitor.state.value.overall.state)
  }

  @Test
  fun anOnDeviceRecordingStaysOnTheTrackerUntilItIsFetched() {
    val clock = TestClock()
    val root = File("build/fixtures-on-device").apply { deleteRecursively(); mkdirs() }
    val writer =
        RecordingWriter.create(
            root,
            meta(clock).copy(recordingId = "on-device", mode = RecordingMode.ON_DEVICE),
            clock,
        )
    val monitor =
        RecordingMonitor(
            meta = writer.meta,
            clock = clock,
            event = writer::event,
            storageHealth = writer::storageHealth,
        )
    val sensor = SimulatedSensor(clock, autoRun = false)
    val fanout =
        SampleFanout(writer, monitor) { message -> writer.event("error", message) }
    sensor.connect()
    sensor.startOnDevice(StreamConfig(ecg = false, acc = false), fanout) {}
    repeat(300) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    assertEquals(0L, frames(File(writer.dir, "hr.log")))

    val fetched = OnDeviceImport(writer, sensor, clock).fetch()
    assertTrue(fetched is OnDeviceFetch.Imported)
    sensor.removeFromDevice((fetched as OnDeviceFetch.Imported).entries.map { it.id })
    writer.finish(RecordingPhase.STOPPED, "test stop")
    assertTrue(samples(File(writer.dir, "hr.log"), 1) > 0)
  }

  private fun frames(file: File): Long {
    var frames = 0L
    FrameLog.scan(file) { frames++ }
    return frames
  }

  private fun samples(file: File, stream: Int): Long {
    var samples = 0L
    FrameLog.scan(file) { payload ->
      samples +=
          when (stream) {
            1 -> 1
            2 -> com.rbmr.taigasleep.recording.store.Payloads.decodeEcg(payload).values.size
            else -> com.rbmr.taigasleep.recording.store.Payloads.decodeAcc(payload).values.size / 3
          }
    }
    return samples
  }

  private fun run(python: String, script: File, dir: File): Pair<Int, String> {
    val proc =
        ProcessBuilder(python, "-I", script.path, dir.path).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText()
    assertTrue("reader timed out", proc.waitFor(60, TimeUnit.SECONDS))
    return proc.exitValue() to out
  }

  private fun available(cmd: String) =
      try {
        ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().waitFor() == 0
      } catch (_: Exception) {
        false
      }
}
