package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.StreamConfig
import com.rbmr.taigasleep.tracker.TrackerEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A fetch that follows an attempt the phone did not finish. The sample counts decide what the log
 * may still hold, so a half-written entry is cut back instead of being stored twice, and a tracker
 * that no longer has entries the phone already imported is a finished fetch rather than a failure.
 */
class OnDeviceFetchRecoveryTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun writer(): RecordingWriter =
      RecordingWriter.create(
          tmp.newFolder("rec"),
          RecordingMeta(
              recordingId = "20261023_223000",
              state = RecordingPhase.RECORDING,
              mode = RecordingMode.ON_DEVICE,
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
              config = RecordingConfig(ecg = false, acc = false),
          ),
          clock,
      )

  private fun frames(dir: File): Int {
    var count = 0
    FrameLog.scan(File(dir, "hr.log")) { count++ }
    return count
  }

  private fun writeSamples(writer: RecordingWriter, count: Int) {
    repeat(count) {
      writer.writeHr(Stamp.now(clock), HrSample(60, true, true, true, intArrayOf(1000)))
    }
  }

  @Test
  fun samplesTheRecordingDoesNotCountAreCutBackInsteadOfStoredTwice() {
    val writer = writer()
    writer.markFetchPending()
    // A process died while appending a tracker entry: the log holds its samples, the meta does not.
    writeSamples(writer, 3)
    writer.close()

    val resumed = RecordingWriter.resumeFetch(writer.dir, clock, "fetching again")!!
    val tracker =
        FixedTracker(listOf(TrackerEntry("entry-1", 1_000_000L, listOf(1000, 1000, 1000))))
    val result = OnDeviceImport(resumed, tracker, clock).fetch()

    assertTrue(result is OnDeviceFetch.Imported)
    // The entry is stored once, not once on top of the copy that was already there.
    assertEquals(3, frames(resumed.dir))
    assertEquals(mapOf("entry-1" to 3), resumed.meta.importedEntries)
  }

  @Test
  fun aTrackerWithoutTheEntriesThePhoneAlreadyImportedIsAFinishedFetch() {
    val writer = writer()
    writer.markFetchPending()
    writeSamples(writer, 2)
    assertTrue(writer.markOnDeviceImported("entry-1", 2))
    writer.close()

    val resumed = RecordingWriter.resumeFetch(writer.dir, clock, "fetching again")!!
    val result = OnDeviceImport(resumed, FixedTracker(emptyList()), clock).fetch()

    assertTrue(result is OnDeviceFetch.Imported)
    assertEquals(0, (result as OnDeviceFetch.Imported).samples)
    assertEquals(2, frames(resumed.dir))
  }

  @Test
  fun aTrackerWithNothingAndNothingImportedIsStillAFailure() {
    val result = OnDeviceImport(writer(), FixedTracker(emptyList()), clock).fetch()

    assertTrue(result is OnDeviceFetch.Failed)
    assertEquals("the tracker has no recording", (result as OnDeviceFetch.Failed).reason)
  }

  /** A tracker whose entries are fixed, so a test states exactly what it holds. */
  private class FixedTracker(private val entries: List<TrackerEntry>) : OnDeviceTracker {
    override fun startOnDevice(
        config: StreamConfig,
        listener: SampleListener,
        onResult: (error: String?) -> Unit,
    ) = onResult(null)

    override fun stopOnDevice() {}

    override fun fetchOnDevice(): List<TrackerEntry> = entries

    override fun removeFromDevice(ids: List<String>) {}

    override fun discardOnDevice() {}
  }
}
