package com.rbmr.taigasleep.tracker.hrs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The heart-rate service tracker's link handling, without a radio. */
@RunWith(RobolectricTestRunner::class)
class BleHrsTrackerTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun anUnexpectedDropIsClosedNotedAndRetried() {
    val seam = FakeSeam()
    val scheduler = FakeScheduler()
    val notes = mutableListOf<String>()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, scheduler)
    tracker.startStreams(setOf("hr"), notesListener(notes))

    assertEquals(1, seam.handles.size)
    val first = seam.handles.single()
    first.connect()
    assertTrue(first.discovered)

    first.drop(status = 133)

    assertEquals(ConnectionState.CONNECTING, tracker.connection.value)
    assertTrue(first.closed)
    assertTrue("disconnect" in notes)
    assertTrue(scheduler.pending)

    scheduler.run()

    assertEquals(2, seam.handles.size)
    assertTrue("reconnect" in notes)
    assertEquals(ConnectionState.CONNECTING, tracker.connection.value)

    // A stale callback from the dead connection cannot join the new session.
    first.connect()
    assertEquals(ConnectionState.CONNECTING, tracker.connection.value)

    tracker.disconnect()
    assertFalse(scheduler.pending)
    assertEquals(ConnectionState.DISCONNECTED, tracker.connection.value)
  }

  @Test
  fun connectingAgainWhileConnectingLeavesOneConnection() {
    val seam = FakeSeam()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, FakeScheduler())

    tracker.connect()
    tracker.connect()

    assertEquals(ConnectionState.CONNECTING, tracker.connection.value)
    assertEquals(1, seam.handles.size)
  }

  @Test
  fun stoppingTheStreamsStopsTheRetries() {
    val seam = FakeSeam()
    val scheduler = FakeScheduler()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, scheduler)
    tracker.startStreams(setOf("hr"), notesListener(mutableListOf()))

    seam.handles.single().connect()
    seam.handles.single().drop(status = 0)
    assertTrue(scheduler.pending)

    tracker.stopStreams()

    assertFalse(scheduler.pending)
    assertEquals(ConnectionState.DISCONNECTED, tracker.connection.value)
  }

  @Test
  fun aLinkThatCannotBeOpenedIsRetried() {
    val seam = FakeSeam().apply { failFirst = true }
    val scheduler = FakeScheduler()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, scheduler)
    tracker.startStreams(setOf("hr"), notesListener(mutableListOf()))

    assertEquals(ConnectionState.CONNECTING, tracker.connection.value)
    assertTrue(scheduler.pending)

    scheduler.run()

    assertEquals(2, seam.attempts)
    assertTrue(scheduler.pending.not())
  }

  @Test
  fun theSetupOperationsAreSerialised() {
    val seam = FakeSeam()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, FakeScheduler())
    val frames = mutableListOf<TrackerFrame>()
    tracker.startStreams(setOf("hr"), frameListener(frames))
    val handle = seam.handles.single()
    handle.hasBattery = true
    handle.hasFirmware = true

    handle.connect()
    handle.discover()

    assertEquals(1, handle.notificationWrites)
    assertEquals(0, handle.batteryReads)
    assertEquals(0, handle.firmwareReads)

    handle.descriptorWritten()
    assertEquals(1, handle.batteryReads)
    assertEquals(0, handle.firmwareReads)

    handle.characteristicRead(Hrs.BATTERY_LEVEL, byteArrayOf(80))
    assertEquals(1, handle.firmwareReads)
  }

  @Test
  fun aFailedNotificationEnableIsReportedAndRetried() {
    val seam = FakeSeam()
    val scheduler = FakeScheduler()
    val errors = mutableListOf<String>()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, scheduler)
    tracker.startStreams(setOf("hr"), errorListener(errors))
    val handle = seam.handles.single()
    handle.failNotifications = true

    handle.connect()
    handle.discover()

    assertTrue(errors.any { it.contains("could not enable") })
    assertTrue(handle.closed)
    assertTrue(scheduler.pending)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `a disconnect wait ends only when the radio reports the drop`() = runTest {
    val seam = FakeSeam()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, FakeScheduler())
    tracker.startStreams(setOf("hr"), notesListener(mutableListOf()))
    val handle = seam.handles.single()

    val wait = async { tracker.disconnectAndWait(5_000) }
    runCurrent()
    assertFalse("the wait ended before the callback", wait.isCompleted)

    handle.drop(status = 0)
    advanceUntilIdle()
    assertTrue(wait.await())
  }

  @Test
  fun framesReachTheListenerThroughTheSeam() {
    val seam = FakeSeam()
    val frames = mutableListOf<TrackerFrame>()
    val tracker = BleHrsTracker(context, "AA:BB", "Strap", seam, FakeScheduler())
    tracker.startStreams(setOf("hr"), frameListener(frames))
    val handle = seam.handles.single()
    handle.connect()
    handle.discover()
    handle.notifyMeasurement(byteArrayOf(0x16, 60, 0x00, 0x02))

    assertEquals(1, frames.size)
  }

  private fun frameListener(frames: MutableList<TrackerFrame>) =
      object : TrackerListener {
        override fun onFrame(frame: TrackerFrame) {
          frames += frame
        }

        override fun onError(message: String) = Unit

        override fun onNote(type: String, message: String) = Unit
      }

  private fun errorListener(errors: MutableList<String>) =
      object : TrackerListener {
        override fun onFrame(frame: TrackerFrame) = Unit

        override fun onError(message: String) {
          errors += message
        }

        override fun onNote(type: String, message: String) = Unit
      }

  private fun notesListener(notes: MutableList<String>) =
      object : TrackerListener {
        override fun onFrame(frame: TrackerFrame) = Unit

        override fun onError(message: String) = Unit

        override fun onNote(type: String, message: String) {
          notes += type
        }
      }

  private class FakeScheduler : RetryScheduler {
    var pending = false
      private set
    private var action: (() -> Unit)? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
      pending = true
      this.action = action
    }

    override fun cancel() {
      pending = false
      action = null
    }

    fun run() {
      val run = action
      pending = false
      action = null
      run?.invoke()
    }
  }

  private class FakeSeam : GattSeam {
    val handles = mutableListOf<FakeHandle>()
    var attempts = 0
      private set
    var failFirst = false

    override fun isEnabled(): Boolean = true

    override fun connect(address: String, onEvent: (GattEvent) -> Unit): GattHandle? {
      attempts++
      if (failFirst && attempts == 1) return null
      return FakeHandle(onEvent).also { handles += it }
    }
  }

  private class FakeHandle(private val onEvent: (GattEvent) -> Unit) : GattHandle {
    var discovered = false
      private set
    var closed = false
      private set
    var notifications = false
      private set
    var failNotifications = false
    var hasBattery = false
    var hasFirmware = false
    var notificationWrites = 0
      private set
    var batteryReads = 0
      private set
    var firmwareReads = 0
      private set

    fun descriptorWritten(status: Int = 0) =
        onEvent(GattEvent.DescriptorWritten(this, Hrs.CCCD, status))

    fun characteristicRead(characteristic: UUID, value: ByteArray, status: Int = 0) =
        onEvent(GattEvent.CharacteristicRead(this, characteristic, value, status))

    fun connect() = onEvent(GattEvent.Connected(this))

    fun discover() = onEvent(GattEvent.ServicesDiscovered(this, 0))

    fun drop(status: Int) = onEvent(GattEvent.Disconnected(this, status))

    fun notifyMeasurement(value: ByteArray) =
        onEvent(GattEvent.CharacteristicChanged(this, Hrs.MEASUREMENT, value))

    override fun hasService(service: UUID): Boolean = service == Hrs.SERVICE

    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        (service == Hrs.BATTERY_SERVICE && characteristic == Hrs.BATTERY_LEVEL && hasBattery) ||
            (service == Hrs.DEVICE_INFORMATION &&
                characteristic == Hrs.FIRMWARE_REVISION &&
                hasFirmware)

    override fun discoverServices(): Boolean {
      discovered = true
      return true
    }

    override fun setNotifications(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        enable: Boolean,
    ): Boolean {
      if (enable) notificationWrites++
      notifications = enable && !failNotifications
      return notifications
    }

    override fun readCharacteristic(service: UUID, characteristic: UUID): Boolean {
      when (characteristic) {
        Hrs.BATTERY_LEVEL -> batteryReads++
        Hrs.FIRMWARE_REVISION -> firmwareReads++
      }
      return true
    }

    override fun disconnect() = Unit

    override fun close() {
      closed = true
    }
  }
}
