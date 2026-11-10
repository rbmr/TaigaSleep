package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSpaceTest {
  private val clock = TestClock()
  private val events = mutableListOf<Pair<String, String>>()

  /** A phone whose free space the test controls, as if the disk were filling up. */
  private var free = 0L
  private var reads = 0
  private val space = SpaceProvider {
    reads++
    free
  }

  private fun meta(config: RecordingConfig = RecordingConfig(ecg = true, acc = true)) =
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
          config = config,
      )

  private fun monitor(config: RecordingConfig = RecordingConfig(ecg = true, acc = true)) =
      RecordingMonitor(
          meta = meta(config),
          clock = clock,
          event = { type, message -> events += type to message },
          space = space,
      ) {
        StorageHealth()
      }

  private fun RecordingMonitor.tick() = tick(clock.elapsedNs())

  private fun storageEvents() =
      events.filter { it.first == "storage" || it.first == "storage_low" }.map { it.first }

  @Test
  fun aNightWithEcgAndMovementNeedsMoreSpaceThanHeartRateAlone() {
    val hr = RecordingConfig(hr = true, ecg = false, acc = false)
    val all = RecordingConfig(hr = true, ecg = true, acc = true)
    assertTrue(StorageSpace.requiredBytes(all) > StorageSpace.requiredBytes(hr) * 10)
    assertTrue(StorageSpace.requiredBytes(all) > StorageSpace.lowBytes(all))
    assertEquals(StorageSpace.requiredBytes(all) / StorageSpace.NIGHT_HOURS, StorageSpace.lowBytes(all))
  }

  @Test
  fun aStartIsRefusedBelowTheSpaceAFullNightNeeds() {
    val config = RecordingConfig(ecg = true, acc = true)
    val required = StorageSpace.requiredBytes(config)
    assertFalse(StorageSpace.canStart(required - 1, config))
    assertTrue(StorageSpace.canStart(required, config))
  }

  @Test
  fun lowSpaceIsRaisedDuringARecordingAndRecheckedEveryMinute() {
    val config = RecordingConfig(ecg = true, acc = true)
    free = StorageSpace.requiredBytes(config)
    val m = monitor(config)
    m.onLink(ConnectionState.CONNECTED)
    m.tick()
    assertEquals(free, m.state.value.storage.freeBytes)
    assertFalse(m.state.value.storage.lowSpace)
    assertNull(m.state.value.overall.reason)
    assertEquals(1, reads)

    // A second tick a second later does not ask the phone again.
    clock.advanceMs(1_000)
    m.tick()
    assertEquals(1, reads)

    // The disk fills: below one hour of stream time the state says so, and the read happens again.
    free = StorageSpace.lowBytes(config) - 1
    clock.advanceMs(59_000)
    m.tick()
    assertEquals(2, reads)
    assertEquals(free, m.state.value.storage.freeBytes)
    assertTrue(m.state.value.storage.lowSpace)
    assertEquals(RecordingState.Reason.STORAGE_LOW, m.state.value.overall.reason)
    assertEquals(RecordingState.OverallState.WARNING, m.state.value.overall.state)
    assertEquals(listOf("storage_low"), storageEvents())
    assertTrue(events.single { it.first == "storage_low" }.second.contains("MB free"))

    // Space came back: the warning clears and the change is recorded.
    free = StorageSpace.requiredBytes(config)
    clock.advanceMs(60_000)
    m.tick()
    assertFalse(m.state.value.storage.lowSpace)
    assertNull(m.state.value.overall.reason)
    assertEquals(listOf("storage_low", "storage"), storageEvents())
  }

  @Test
  fun aRecordingWithoutASpaceProviderReportsNoFreeSpace() {
    val m =
        RecordingMonitor(
            meta().copy(config = RecordingConfig(ecg = false, acc = false)),
            clock,
            { _, _ -> },
        ) {
          StorageHealth()
        }
    m.tick()
    assertNull(m.state.value.storage.freeBytes)
    assertFalse(m.state.value.storage.lowSpace)
  }
}
