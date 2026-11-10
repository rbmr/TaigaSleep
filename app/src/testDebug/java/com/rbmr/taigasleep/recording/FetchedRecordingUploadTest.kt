package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.backend.ApiCode
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RemoteFile
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaConflictException
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.sync.UploadEngine
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SimulatedSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole point of holding a fetch-pending recording back: the one upload it gets afterwards
 * carries every sample, so the analysis sees the night the tracker stored.
 */
class FetchedRecordingUploadTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()
  private val hrOnly = StreamConfig(ecg = false, acc = false)

  @Test
  fun theUploadAfterTheFetchCarriesEverySampleOnce() = runBlocking {
    val dir = tmp.newFolder("rec")
    val writer =
        RecordingWriter.create(
            dir,
            RecordingMeta(
                recordingId = "20261023_223000-k3f9",
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
    val sensor = SimulatedSensor(clock, autoRun = false).also { it.connect() }
    val fanout = SampleFanout(writer, NoMonitor) { message -> writer.event("error", message) }
    sensor.startOnDevice(hrOnly, fanout) {}
    repeat(100) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }
    assertEquals(ConnectionState.CONNECTED, sensor.connection.value)

    // The fetch succeeds: the samples are on the phone, the recording is finished, the copy goes.
    assertTrue(writer.markFetchPending())
    val fetched = OnDeviceImport(writer, sensor, clock).fetch()
    assertTrue(fetched is OnDeviceFetch.Imported)
    val samples = (fetched as OnDeviceFetch.Imported).samples
    assertTrue(samples > 0)
    assertTrue(writer.markTrackerRemoval(fetched.entries.map { it.id }))
    writer.finish(RecordingPhase.STOPPED, "fetched")
    sensor.removeFromDevice(fetched.entries.map { it.id })
    assertEquals(0, sensor.storedEntryCount())

    val server = FakeServer()
    UploadEngine(client = server, id = writer.meta.recordingId, dir = writer.dir).run()

    // One upload, from its first byte, with the finished file and nothing left behind.
    assertEquals(1, server.puts)
    assertEquals(listOf("hr.log" to 0L, "events.jsonl" to 0L), server.appends)
    assertArrayEquals(File(writer.dir, "hr.log").readBytes(), server.stored.getValue("hr.log"))
    assertEquals(1, server.completeCalls)
    assertTrue(server.complete)
    // The uploaded file holds exactly the fetched samples, each once.
    assertEquals(samples, frames(File(writer.dir, "hr.log")))
    assertEquals(samples, RecordingMeta.read(writer.dir)!!.importedEntries.values.sum())
  }

  private fun frames(dir: File): Int {
    var count = 0
    FrameLog.scan(dir) { count++ }
    return count
  }

  /** A server that stores what it is sent, so the test can compare it with the phone's files. */
  private class FakeServer : StubTaigaClient() {
    val stored = mutableMapOf<String, ByteArray>()
    val appends = mutableListOf<Pair<String, Long>>()
    var puts = 0
    var complete = false
    var completeCalls = 0

    override suspend fun putRecording(id: String, metaJson: String): RecordingDto {
      puts++
      return recording(id)
    }

    override suspend fun getRecording(id: String): RecordingDto = recording(id)

    override suspend fun headFile(id: String, file: String): RemoteFile? =
        stored[file]?.let { RemoteFile(size = it.size.toLong(), encoding = "") }

    override suspend fun appendFile(
        id: String,
        file: String,
        offset: Long,
        bytes: ByteArray,
        encoding: String?,
    ): Long {
      val current = stored[file]?.size?.toLong() ?: 0L
      if (offset != current) {
        throw TaigaConflictException(409, "offset mismatch", ApiCode.OFFSET_MISMATCH, current)
      }
      stored[file] = (stored[file] ?: ByteArray(0)) + bytes
      appends += file to offset
      return stored.getValue(file).size.toLong()
    }

    override suspend fun completeRecording(id: String): RecordingDto {
      completeCalls++
      complete = true
      return recording(id)
    }

    private fun recording(id: String) =
        RecordingDto(id = id, state = "stopped", complete = complete)
  }

  /** The fetch tests write samples through the fanout only; no live state is derived. */
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
}
