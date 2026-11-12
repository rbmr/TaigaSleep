package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMonitorTest {
  private val clock = TestClock()
  private val events = mutableListOf<Pair<String, String>>()
  private var health = StorageHealth()

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

    // A brief loss shorter than a minute is logged immediately but does not change the state.
    clock.advanceMs(1_000)
    m.hr(contact = false)
    m.tick()
    clock.advanceMs(30_000)
    m.tick()
    assertEquals(RecordingState.ContactState.ON_BODY, m.state.value.contact.state)
    clock.advanceMs(1_000)
    m.hr(contact = true)
    m.tick()
    assertEquals(
        listOf("contact" to "no skin contact", "contact" to "skin contact restored"),
        events.filter { it.first == "contact" },
    )

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
    assertEquals(
        listOf("contact" to "no skin contact", "contact" to "skin contact restored", "contact" to "no skin contact"),
        events.filter { it.first == "contact" },
    )

    // Off-body time grows while the strap is off.
    clock.advanceMs(25_000)
    m.tick()
    assertEquals(25_000L, m.state.value.contact.offBodyMs)

    // A flicker back on for less than 5 s is logged but does not clear the state yet.
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
        listOf(
            "contact" to "no skin contact",
            "contact" to "skin contact restored",
            "contact" to "no skin contact",
            "contact" to "skin contact restored",
        ),
        events.filter { it.first == "contact" },
    )
  }

  @Test
  fun shortContactLossesAreLoggedEvenWhenTheStateDoesNotChange() {
    // This is the pattern in the real 17-step capture: the strap was off for 13 s and 8 s, both
    // shorter than the 60 s notification hysteresis, but they must still be annotated.
    val m = monitor()
    m.hr(contact = true)
    m.tick()
    clock.advanceMs(1_000)
    m.hr(contact = false)
    m.tick()
    clock.advanceMs(12_000)
    m.hr(contact = true)
    m.tick()
    assertEquals(RecordingState.ContactState.ON_BODY, m.state.value.contact.state)
    assertEquals(
        listOf("contact" to "no skin contact", "contact" to "skin contact restored"),
        events.filter { it.first == "contact" },
    )
  }

  @Test
  fun linkLossAndReconnectionAreLogged() {
    val m = monitor()
    m.onLink(ConnectionState.CONNECTED)
    m.onLink(ConnectionState.CONNECTING)
    m.onLink(ConnectionState.CONNECTED)
    assertEquals(
        listOf("connect" to "Simulated sensor", "disconnect" to "disconnected", "reconnect" to "Simulated sensor"),
        events,
    )
    assertEquals(RecordingState.LinkState.CONNECTED, m.state.value.link.state)
    assertEquals(1, m.state.value.link.reconnects)
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
  fun aStalledHrAsksForRecoveryAfterThirtySecondsAndRecordsWhy() {
    val m = monitor()
    m.onPhase(RecordingState.Phase.RECORDING)
    m.onLink(ConnectionState.CONNECTED)
    m.hr()
    m.tick()

    // The stale display comes after a few seconds; recovery waits for real silence.
    clock.advanceMs(29_000)
    assertEquals(emptyList<RecoveryStep>(), m.tick())
    clock.advanceMs(1_000)
    val steps = m.tick()
    assertEquals(RecoveryStepKind.RESTART_STREAMS, steps.single().kind)
    assertEquals("recovery" to steps.single().reason, events.last())
    assertTrue(events.last().second.contains("30 s"))

    // The monitor keeps the ladder going when the stream stays silent.
    clock.advanceMs(30_000)
    assertEquals(RecoveryStepKind.RECONNECT, m.tick().single().kind)
    assertEquals(2, events.count { it.first == "recovery" })

    // A sample that arrives resets it: no further steps from the old stall.
    clock.advanceMs(10_000)
    m.hr()
    m.tick()
    clock.advanceMs(10_000)
    assertTrue(m.tick().isEmpty())
  }

  @Test
  fun aLinkDownForAMinuteRecordsThePhoneViewOnceAndThenRecovers() {
    var views = 0
    val m =
        RecordingMonitor(
            meta(),
            clock,
            { type, message -> events += type to message },
            strapView = {
              views++
              "phone sees the strap: gatt connected, listed in the phone's connected devices"
            },
        ) { health }
    m.onPhase(RecordingState.Phase.RECORDING)
    m.onLink(ConnectionState.CONNECTED)
    m.hr()
    m.tick()
    m.onLink(ConnectionState.CONNECTING)

    // The first 30 s of a lost link are left alone; then the phone's view is written once.
    clock.advanceMs(29_999)
    assertTrue(m.tick().isEmpty())
    assertTrue(events.none { it.first == "strap" })
    clock.advanceMs(1)
    m.tick()
    assertEquals(
        listOf(
            "strap" to
                "phone sees the strap: gatt connected, listed in the phone's connected devices"
        ),
        events.filter { it.first == "strap" },
    )
    assertEquals(1, views)

    // Still the same outage: no second view. The link-down ladder starts at 60 s.
    clock.advanceMs(30_000)
    val steps = m.tick()
    assertEquals(RecoveryStepKind.RECONNECT, steps.single().kind)
    // The step carries a fresh view, written just before its reason.
    assertEquals(2, views)
    assertEquals("strap", events[events.size - 2].first)
    assertEquals("recovery" to steps.single().reason, events.last())
  }

  @Test
  fun everyRecoveryStepCarriesThePhoneViewOfTheStrap() {
    val m =
        RecordingMonitor(
            meta(),
            clock,
            { type, message -> events += type to message },
            strapView = {
              "phone sees the strap: gatt disconnected, " +
                  "not listed in the phone's connected devices"
            },
        ) { health }
    m.onPhase(RecordingState.Phase.RECORDING)
    m.onLink(ConnectionState.CONNECTED)
    m.hr()
    m.tick()

    clock.advanceMs(30_000)
    val restart = m.tick().single()
    assertEquals(RecoveryStepKind.RESTART_STREAMS, restart.kind)
    assertEquals("strap", events[events.size - 2].first)
    assertEquals("recovery" to restart.reason, events.last())

    // The reconnect step after the restart carries its own view too.
    clock.advanceMs(30_000)
    val reconnect = m.tick().single()
    assertEquals(RecoveryStepKind.RECONNECT, reconnect.kind)
    assertEquals(2, events.count { it.first == "strap" })
    assertEquals("strap", events[events.size - 2].first)
    assertEquals("recovery" to reconnect.reason, events.last())
  }

  @Test
  fun aBluetoothCycleAsksToReconnectAsSoonAsTheRadioIsBack() {
    val m = monitor()
    m.onPhase(RecordingState.Phase.RECORDING)
    m.onLink(ConnectionState.CONNECTED)
    m.hr()
    m.tick()

    val wait = m.onBluetooth(false).single()
    assertEquals(RecoveryStepKind.WAIT, wait.kind)
    assertEquals("recovery" to wait.reason, events.last())

    clock.advanceMs(5 * 60_000)
    assertTrue("nothing is escalated while the radio is off", m.tick().isEmpty())

    val reconnect = m.onBluetooth(true).single()
    assertEquals(RecoveryStepKind.RECONNECT, reconnect.kind)
    assertEquals("recovery" to reconnect.reason, events.last())
  }

  @Test
  fun anOnDeviceRecordingAsksForNoStreamRecovery() {
    val m =
        RecordingMonitor(
            meta().copy(mode = RecordingMode.ON_DEVICE),
            clock,
            { type, message -> events += type to message },
        ) { health }
    m.onPhase(RecordingState.Phase.RECORDING)
    m.onLink(ConnectionState.CONNECTED)
    clock.advanceMs(10 * 60_000)
    assertTrue(m.tick().isEmpty())
    assertTrue(m.onBluetooth(false).isEmpty())
    assertTrue(events.none { it.first == "recovery" })
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
    health =
        StorageHealth(
            bytesWritten = 4096,
            lastError = "disk full",
            lastSyncNs = clock.elapsedNs() - 2_000_000_000L,
            droppedFrames = 3,
        )
    val m = monitor()
    clock.advanceMs(1_000)
    m.tick()
    val state = m.state.value
    assertEquals(4096L, state.storage.bytesWritten)
    assertEquals("disk full", state.storage.lastError)
    assertEquals(3_000L, state.storage.sinceSyncMs)
    assertEquals(3L, state.storage.droppedFrames)
    assertEquals(RecordingState.Reason.STORAGE_ERROR, state.overall.reason)
    assertEquals(listOf("storage_error" to "writing to the phone failed: disk full"), events)

    clock.advanceMs(1_000)
    m.tick()
    assertEquals(1, events.size)

    // Writing works again: the state clears and no second event is written.
    health = StorageHealth(bytesWritten = 8192, lastSyncNs = clock.elapsedNs())
    clock.advanceMs(1_000)
    m.tick()
    assertNull(m.state.value.storage.lastError)
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
        lowSpace: Boolean = false,
        link: RecordingState.LinkState = RecordingState.LinkState.CONNECTED,
        contact: RecordingState.ContactState = RecordingState.ContactState.ON_BODY,
        streams: Collection<RecordingState.Stream> = listOf(streamOk),
        ecg: RecordingState.EcgQuality = RecordingState.EcgQuality.OK,
        power: RecordingState.PowerState = RecordingState.PowerState.OK,
    ) = overallOf(storage, lowSpace, link, contact, streams, ecg, power)

    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.ERROR, RecordingState.Reason.STORAGE_ERROR),
        overall(storage = "disk full", lowSpace = true, link = RecordingState.LinkState.DISCONNECTED),
    )
    assertEquals(
        RecordingState.Overall(RecordingState.OverallState.WARNING, RecordingState.Reason.STORAGE_LOW),
        overall(lowSpace = true, link = RecordingState.LinkState.DISCONNECTED),
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
