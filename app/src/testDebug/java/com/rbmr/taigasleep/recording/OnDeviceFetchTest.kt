package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.Payloads
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.SimulatedSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import com.rbmr.taigasleep.tracker.TrackerEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The on-device fetch as one sequence: stop, read, write, flush, verify. Only a verified import may
 * remove the tracker's copy, and a retry must not import an entry twice.
 */
class OnDeviceFetchTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()
  private val hrOnly = StreamConfig(ecg = false, acc = false)

  private fun meta(id: String) =
      RecordingMeta(
          recordingId = id,
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
      )

  private fun writer(name: String = "rec"): RecordingWriter =
      RecordingWriter.create(tmp.newFolder(name), meta(name), clock)

  private fun simulated(): SimulatedSensor =
      SimulatedSensor(clock, autoRun = false).also {
        it.connect()
        // The first pump completes the simulated connection.
        clock.advanceMs(100)
        it.pump(clock.elapsedNs())
        assertEquals(ConnectionState.CONNECTED, it.connection.value)
      }

  private fun pump(sensor: SimulatedSensor, ms: Long) {
    repeat((ms / 100).toInt()) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }
  }

  private fun hrTimes(writer: RecordingWriter): List<Long> {
    val times = mutableListOf<Long>()
    FrameLog.scan(File(writer.dir, "hr.log")) { times += Payloads.decodeHr(it).phoneNs }
    return times
  }

  @Test
  fun aFetchSlowerThanTheTimeoutKeepsTheTrackerCopyAndWritesNothing() {
    val writer = writer()
    val sensor = simulated()
    sensor.startOnDevice(hrOnly, fanout(writer)) {}
    pump(sensor, 10_000)
    val entries = sensor.storedEntryCount()
    assertTrue(entries > 0)

    sensor.fetchDelayMs = 5_000
    val result = OnDeviceImport(writer, sensor, clock, timeoutMs = 100).fetch()

    assertTrue(result is OnDeviceFetch.Failed)
    assertEquals(entries, sensor.storedEntryCount())
    assertEquals(emptyList<Long>(), hrTimes(writer))
    // The writer closes when the recording ends; a late task must not add a frame after that.
    writer.finish(RecordingPhase.FETCH_PENDING, "timed out")
    Thread.sleep(300)
    assertEquals(emptyList<Long>(), hrTimes(writer))
  }

  @Test
  fun aWriteFailureDuringImportKeepsTheTrackerCopy() {
    val writer = writer()
    val sensor = simulated()
    sensor.startOnDevice(hrOnly, fanout(writer)) {}
    pump(sensor, 10_000)
    val entries = sensor.storedEntries()
    assertTrue(entries.isNotEmpty())

    // A closed writer cannot take frames, so the import must fail before anything is removed.
    writer.close()
    val result = OnDeviceImport(writer, sensor, clock).fetch()

    assertTrue(result is OnDeviceFetch.Failed)
    assertEquals(entries, sensor.storedEntries())
    assertEquals(emptyList<Long>(), hrTimes(writer))
  }

  @Test
  fun anInterruptedFetchRetriedImportsEverySampleExactlyOnce() {
    val writer = writer()
    val sensor = simulated()
    sensor.startOnDevice(hrOnly, fanout(writer)) {}
    pump(sensor, 10_000)
    val firstEntry = sensor.storedEntries().single()
    val firstSamples = firstEntry.rrMs.count { it > 0 }

    val first = OnDeviceImport(writer, sensor, clock).fetch()
    assertTrue(first is OnDeviceFetch.Imported)
    assertEquals(firstSamples, (first as OnDeviceFetch.Imported).samples)
    // The process stops before the tracker copy is removed, as an interrupted stop would.
    assertEquals(1, sensor.storedEntryCount())
    assertEquals(firstSamples, hrTimes(writer).size)

    // The tracker also stored another entry before the retry.
    sensor.startOnDevice(hrOnly, fanout(writer)) {}
    pump(sensor, 10_000)
    val secondEntry = sensor.storedEntries().last()
    val secondSamples = secondEntry.rrMs.count { it > 0 }

    val retry = OnDeviceImport(writer, sensor, clock).fetch()
    assertTrue(retry is OnDeviceFetch.Imported)
    assertEquals(secondSamples, (retry as OnDeviceFetch.Imported).samples)
    assertEquals(firstSamples + secondSamples, hrTimes(writer).size)
    assertEquals(firstEntry.id, sensor.storedEntries().first().id)

    sensor.removeFromDevice(retry.entries)
    assertEquals(0, sensor.storedEntryCount())
  }

  @Test
  fun multipleTrackerEntriesUseTheirOwnStartAndDoNotOverlap() {
    val writer = writer()
    val sensor = simulated()
    val firstStart = clock.phoneNs / 1_000_000L
    sensor.startOnDevice(hrOnly, fanout(writer)) {}
    pump(sensor, 10_000)
    val firstSamples = sensor.storedEntries().first().rrMs.count { it > 0 }

    // A gap between two tracker entries: the second exercise starts half a minute later.
    clock.advanceMs(30_000)
    val secondStart = clock.phoneNs / 1_000_000L
    sensor.startOnDevice(hrOnly, fanout(writer)) {}
    pump(sensor, 10_000)
    val secondSamples = sensor.storedEntries().last().rrMs.count { it > 0 }

    val result = OnDeviceImport(writer, sensor, clock).fetch()
    assertTrue(result is OnDeviceFetch.Imported)
    val times = hrTimes(writer)
    assertEquals(firstSamples + secondSamples, times.size)

    val first = times.take(firstSamples)
    val second = times.drop(firstSamples)
    assertTrue(first.all { it >= firstStart * 1_000_000L })
    assertTrue(first.all { it < secondStart * 1_000_000L })
    assertTrue(second.all { it >= secondStart * 1_000_000L })
    assertTrue("entries must not overlap", first.last() < second.first())
    assertTrue("the gap between the entries must stay", second.first() - first.last() >= 20_000_000_000L)
    assertTrue(secondSamples > 0)
  }

  @Test
  fun importedTimestampsComeFromTheEntryStartNotFromTheStop() {
    val writer = writer()
    val sensor = simulated()
    sensor.startOnDevice(hrOnly, fanout(writer)) {}
    pump(sensor, 10_000)
    val entry = sensor.storedEntries().single()
    val startNs = entry.startUtcMs * 1_000_000L

    // Fetched an hour later, as in the morning. The timestamps must still be near the start.
    clock.advanceMs(60 * 60_000)
    val result = OnDeviceImport(writer, sensor, clock).fetch()

    assertTrue(result is OnDeviceFetch.Imported)
    val times = hrTimes(writer)
    assertTrue(times.isNotEmpty())
    assertTrue(times.first() >= startNs)
    assertTrue(times.last() < startNs + 60_000_000_000L)
  }

  @Test
  fun aZeroIntervalIsAGapAndNotASample() {
    val writer = writer()
    val tracker = FixedTracker(listOf(TrackerEntry("fixed", 1_000_000L, listOf(1000, 0, 1000))))

    val result = OnDeviceImport(writer, tracker, clock).fetch()

    assertTrue(result is OnDeviceFetch.Imported)
    assertEquals(2, (result as OnDeviceFetch.Imported).samples)
    assertEquals(listOf(1_001_000_000_000L, 1_002_000_000_000L), hrTimes(writer))
    // The import itself never removes the tracker copy.
    assertNull(tracker.removed)
  }

  private fun fanout(writer: RecordingWriter): SampleListener =
      SampleFanout(writer, NoMonitor) { writer.event("error", it) }

  /** The import tests do not derive live state; every sample is still written by the fanout. */
  private object NoMonitor : SampleMonitor {
    override fun onHr(
        stamp: com.rbmr.taigasleep.tracker.Stamp,
        sample: com.rbmr.taigasleep.tracker.HrSample,
    ) {}

    override fun onEcg(
        stamp: com.rbmr.taigasleep.tracker.Stamp,
        batch: com.rbmr.taigasleep.tracker.EcgBatch,
    ) {}

    override fun onAcc(
        stamp: com.rbmr.taigasleep.tracker.Stamp,
        batch: com.rbmr.taigasleep.tracker.AccBatch,
    ) {}
  }

  /** A tracker whose entries are fixed, for the gap rule that the simulator does not produce. */
  private class FixedTracker(private val entries: List<TrackerEntry>) : OnDeviceTracker {
    var removed: List<TrackerEntry>? = null

    override fun startOnDevice(
        config: StreamConfig,
        listener: SampleListener,
        onResult: (error: String?) -> Unit,
    ) = onResult(null)

    override fun stopOnDevice() {}

    override fun fetchOnDevice(): List<TrackerEntry> = entries

    override fun removeFromDevice(entries: List<TrackerEntry>) {
      removed = entries
    }

    override fun discardOnDevice() {}
  }
}
