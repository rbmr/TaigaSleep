package com.rbmr.taigasleep.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cost of a recording, driven through the path a real recording takes: begin, one heartbeat a
 * minute and a stop. No phone is involved: the battery, the process clocks and the traffic counters
 * are fakes behind [RecordingCost.Sources].
 */
class RecordingCostTest {
  private val fake = FakeSources()
  private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

  private val startUtcMs = 1_792_798_200_000L
  private val t0 = 5_000_000L

  @Before
  fun install() {
    RecordingCost.install(fake) { type, fields -> events += type to fields }
  }

  @After
  fun reset() {
    RecordingCost.install(null)
    events.clear()
  }

  private fun begin(resumed: Boolean = false, atMs: Long = t0) =
      RecordingCost.begin(
          recordingId = ID,
          startedUtcMs = startUtcMs,
          nowUtcMs = startUtcMs + atMs - t0,
          nowElapsedMs = atMs,
          resumed = resumed,
      )

  private fun heartbeat(
      atMs: Long,
      battery: Int = 80,
      interactive: Boolean = true,
      doze: Boolean = false,
      charging: Boolean = false,
  ) =
      RecordingCost.heartbeatFields(
          nowElapsedMs = atMs,
          batteryPct = battery,
          interactive = interactive,
          doze = doze,
          charging = charging,
      )

  @Test
  fun `nothing is read and nothing is logged while no recording is active`() {
    assertNull(heartbeat(t0 + 60_000))
    assertEquals("no phone reading happens without a recording", 0, fake.reads)
    assertTrue(events.isEmpty())

    // A counter touched outside a recording changes nothing that is ever logged.
    RecordingCost.tick()
    RecordingCost.fsync()
    RecordingCost.ble("hr", 10)
    assertTrue(events.isEmpty())
  }

  @Test
  fun `a simulated recording reports every counter in the heartbeat`() {
    begin()
    RecordingCost.wakeLockAcquired(t0)
    repeat(120) { RecordingCost.tick() }
    repeat(2) { RecordingCost.timerFired() }
    repeat(3) { RecordingCost.preferenceWrite() }
    RecordingCost.recordingWrite(1_000)
    RecordingCost.recordingWrite(500)
    RecordingCost.eventWrite(100)
    RecordingCost.diagnosticsWrite(450)
    RecordingCost.fsync()
    RecordingCost.ble("hr", 5)
    RecordingCost.ble("ecg", 390)
    RecordingCost.ble("acc", 600)
    RecordingCost.ble("ppi", 700)
    fake.cpuMs = 2_000
    fake.ownThreadCpuMs = 20
    fake.gcCount = 12
    fake.gcTimeMs = 120
    fake.txBytes = 4_000
    fake.rxBytes = 6_000
    fake.thermalHeadroom = 0.9f

    fake.battery = 80
    fake.charge = 5_000_000L
    heartbeat(t0, battery = 80)
    fake.charge = 4_999_000L
    heartbeat(t0 + 60_000, battery = 79, doze = true)
    heartbeat(t0 + 120_000, battery = 79, doze = true)
    fake.charge = 4_998_000L
    val beat =
        heartbeat(t0 + 240_000, battery = 78, interactive = false, doze = true)!!

    assertEquals(4_998_000L, beat["c"])
    assertEquals(-300_000L, beat["i"])
    assertEquals(60_000_000_000L, beat["e"])
    assertEquals(listOf(2_000L, 20L), beat["p"])
    assertEquals("held for the four minutes, acquired once", listOf(240_000L, 1L), beat["w"])
    assertEquals(listOf(120L, 2L, 4L, 3L), beat["t"])
    assertEquals(listOf(1_500L, 2L, 100L, 1L, 450L, 1L, 1L), beat["o"])
    assertEquals(
        mapOf(
            "acc" to listOf(1L, 600L),
            "ecg" to listOf(1L, 390L),
            "hr" to listOf(1L, 5L),
            "ppi" to listOf(1L, 700L),
        ),
        beat["b"],
    )
    assertEquals("traffic since the recording began", listOf(3_000L, 4_000L), beat["n"])
    assertEquals(listOf(12L, 120L), beat["g"])
    assertEquals("four minutes of screen on, three of doze", listOf(240_000L, 180_000L), beat["m"])
    assertEquals(0.9, beat["h"])
    assertTrue("the collection cost is recorded", (beat["u"] as Long) >= 0)
  }

  @Test
  fun `a heartbeat's cost fields are pinned byte for byte`() {
    begin()
    fake.charge = 5_000_000L
    fake.currentNowUa = -300_000L
    fake.energyNwh = 60_000_000_000L
    fake.cpuMs = 2_000
    fake.ownThreadCpuMs = 20
    fake.gcCount = 12
    fake.gcTimeMs = 120
    fake.txBytes = 4_000
    fake.rxBytes = 6_000
    fake.thermalHeadroom = 0.9f
    RecordingCost.wakeLockAcquired(t0)
    repeat(3) { RecordingCost.tick() }
    RecordingCost.timerFired()
    repeat(2) { RecordingCost.preferenceWrite() }
    RecordingCost.recordingWrite(1_000)
    RecordingCost.recordingWrite(500)
    RecordingCost.eventWrite(100)
    RecordingCost.diagnosticsWrite(450)
    RecordingCost.fsync()
    RecordingCost.ble("hr", 5)
    RecordingCost.ble("ecg", 390)

    val beat = heartbeat(t0 + 60_000, battery = 79)!!
    // The collection time varies by machine; everything else is the format.
    val pinned = beat.filterKeys { it != "u" }
    val line = JsonLine.build(0, 0, 1, "diag_heartbeat", mapOf("uptime_s" to 60, "cost" to pinned))
    assertEquals(
        """{"t_ms":0,"elapsed_ms":0,"pid":1,"type":"diag_heartbeat","uptime_s":60,"cost":""" +
            """{"c":5000000,"i":-300000,"e":60000000000,"p":[2000,20],"w":[60000,1],""" +
            """"t":[3,1,1,2],"o":[1500,2,100,1,450,1,1],"b":{"ecg":[1,390],"hr":[1,5]},""" +
            """"n":[3000,4000],"g":[12,120],"m":[0,0],"h":0.9}}""",
        line,
    )
  }

  @Test
  fun `the cost event of a normal stop divides drain, wake lock and CPU per hour`() {
    fake.cpuMs = 0
    fake.charge = 5_000_000L
    begin()
    RecordingCost.wakeLockAcquired(t0)
    heartbeat(t0, battery = 80)
    // One hour of screen-on: five battery points and 20 000 uAh.
    fake.cpuMs = 3_600_000
    fake.charge = 4_980_000L
    heartbeat(t0 + 3_600_000, battery = 75)
    RecordingCost.recordingWrite(7_000_000)
    RecordingCost.eventWrite(1_000)
    RecordingCost.diagnosticsWrite(20_000)
    repeat(5) { RecordingCost.fsync() }
    RecordingCost.streamStalled()
    RecordingCost.reconnected()
    RecordingCost.reconnected()
    RecordingCost.watchdogRestart()
    RecordingCost.tick()

    fake.battery = 75
    RecordingCost.end("stopped", startUtcMs + 3_600_000, t0 + 3_600_000)

    val event = onlyCost()
    assertEquals("stopped", event["reason"])
    assertEquals(ID, event["recording"])
    assertEquals(startUtcMs, event["started_utc_ms"])
    assertEquals(3_600L, event["duration_s"])
    assertEquals(3_600L, event["segment_s"])
    assertEquals(80, event["battery_pct_start"])
    assertEquals(75, event["battery_pct_end"])
    assertEquals(5_000_000L, event["charge_uah_start"])
    assertEquals(4_980_000L, event["charge_uah_end"])
    assertEquals("the lock is still held at the stop", 3_600_000L, event["wake_lock_ms"])
    assertEquals(1L, event["wake_lock_n"])
    assertEquals(3_600_000L, event["cpu_ms"])
    assertEquals(3_600_000.0, event["cpu_ms_per_h"])
    assertEquals(7_000_000L, event["bytes_written"])
    assertEquals(1_000L, event["event_bytes"])
    assertEquals(20_000L, event["diag_bytes"])
    assertEquals(5L, event["fsyncs"])
    assertEquals(1L, event["stream_stalls"])
    assertEquals(2L, event["reconnects"])
    assertEquals(1L, event["watchdog_restarts"])
    assertEquals(5.0, event["drain_pct_per_h_on"])
    assertEquals(20_000.0, event["drain_uah_per_h_on"])
    assertNull("nothing drained with the screen off", event["drain_pct_per_h_off"])
  }

  @Test
  fun `a charging recording is flagged and its charge is not averaged into drain`() {
    fake.charging = true
    begin()
    RecordingCost.wakeLockAcquired(t0)
    heartbeat(t0, battery = 90, charging = true)
    fake.battery = 95
    fake.charge = 5_100_000L
    RecordingCost.end("stopped", startUtcMs + 3_600_000, t0 + 3_600_000)

    val event = onlyCost()
    assertEquals("the night was charging", 3_600L, event["charging_s"])
    assertEquals(true, event["charging_end"])
    assertNull("a charging night has no drain to report", event["drain_uah_per_h_on"])
    assertNull(event["drain_pct_per_h_on"])
  }

  @Test
  fun `a recording recovered after a kill marks its new segment and keeps the whole duration`() {
    begin(resumed = true, atMs = t0 + 600_000)

    val recovered = onlyCost()
    assertEquals("recovered", recovered["reason"])
    assertEquals(ID, recovered["recording"])
    assertEquals("ten minutes ran before this process", 600L, recovered["duration_s"])
    assertEquals(0L, recovered["segment_s"])
    assertEquals(80, recovered["battery_pct_start"])
    assertEquals(80, recovered["battery_pct_end"])

    fake.cpuMs = 2_000
    heartbeat(t0 + 660_000, battery = 79)
    RecordingCost.end("stopped", startUtcMs + 1_200_000, t0 + 1_200_000)

    val stopped = events.last().second
    assertEquals("stopped", stopped["reason"])
    assertEquals("the duration covers the killed segment too", 1_200L, stopped["duration_s"])
    assertEquals("the counters cover this process only", 600L, stopped["segment_s"])
  }

  @Test
  fun `an end without a recording logs nothing`() {
    RecordingCost.end("stopped", startUtcMs, t0)
    assertTrue(events.isEmpty())
  }

  @Test
  fun `the heartbeat cost block stays inside the size and time budget`() {
    begin()
    // Every optional reading, at the largest values a night produces.
    fake.charge = 5_000_000L
    fake.currentNowUa = -800_000L
    fake.energyNwh = 60_000_000_000L
    fake.cpuMs = 3_000_000
    fake.ownThreadCpuMs = 5_000
    fake.gcCount = 500
    fake.gcTimeMs = 3_000
    fake.txBytes = 4_000_000
    fake.rxBytes = 6_000_000
    fake.thermalHeadroom = 0.9f
    RecordingCost.wakeLockAcquired(t0)
    repeat(28_800) { RecordingCost.tick() }
    repeat(480) { RecordingCost.timerFired() }
    repeat(1_920) { RecordingCost.preferenceWrite() }
    repeat(360_000) {
      if (it % 100 == 0) RecordingCost.recordingWrite(4_000)
      if (it % 1_000 == 0) RecordingCost.eventWrite(100)
      if (it % 1_000 == 0) RecordingCost.diagnosticsWrite(450)
    }
    repeat(480) { RecordingCost.fsync() }
    repeat(28_800) { RecordingCost.ble("hr", 5) }
    // One ECG notification carries about ten samples, one accelerometer notification about four.
    repeat(360_000) { RecordingCost.ble("ecg", 55) }
    repeat(360_000) { RecordingCost.ble("acc", 59) }

    val plain = JsonLine.build(0, 0, 1, "diag_heartbeat", mapOf("uptime_s" to 28_800))
    val full = heartbeat(t0 + 60_000)!!
    val withCost =
        JsonLine.build(0, 0, 1, "diag_heartbeat", mapOf("uptime_s" to 28_800, "cost" to full))
    val growth = withCost.length - plain.length
    assertTrue("the cost block grew the heartbeat by $growth bytes: $full", growth <= 295)

    // Without an energy counter or thermal headroom the phone reports neither: the common case.
    fake.energyNwh = null
    fake.thermalHeadroom = null
    val common = heartbeat(t0 + 120_000)!!
    val commonLine =
        JsonLine.build(0, 0, 1, "diag_heartbeat", mapOf("uptime_s" to 28_800, "cost" to common))
    val commonGrowth = commonLine.length - plain.length
    assertTrue("the common block grew the heartbeat by $commonGrowth bytes: $common", commonGrowth <= 270)
    val collectUs = (heartbeat(t0 + 180_000)!!["u"] as Long)
    assertTrue("collecting the block cost ${collectUs}us", collectUs < 5_000)
  }

  private fun onlyCost(): Map<String, Any?> {
    assertEquals(1, events.size)
    assertEquals("recording_cost", events.single().first)
    return events.single().second
  }

  private class FakeSources : RecordingCost.Sources {
    var battery = 80
    var charge: Long? = 5_000_000
    var currentNowUa: Long? = -300_000
    var energyNwh: Long? = 60_000_000_000
    var charging = false
    var cpuMs = 1_000L
    var ownThreadCpuMs: Long? = 10
    var gcCount: Long? = 10
    var gcTimeMs: Long? = 100
    var txBytes: Long? = 1_000
    var rxBytes: Long? = 2_000
    var thermalHeadroom: Float? = 0.5f
    var reads = 0

    override fun batteryPercent(): Int {
      reads++
      return battery
    }

    override fun chargeCounterUah(): Long? {
      reads++
      return charge
    }

    override fun currentNowUa(): Long? {
      reads++
      return currentNowUa
    }

    override fun energyNwh(): Long? {
      reads++
      return energyNwh
    }

    override fun charging(): Boolean {
      reads++
      return charging
    }

    override fun processCpuMs(): Long {
      reads++
      return cpuMs
    }

    override fun ownThreadCpuMs(): Long? {
      reads++
      return ownThreadCpuMs
    }

    override fun gcCount(): Long? {
      reads++
      return gcCount
    }

    override fun gcTimeMs(): Long? {
      reads++
      return gcTimeMs
    }

    override fun txBytes(): Long? {
      reads++
      return txBytes
    }

    override fun rxBytes(): Long? {
      reads++
      return rxBytes
    }

    override fun thermalHeadroom(): Float? {
      reads++
      return thermalHeadroom
    }
  }

  private companion object {
    const val ID = "20261023_223000-k3f9"
  }
}
