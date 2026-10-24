package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.sensor.AccBatch
import com.rbmr.taigasleep.sensor.EcgBatch
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.Stamp
import com.rbmr.taigasleep.storage.FrameLog
import com.rbmr.taigasleep.storage.Payloads
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.storage.SessionStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionWriterTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun meta(id: String = "20261023_223000") =
      SessionMeta(
          sessionId = id,
          state = SessionState.RECORDING,
          startUtcMs = clock.phoneNs / 1_000_000L,
          endUtcMs = null,
          timezone = "Europe/Amsterdam",
          appVersion = "0.1.0",
          appVersionCode = 1,
          phoneManufacturer = "Test",
          phoneModel = "T1",
          android = "16",
          sdk = 36,
          sensorKind = "polar-h10",
          sensorId = "069A4737",
          sensorName = "Polar H10 069A4737",
          sensorFirmware = "3.0.35",
          sensorBatteryAtStart = 87,
          config = SessionConfig(ecg = true, acc = true),
      )

  private fun stamp() = Stamp.now(clock)

  private fun events(dir: File) =
      File(dir, "events.jsonl").readLines().map { JSONObject(it) }

  @Test
  fun metaIsAtomicallyRewrittenAndRoundTrips() {
    val root = tmp.newFolder("rec")
    val w = SessionWriter.create(root, meta(), clock)
    val first = SessionMeta.read(w.dir)!!
    assertEquals(SessionState.RECORDING, first.state)
    assertNull(first.endUtcMs)
    assertEquals(87, first.sensorBatteryAtStart)
    assertTrue(File(w.dir, "meta.json").readText().contains("\"end_utc_ms\": null"))

    clock.advanceMs(60_000)
    w.finish(SessionState.STOPPED, "stopped by user")
    val done = SessionMeta.read(w.dir)!!
    assertEquals(SessionState.STOPPED, done.state)
    assertEquals(clock.phoneNs / 1_000_000L, done.endUtcMs)
    assertEquals(first.copy(state = SessionState.STOPPED, endUtcMs = done.endUtcMs), done)
    // No temp file left behind, and a second finish changes nothing.
    assertFalse(File(w.dir, "meta.json.tmp").exists())
    w.finish(SessionState.FORCED_STOP, "late")
    assertEquals(SessionState.STOPPED, SessionMeta.read(w.dir)!!.state)
  }

  @Test
  fun leftoverTempFileDoesNotAffectTheCurrentMeta() {
    val root = tmp.newFolder("rec")
    val w = SessionWriter.create(root, meta(), clock)
    File(w.dir, "meta.json.tmp").writeText("{ half written")
    w.finish(SessionState.STOPPED, "ok")
    assertEquals(SessionState.STOPPED, SessionMeta.read(w.dir)!!.state)
  }

  @Test
  fun writesAllStreamsAndEventsInOrder() {
    val root = tmp.newFolder("rec")
    val w = SessionWriter.create(root, meta(), clock)
    w.writeHr(stamp(), HrSample(60, true, true, true, intArrayOf(1000)))
    w.writeEcg(stamp(), EcgBatch(LongArray(3) { it * 7_692_307L }, intArrayOf(1, -2, 3)))
    w.writeAcc(stamp(), AccBatch(LongArray(2) { it * 20_000_000L }, intArrayOf(1, 2, 3, 4, 5, 6)))
    val health = w.storageHealth()
    w.finish(SessionState.STOPPED, "done")

    val hr = mutableListOf<ByteArray>()
    FrameLog.scan(File(w.dir, "hr.log")) { hr += it }
    assertEquals(60, Payloads.decodeHr(hr.single()).hr)
    val ecg = mutableListOf<ByteArray>()
    FrameLog.scan(File(w.dir, "ecg.log")) { ecg += it }
    assertEquals(1, ecg.size)
    val acc = mutableListOf<ByteArray>()
    FrameLog.scan(File(w.dir, "acc.log")) { acc += it }
    assertEquals(1, acc.size)
    assertEquals(listOf("start", "stop"), events(w.dir).map { it.getString("type") })
    // The writer owns storage health, not metrics: bytes of all three logs and no failure.
    assertTrue(health.bytesWritten > 3 * FrameLog.HEADER_SIZE)
    assertNull(health.lastError)
    assertEquals(0L, health.lastSyncNs)
    assertEquals(health.bytesWritten, w.storageHealth().bytesWritten)
  }

  @Test
  fun syncRecordsWhenStorageWasLastFlushed() {
    val root = tmp.newFolder("rec")
    val w = SessionWriter.create(root, meta(), clock)
    assertEquals(0L, w.storageHealth().lastSyncNs)
    clock.advanceMs(30_000)
    w.sync()
    assertEquals(clock.elapsedNs(), w.storageHealth().lastSyncNs)
  }

  @Test
  fun i16OverflowIsClampedAndLoggedAsError() {
    val root = tmp.newFolder("rec")
    val w = SessionWriter.create(root, meta(), clock)
    w.writeAcc(stamp(), AccBatch(longArrayOf(0), intArrayOf(40000, 0, -40000)))
    w.finish(SessionState.STOPPED, "done")
    val errors = events(w.dir).filter { it.getString("type") == "error" }
    assertEquals(1, errors.size)
    assertTrue(errors[0].getString("message").contains("clamped"))
    val payloads = mutableListOf<ByteArray>()
    FrameLog.scan(File(w.dir, "acc.log")) { payloads += it }
    assertEquals(listOf(32767, 0, -32768), Payloads.decodeAcc(payloads.single()).values.toList())
  }

  @Test
  fun resumeTruncatesTornTailAndKeepsAppending() {
    val root = tmp.newFolder("rec")
    val w = SessionWriter.create(root, meta(), clock)
    repeat(10) { w.writeHr(stamp(), HrSample(60 + it, true, true, false, IntArray(0))) }
    w.close() // process "dies": meta stays at recording
    val hrLog = File(w.dir, "hr.log")
    hrLog.appendBytes(byteArrayOf(30, 0, 0, 0, 1, 2, 3)) // torn frame

    assertEquals(SessionState.RECORDING, SessionMeta.read(w.dir)!!.state)
    val r = SessionWriter.resume(w.dir, clock, "service resumed session")
    assertNotNull(r)
    r!!.writeHr(stamp(), HrSample(99, true, true, false, IntArray(0)))
    r.finish(SessionState.STOPPED, "done")

    val hr = mutableListOf<ByteArray>()
    val scan = FrameLog.scan(hrLog) { hr += it }
    assertEquals(11, hr.size)
    assertFalse(scan.damagedTail)
    assertEquals(99, Payloads.decodeHr(hr.last()).hr)
    assertTrue(events(w.dir).any { it.getString("type") == "service_restart" })
  }

  @Test
  fun orphanedRecordingSessionsBecomeInterrupted() {
    val root = tmp.newFolder("rec")
    val store = SessionStore(root)
    val w = SessionWriter.create(root, meta(), clock)
    clock.advanceMs(1000)
    w.writeHr(stamp(), HrSample(60, true, true, false, IntArray(0)))
    w.close()
    val done = SessionWriter.create(root, meta("20261022_220000"), clock)
    done.finish(SessionState.STOPPED, "done")

    assertEquals(listOf("20261023_223000"), store.markOrphans(liveId = null))
    val info = store.list().first { it.id == "20261023_223000" }
    assertEquals(SessionState.INTERRUPTED, info.state)
    // The duration estimate comes from the last HR frame, about one second after the start.
    assertEquals(1000L, info.durationMs)
    assertEquals(SessionState.STOPPED, store.list().first { it.id == "20261022_220000" }.state)
  }
}
