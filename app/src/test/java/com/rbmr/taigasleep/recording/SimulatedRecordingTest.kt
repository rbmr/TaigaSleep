package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.sensor.AccBatch
import com.rbmr.taigasleep.sensor.ConnectionState
import com.rbmr.taigasleep.sensor.EcgBatch
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.SampleListener
import com.rbmr.taigasleep.sensor.SimulatedSensor
import com.rbmr.taigasleep.sensor.Stamp
import com.rbmr.taigasleep.sensor.StreamConfig
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
 * Runs the real [SessionWriter] against the [SimulatedSensor] on a fake clock for two minutes,
 * leaves the session under build/fixtures/ and, if python3 is installed, has tools/read_recording.py
 * validate it.
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
    val sensor =
        SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 50_000, disconnectForMs = 8_000)
    val listener =
        object : SampleListener {
          override fun onHr(stamp: Stamp, sample: HrSample) = writer.writeHr(stamp, sample)

          override fun onEcg(stamp: Stamp, batch: EcgBatch) = writer.writeEcg(stamp, batch)

          override fun onAcc(stamp: Stamp, batch: AccBatch) = writer.writeAcc(stamp, batch)

          override fun onError(message: String) = writer.event("error", message)
        }
    sensor.connect()
    sensor.startStreams(StreamConfig(ecg = true, acc = true), listener)

    var state = sensor.connection.value
    var everConnected = false
    var reconnects = 0
    repeat(1200) { // 120 s in 100 ms steps
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
      val now = sensor.connection.value
      if (now != state) {
        if (now == ConnectionState.CONNECTED) {
          writer.event(if (everConnected) "reconnect" else "connect", sensor.name)
          if (everConnected) reconnects++
          everConnected = true
        } else if (state == ConnectionState.CONNECTED) {
          writer.event("disconnect", now.name.lowercase())
        }
        state = now
      }
    }
    sensor.stopStreams()
    writer.finish(SessionState.STOPPED, "test stop")

    assertTrue("simulated sensor should have dropped and come back", reconnects >= 1)
    val stats = writer.stats()
    // 120 s minus the gaps: roughly 1 Hz HR, 130 Hz ECG, 50 Hz ACC.
    assertTrue("hr ${stats.hrSamples}", stats.hrSamples in 90..118)
    assertTrue("ecg ${stats.ecgSamples}", stats.ecgSamples in 90 * 130L..120 * 130L)
    assertTrue("acc ${stats.accSamples}", stats.accSamples in 90 * 50L..120 * 50L)

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
