package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.sensor.ConnectionState
import com.rbmr.taigasleep.sensor.SimulatedSensor
import com.rbmr.taigasleep.sensor.StreamConfig
import com.rbmr.taigasleep.storage.FrameLog
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionState
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real [SessionWriter] and [RecordingMonitor] behind one [SampleFanout] against the
 * [SimulatedSensor] on a fake clock for two minutes, leaves the session under build/fixtures/ and,
 * if python3 is installed, has tools/read_recording.py validate the raw files. It also asserts the
 * published state: the raw bytes and the live state must describe the same run.
 */
class SimulatedRecordingTest {
  private fun meta(clock: TestClock) =
      SessionMeta(
          sessionId = "20261023_223000",
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

  @Test
  fun simulatedNightValidatesWithPythonReader() {
    val clock = TestClock()
    val root = File("build/fixtures").apply { deleteRecursively(); mkdirs() }
    val writer = SessionWriter.create(root, meta(clock), clock)
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
    writer.finish(SessionState.STOPPED, "test stop")

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
            2 -> com.rbmr.taigasleep.storage.Payloads.decodeEcg(payload).values.size
            else -> com.rbmr.taigasleep.storage.Payloads.decodeAcc(payload).values.size / 3
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
