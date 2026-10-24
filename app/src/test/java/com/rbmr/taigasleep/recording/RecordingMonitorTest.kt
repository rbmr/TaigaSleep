package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.sensor.AccBatch
import com.rbmr.taigasleep.sensor.EcgBatch
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.Stamp
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMonitorTest {
  private val clock = TestClock()
  private val events = mutableListOf<Pair<String, String>>()
  private var health = StorageHealth()

  private fun meta(config: SessionConfig = SessionConfig(ecg = true, acc = true)) =
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
          config = config,
      )

  private fun monitor() =
      RecordingMonitor(meta(), clock, { type, message -> events += type to message }) { health }

  private fun RecordingMonitor.hr(contact: Boolean = true, supported: Boolean = true) =
      onHr(Stamp.now(clock), HrSample(60, supported, contact, true, IntArray(0)))

  private fun RecordingMonitor.ecg(value: Int = 300, samples: Int = 73) =
      onEcg(
          Stamp.now(clock),
          EcgBatch(LongArray(samples) { it * 7_692_307L }, IntArray(samples) { value }),
      )

  private fun RecordingMonitor.acc(samples: Int = 36) =
      onAcc(
          Stamp.now(clock),
          AccBatch(LongArray(samples) { it * 20_000_000L }, IntArray(samples * 3) { 10 }),
      )

  private fun RecordingMonitor.tick() = tick(clock.elapsedNs())

  @Test
  fun contactHysteresisIgnoresFlickerAndDeclaresOffBodyAfterAMinute() {
    val m = monitor()
    clock.advanceMs(1)
    m.hr(contact = true)
    m.tick()
    assertEquals(RecordingState.ContactState.ON_BODY, m.state.value.contact.state)

    // A brief loss shorter than a minute is ignored.
    clock.advanceMs(1_000)
    m.hr(contact = false)
    m.tick()
    clock.advanceMs(30_000)
    m.tick()
    assertEquals(RecordingState.ContactState.ON_BODY, m.state.value.contact.state)
    clock.advanceMs(1_000)
    m.hr(contact = true)
    m.tick()

    // Then a real loss: declared after 60 s, not before.
    clock.advanceMs(3_000)
    m.hr(contact = false)
    m.tick()
    clock.advanceMs(59_000)
    m.tick()
    assertEquals(RecordingState.ContactState.ON_BODY, m.state.value.contact.state)
    clock.advanceMs(1_000)
    m.tick()
    assertEquals(RecordingState.ContactState.OFF_BODY, m.state.value.contact.state)
    assertEquals(listOf("contact" to "no skin contact"), events.filter { it.first == "contact" })

    // Off-body time grows while the strap is off.
    clock.advanceMs(25_000)
    m.tick()
    assertEquals(25_000L, m.state.value.contact.offBodyMs)

    // A flicker back on for less than 5 s does not clear it.
    m.hr(contact = true)
    m.tick()
    clock.advanceMs(4_000)
    m.tick()
    assertEquals(RecordingState.ContactState.OFF_BODY, m.state.value.contact.state)
    clock.advanceMs(1_000)
    m.tick()
    assertEquals(RecordingState.ContactState.ON_BODY, m.state.value.contact.state)
    assertEquals(30_000L, m.state.value.contact.offBodyMs)
    assertEquals(
        listOf("contact" to "no skin contact", "contact" to "skin contact restored"),
        events.filter { it.first == "contact" },
    )
  }

  @Test
  fun aSensorThatCannotReportContactStaysUnknown() {
    val m = monitor()
    m.hr(contact = false, supported = false)
    m.tick()
    assertEquals(RecordingState.ContactState.UNKNOWN, m.state.value.contact.state)
    assertTrue(events.none { it.first == "contact" })
  }

  @Test
  fun rateWindowMeasuresEachStream() {
    val m = monitor()
    // The fake clock starts at 5 s; schedule from here, not from zero.
    var nextEcg = clock.elapsedNs() / 1_000_000L + 600
    var nextAcc = clock.elapsedNs() / 1_000_000L + 720
    repeat(100) { step ->
      clock.advanceMs(100)
      if (step % 10 == 9) m.hr(contact = true)
      if (clock.elapsedNs() / 1_000_000L >= nextEcg) {
        m.ecg()
        nextEcg += 562
      }
      if (clock.elapsedNs() / 1_000_000L >= nextAcc) {
        m.acc()
        nextAcc += 720
      }
      m.tick()
    }
    val streams = m.state.value.streams
    assertEquals(RecordingState.StreamStatus.OK, streams.getValue(RecordingState.StreamKind.HR).status)
    val hr = streams.getValue(RecordingState.StreamKind.HR).rateHz!!
    val ecg = streams.getValue(RecordingState.StreamKind.ECG).rateHz!!
    val acc = streams.getValue(RecordingState.StreamKind.ACC).rateHz!!
    assertTrue("hr $hr", hr in 0.9..1.1)
    assertTrue("ecg $ecg", ecg in 125.0..135.0)
    assertTrue("acc $acc", acc in 47.0..53.0)
  }

  @Test
  fun aSilentStallIsDeclaredOnceAndRecoveryIsCounted() {
    val m = monitor()
    m.ecg()
    m.tick()
    assertEquals(
        RecordingState.StreamStatus.OK,
        m.state.value.streams.getValue(RecordingState.StreamKind.ECG).status,
    )

    clock.advanceMs(4_000)
    m.tick()
    assertEquals(
        RecordingState.StreamStatus.STALE,
        m.state.value.streams.getValue(RecordingState.StreamKind.ECG).status,
    )
    assertEquals(listOf("stream_stalled"), events.map { it.first })

    // Still no data: one event, not one per tick.
    clock.advanceMs(2_000)
    m.tick()
    assertEquals(listOf("stream_stalled"), events.map { it.first })

    m.ecg()
    m.tick()
    val ecg = m.state.value.streams.getValue(RecordingState.StreamKind.ECG)
    assertEquals(RecordingState.StreamStatus.OK, ecg.status)
    assertEquals(1, ecg.restarts)
    assertEquals(listOf("stream_stalled", "stream_recovered"), events.map { it.first })
  }

  @Test
  fun batteryThresholdWritesOneEventAndClears() {
    val m = monitor()
    m.onBattery(10)
    assertEquals(RecordingState.PowerState.LOW, m.state.value.power.state)
    assertEquals(listOf("battery" to "sensor battery low: 10%"), events)

    // Still low: no new event.
    m.onBattery(11)
    assertEquals(listOf("battery" to "sensor battery low: 10%"), events)

    m.onBattery(50)
    assertEquals(RecordingState.PowerState.OK, m.state.value.power.state)
    assertEquals(
        listOf("battery" to "sensor battery low: 10%", "battery" to "sensor battery ok: 50%"),
        events,
    )

    m.onBattery(null)
    assertEquals(RecordingState.PowerState.UNKNOWN, m.state.value.power.state)
    assertEquals(2, events.size)
  }

  @Test
  fun storageHealthIsSurfacedAndReportedOnce() {
    health = StorageHealth(bytesWritten = 4096, lastError = "disk full", lastSyncNs = clock.elapsedNs() - 2_000_000_000L)
    val m = monitor()
    clock.advanceMs(1_000)
    m.tick()
    val state = m.state.value
    assertEquals(4096L, state.storage.bytesWritten)
    assertEquals("disk full", state.storage.lastError)
    assertEquals(3_000L, state.storage.sinceSyncMs)
    assertEquals(RecordingState.Reason.STORAGE_ERROR, state.overall.reason)
    assertEquals(listOf("storage_error" to "writing to the phone failed: disk full"), events)

    clock.advanceMs(1_000)
    m.tick()
    assertEquals(1, events.size)
  }

  @Test
  fun aPinnedEcgIsFlaggedAndRecovers() {
    val m = monitor()
    repeat(4) { m.ecg(value = 18_464) }
    m.tick()
    assertEquals(RecordingState.EcgQuality.POOR, m.state.value.quality.ecg)
    assertEquals(listOf("ecg_signal" to "poor signal (ECG pinned at its limits)"), events)

    repeat(10) { m.ecg(value = 300) }
    m.tick()
    assertEquals(RecordingState.EcgQuality.OK, m.state.value.quality.ecg)
    assertEquals("ecg_signal" to "signal recovered", events.last())
  }

  @Test
  fun overallPriorityIsFixed() {
    val streamOk = RecordingState.Stream(RecordingState.StreamKind.ECG)
    val streamStale = streamOk.copy(status = RecordingState.StreamStatus.STALE)
    fun overall(
        storage: String? = null,
        link: RecordingState.LinkState = RecordingState.LinkState.CONNECTED,
        contact: RecordingState.ContactState = RecordingState.ContactState.ON_BODY,
        streams: Collection<RecordingState.Stream> = listOf(streamOk),
        ecg: RecordingState.EcgQuality = RecordingState.EcgQuality.OK,
        power: RecordingState.PowerState = RecordingState.PowerState.OK,
    ) = overallOf(storage, link, contact, streams, ecg, power)

    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.ERROR, RecordingState.Reason.STORAGE_ERROR),
        overall(storage = "disk full", link = RecordingState.LinkState.DISCONNECTED),
    )
    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.ERROR, RecordingState.Reason.LINK_LOST),
        overall(link = RecordingState.LinkState.CONNECTING, contact = RecordingState.ContactState.OFF_BODY),
    )
    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.WARNING, RecordingState.Reason.OFF_BODY),
        overall(contact = RecordingState.ContactState.OFF_BODY, streams = listOf(streamStale)),
    )
    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.ERROR, RecordingState.Reason.STREAM_STALE),
        overall(streams = listOf(streamStale), ecg = RecordingState.EcgQuality.POOR),
    )
    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.WARNING, RecordingState.Reason.ECG_POOR),
        overall(ecg = RecordingState.EcgQuality.POOR, power = RecordingState.PowerState.LOW),
    )
    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.WARNING, RecordingState.Reason.BATTERY_LOW),
        overall(power = RecordingState.PowerState.LOW),
    )
    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.OK, null),
        overall(),
    )
  }

  @Test
  fun noSessionStartBatteryMeansUnknownPower() {
    val m =
        RecordingMonitor(
            meta().copy(sensorBatteryAtStart = null),
            clock,
            { _, _ -> },
        ) { health }
    assertNull(m.state.value.power.percent)
    assertEquals(RecordingState.PowerState.UNKNOWN, m.state.value.power.state)
  }
}
