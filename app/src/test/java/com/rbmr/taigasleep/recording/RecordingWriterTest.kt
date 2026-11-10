package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.Payloads
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.Stamp
import java.io.File
import org.json.JSONObject
import com.rbmr.taigasleep.tracker.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingWriterTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun meta(id: String = "20261023_223000") =
      RecordingMeta(
          recordingId = id,
          state = RecordingPhase.RECORDING,
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
          config = RecordingConfig(ecg = true, acc = true),
      )

  private fun stamp() = Stamp.now(clock)

  private fun events(dir: File) =
      File(dir, "events.jsonl").readLines().map { JSONObject(it) }

  @Test
  fun metaIsAtomicallyRewrittenAndRoundTrips() {
    val root = tmp.newFolder("rec")
    val w = RecordingWriter.create(root, meta(), clock)
    val first = RecordingMeta.read(w.dir)!!
    assertEquals(RecordingPhase.RECORDING, first.state)
    assertNull(first.endUtcMs)
    assertEquals(87, first.sensorBatteryAtStart)
    assertTrue(File(w.dir, "meta.json").readText().contains("\"end_utc_ms\": null"))

    clock.advanceMs(60_000)
    w.finish(RecordingPhase.STOPPED, "stopped by user")
    val done = RecordingMeta.read(w.dir)!!
    assertEquals(RecordingPhase.STOPPED, done.state)
    assertEquals(clock.phoneNs / 1_000_000L, done.endUtcMs)
    assertEquals(first.copy(state = RecordingPhase.STOPPED, endUtcMs = done.endUtcMs), done)
    // No temp file left behind, and a second finish changes nothing.
    assertFalse(File(w.dir, "meta.json.tmp").exists())
    w.finish(RecordingPhase.FORCED_STOP, "late")
    assertEquals(RecordingPhase.STOPPED, RecordingMeta.read(w.dir)!!.state)
  }

  @Test
  fun leftoverTempFileDoesNotAffectTheCurrentMeta() {
    val root = tmp.newFolder("rec")
    val w = RecordingWriter.create(root, meta(), clock)
    File(w.dir, "meta.json.tmp").writeText("{ half written")
    w.finish(RecordingPhase.STOPPED, "ok")
    assertEquals(RecordingPhase.STOPPED, RecordingMeta.read(w.dir)!!.state)
  }

  @Test
  fun aFailedFinalMetaWriteEndsTheRecordingAndRecordsWhy() {
    val root = tmp.newFolder("rec")
    val w = RecordingWriter.create(root, meta(), clock)
    w.writeHr(stamp(), HrSample(60, true, true, false, IntArray(0)))
    // The phone refuses the last write: a directory where the temporary file belongs makes the
    // atomic rewrite fail, exactly as a full disk does.
    File(w.dir, "meta.json.tmp").mkdirs()

    w.finish(RecordingPhase.STOPPED, "stopped by user")

    // Nothing escaped to the caller; in memory the recording ends as interrupted, and the log has
    // the reason. The on-disk state is still the start one, which the next app start closes.
    assertEquals(RecordingPhase.INTERRUPTED, w.meta.state)
    val errors = events(w.dir).filter { it.getString("type") == "error" }
    assertEquals(1, errors.size)
    assertTrue(errors.single().getString("message").contains("could not save the recording state"))
    assertEquals(RecordingPhase.RECORDING, RecordingMeta.read(w.dir)!!.state)
  }

  @Test
  fun writesAllStreamsAndEventsInOrder() {
    val root = tmp.newFolder("rec")
    val w = RecordingWriter.create(root, meta(), clock)
    w.writeHr(stamp(), HrSample(60, true, true, true, intArrayOf(1000)))
    w.writeEcg(stamp(), EcgBatch(LongArray(3) { it * 7_692_307L }, intArrayOf(1, -2, 3)))
    w.writeAcc(stamp(), AccBatch(LongArray(2) { it * 20_000_000L }, intArrayOf(1, 2, 3, 4, 5, 6)))
    val health = w.storageHealth()
    w.finish(RecordingPhase.STOPPED, "done")

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
    val w = RecordingWriter.create(root, meta(), clock)
    assertEquals(0L, w.storageHealth().lastSyncNs)
    clock.advanceMs(30_000)
    w.sync()
    assertEquals(clock.elapsedNs(), w.storageHealth().lastSyncNs)
  }

  @Test
  fun i16OverflowIsClampedAndLoggedAsError() {
    val root = tmp.newFolder("rec")
    val w = RecordingWriter.create(root, meta(), clock)
    w.writeAcc(stamp(), AccBatch(longArrayOf(0), intArrayOf(40000, 0, -40000)))
    w.finish(RecordingPhase.STOPPED, "done")
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
    val w = RecordingWriter.create(root, meta(), clock)
    repeat(10) { w.writeHr(stamp(), HrSample(60 + it, true, true, false, IntArray(0))) }
    w.close() // process "dies": meta stays at recording
    val hrLog = File(w.dir, "hr.log")
    hrLog.appendBytes(byteArrayOf(30, 0, 0, 0, 1, 2, 3)) // torn frame

    assertEquals(RecordingPhase.RECORDING, RecordingMeta.read(w.dir)!!.state)
    val r = RecordingWriter.resume(w.dir, clock, "service resumed session")
    assertNotNull(r)
    r!!.writeHr(stamp(), HrSample(99, true, true, false, IntArray(0)))
    r.finish(RecordingPhase.STOPPED, "done")

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
    val store = RecordingStore(root)
    val w = RecordingWriter.create(root, meta(), clock)
    clock.advanceMs(1000)
    w.writeHr(stamp(), HrSample(60, true, true, false, IntArray(0)))
    w.close()
    val done = RecordingWriter.create(root, meta("20261022_220000"), clock)
    done.finish(RecordingPhase.STOPPED, "done")

    assertEquals(listOf("20261023_223000"), store.markOrphans(liveId = null))
    val info = store.list().first { it.id == "20261023_223000" }
    assertEquals(RecordingPhase.INTERRUPTED, info.state)
    // The duration estimate comes from the last HR frame, about one second after the start.
    assertEquals(1000L, info.durationMs)
    assertEquals(RecordingPhase.STOPPED, store.list().first { it.id == "20261022_220000" }.state)
  }

  @Test
  fun onDeviceRecordingsStayInProgressWithoutTheApp() {
    val root = tmp.newFolder("rec")
    val store = RecordingStore(root)
    RecordingWriter.create(root, meta().copy(mode = RecordingMode.ON_DEVICE), clock).close()

    assertEquals(emptyList<String>(), store.markOrphans(liveId = null))
    assertEquals("20261023_223000", store.onDeviceInProgress()?.recordingId)
    assertEquals(RecordingPhase.RECORDING, store.list().first().state)
  }

  @Test
  fun fetchPendingSurvivesARestartAndClearsAfterAFetchedRetry() {
    val root = tmp.newFolder("rec")
    val store = RecordingStore(root)
    val w = RecordingWriter.create(root, meta().copy(mode = RecordingMode.ON_DEVICE), clock)
    assertTrue(w.markFetchPending())
    w.finish(RecordingPhase.FETCH_PENDING, "fetch failed")

    val pending = store.fetchPending()!!
    assertEquals("20261023_223000", pending.recordingId)
    assertEquals(RecordingPhase.FETCH_PENDING, pending.state)
    assertTrue(pending.fetchPending)
    // The app process died after some entries were imported; a retry can resume them.
    val retry = RecordingWriter.resumeFetch(w.dir, clock, "fetching again")!!
    assertEquals(RecordingPhase.FETCH_PENDING, retry.meta.state)
    assertTrue(retry.markOnDeviceImported("entry-1", 3))
    retry.finish(RecordingPhase.STOPPED, "fetched")

    assertNull(store.fetchPending())
    val done = RecordingMeta.read(w.dir)!!
    assertEquals(RecordingPhase.STOPPED, done.state)
    assertEquals(mapOf("entry-1" to 3), done.importedEntries)
  }

  @Test
  fun aHalfAppendedTrackerEntryIsRolledBackBeforeTheNextFetch() {
    val root = tmp.newFolder("rec")
    val w = RecordingWriter.create(root, meta().copy(mode = RecordingMode.ON_DEVICE), clock)
    w.markFetchPending()
    w.beginEntryImport("entry-1")
    w.writeHr(stamp(), HrSample(60, true, true, true, intArrayOf(1000)))
    w.close() // the process dies between writing the entry and marking it imported

    val resumed = RecordingWriter.resumeFetch(w.dir, clock, "fetching again")!!
    var frames = 0
    FrameLog.scan(File(w.dir, "hr.log")) { frames++ }

    assertEquals(0, frames)
    assertNull(resumed.meta.importStarted)
    assertEquals(emptyMap<String, Int>(), resumed.importedOnDevice())
  }

  @Test
  fun finishedOnDeviceRecordingsAreNotInProgress() {
    val root = tmp.newFolder("rec")
    val store = RecordingStore(root)
    RecordingWriter.create(root, meta().copy(mode = RecordingMode.ON_DEVICE), clock)
        .finish(RecordingPhase.STOPPED, "done")

    assertNull(store.onDeviceInProgress())
  }
}
