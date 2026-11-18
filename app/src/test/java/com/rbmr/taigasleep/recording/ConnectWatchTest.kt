package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.TrackerCapabilities
import com.rbmr.taigasleep.tracker.TrackerListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lines a connect that stays pending leaves, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectWatchTest {
  private val tracker = PendingTracker()
  private val logs = mutableListOf<Pair<String, Map<String, Any?>>>()

  private fun types(): List<String> = logs.map { it.first }

  private fun lastFields(): Map<String, Any?> = logs.last().second

  private fun TestScope.watch() {
    ConnectWatch(
            nowMs = { testScheduler.currentTime },
            log = { type, fields -> logs += type to fields },
        )
        .watch(
            scope = backgroundScope,
            tracker = tracker,
            strapView = { "phone sees the strap: gatt disconnected, not listed" },
            bluetoothOn = { true },
        )
  }

  @Test
  fun `a connect pending for 30 seconds is written once and again five minutes later`() = runTest {
    tracker.lines = listOf("gatt connected", "searching for the strap")
    watch()

    advanceTimeBy(ConnectWatch.PENDING_AFTER_MS)
    runCurrent()

    assertEquals(listOf("connect_pending"), types())
    assertEquals(SensorKind.POLAR_H10.wire, lastFields()["kind"])
    assertEquals(PendingTracker.ID, lastFields()["sensor_id"])
    assertEquals(ConnectWatch.PENDING_AFTER_MS, lastFields()["pending_ms"])
    assertEquals(true, lastFields()["bluetooth_on"])
    assertEquals("phone sees the strap: gatt disconnected, not listed", lastFields()["strap"])
    assertEquals(tracker.lines, lastFields()["sdk"])

    advanceTimeBy(ConnectWatch.REPORT_EVERY_MS)
    runCurrent()

    assertEquals(listOf("connect_pending", "connect_pending"), types())
    assertEquals(
        ConnectWatch.PENDING_AFTER_MS + ConnectWatch.REPORT_EVERY_MS,
        lastFields()["pending_ms"],
    )
  }

  @Test
  fun `one pending line carries only the newest part of the driver log`() = runTest {
    tracker.lines = (1..30).map { it.toString().padEnd(300, 'x') }
    watch()

    advanceTimeBy(ConnectWatch.PENDING_AFTER_MS)
    runCurrent()

    val sdk = (lastFields()["sdk"] as List<*>).map { it.toString() }
    assertTrue(sdk.sumOf { it.length } <= ConnectWatch.MAX_SDK_CHARS)
    assertEquals(tracker.lines.last(), sdk.last())
  }

  @Test
  fun `a pending connect says how long it took when it connects`() = runTest {
    watch()
    advanceTimeBy(ConnectWatch.PENDING_AFTER_MS)
    runCurrent()

    tracker.connected()
    runCurrent()

    assertEquals(listOf("connect_pending", "connect_done"), types())
    assertEquals(ConnectWatch.PENDING_AFTER_MS, lastFields()["after_ms"])
  }

  @Test
  fun `a connect that succeeds within 30 seconds writes nothing`() = runTest {
    watch()
    advanceTimeBy(10_000)
    runCurrent()

    tracker.connected()
    runCurrent()
    advanceTimeBy(ConnectWatch.REPORT_EVERY_MS)
    runCurrent()

    assertTrue(logs.isEmpty())
  }

  /** A tracker that starts connecting and stays there until the test says otherwise. */
  private class PendingTracker : Tracker {
    override val kind = SensorKind.POLAR_H10
    override val id = ID
    override val name = "Polar H10 $ID"
    override val streams = emptyList<OfferedStream>()
    override val capabilities = TrackerCapabilities(battery = false, firmware = false)

    private val _connection = MutableStateFlow(ConnectionState.CONNECTING)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private val _battery = MutableStateFlow<Int?>(null)
    override val battery: StateFlow<Int?> = _battery.asStateFlow()
    private val _firmware = MutableStateFlow<String?>(null)
    override val firmware: StateFlow<String?> = _firmware.asStateFlow()

    var lines: List<String> = emptyList()

    override fun logLinesSince(sinceElapsedMs: Long): List<String> = lines

    override fun connect() {
      _connection.value = ConnectionState.CONNECTING
    }

    fun connected() {
      _connection.value = ConnectionState.CONNECTED
    }

    override fun disconnect() {
      _connection.value = ConnectionState.DISCONNECTED
    }

    override fun startStreams(selected: Set<String>, listener: TrackerListener) = Unit

    override fun stopStreams() = Unit

    companion object {
      const val ID = "0A1B2C3D"
    }
  }
}
