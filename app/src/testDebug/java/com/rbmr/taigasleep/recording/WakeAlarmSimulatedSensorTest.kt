package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SimulatedSensor
import com.rbmr.taigasleep.tracker.SleepSensor
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wake-up window on a real recording: the simulated sensor always moves, so a window around "now"
 * rings a minute after the recording starts, and the end of the window is armed with the system for
 * the case where nothing is left running to decide.
 */
class WakeAlarmSimulatedSensorTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()
  private var control: RecordingControl? = null

  @After
  fun tearDown() {
    control?.shutdown()
    RecordingRepository.setIdle()
  }

  @Test
  fun `a window around now rings once the movement keeps up`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, windowAroundNow())) }
    // The window is armed before the streams open; samples pumped in between would reach nobody.
    await("the window to be armed and the streams open") { host.armed != null && host.refreshed > 0 }

    sensor.connect()
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    await("the alarm to ring") { host.firedAlarms == 1 }
    // The window rang for this night and will not ring again: the recording goes on.
    assertNull(host.armed)
    assertTrue(host.activeSessionId != null)
    assertEquals(1, host.firedAlarms)
  }

  @Test
  fun `the end of the window is armed with the system while the recording runs`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val window = windowAroundNow()
    val nowMs = clock.phoneNs / 1_000_000
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, window)) }
    await("the window to be armed") { host.armed != null && host.activeSessionId != null }

    val armed = host.armed!!
    assertEquals(window.bounds(nowMs).endUtcMs, armed.endUtcMs)
    assertEquals(host.activeSessionId, armed.recordingId)
  }

  @Test
  fun `stopping a recording drops its window`() {
    val host = startWithWindow()
    control!!.stop(forced = false)
    await("the recording to end") { host.activeSessionId == null }

    assertNull("a stopped recording does not ring", host.armed)
  }

  @Test
  fun `force stopping a recording drops its window`() {
    val host = startWithWindow()
    control!!.stop(forced = true)
    await("the recording to end") { host.activeSessionId == null }

    assertNull("a force-stopped recording does not ring", host.armed)
  }

  @Test
  fun `discarding a recording drops its window`() {
    val host = startWithWindow()
    control!!.discard()
    await("the recording to end") { host.activeSessionId == null }

    assertNull("a discarded recording does not ring", host.armed)
  }

  @Test
  fun `resuming a recording keeps its window armed`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val first = RecordingControl(store, clock, host).also { it.start(request(sensor, windowAroundNow())) }
    await("the window to be armed") { host.armed != null && host.activeSessionId != null }
    val armed = host.armed!!
    val id = host.activeSessionId!!

    // The process died; the marker on disk and the system alarm are still there.
    first.shutdown()
    host.running = false

    control = RecordingControl(store, clock, host)
    control!!.resume()
    await("the recording to resume") { host.running && host.activeSessionId == id }

    assertEquals("a restart must not drop the window", armed, host.armed)
  }

  @Test
  fun `a recording abandoned on resume drops its window`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val window = windowAroundNow()
    val bounds = window.bounds(clock.phoneNs / 1_000_000)
    host.armed = ArmedWake("GONE0001", window, bounds.startUtcMs, bounds.endUtcMs)
    host.activeSessionId = "GONE0001"
    control = RecordingControl(store, clock, host)

    control!!.resume()
    await("the marker to be dropped") { host.activeSessionId == null }

    assertNull("a recording that cannot be resumed does not ring", host.armed)
  }

  @Test
  fun `a recording the tracker stores itself arms no window`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    control =
        RecordingControl(store, clock, host).also {
          it.start(request(sensor, windowAroundNow(), RecordingMode.ON_DEVICE))
        }
    await("the tracker to hold the night") { sensor.storedEntryCount() == 1 }

    assertNull(host.armed)
    assertEquals(0, host.firedAlarms)
  }

  @Test
  fun `a window without a chosen signal still arms its end`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val window = WakeWindow(6 * 60 + 30, 30, heartRate = false, movement = false)
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, window)) }
    await("the window to be armed") { host.armed != null }

    val nowMs = clock.phoneNs / 1_000_000
    assertEquals(window.bounds(nowMs).endUtcMs, host.armed!!.endUtcMs)
    assertEquals("nothing may ring before the end", 0, host.firedAlarms)
  }

  @Test
  fun `moving the window while recording arms the new one and shows it`() {
    val host = startWithWindow()
    val id = host.activeSessionId!!
    val later = WakeWindow((windowAroundNow().startMinute + 60) % (24 * 60), 20, heartRate = false, movement = true)

    control!!.changeWake(later)
    await("the new window to be armed and shown") {
      host.armed?.window == later && RecordingRepository.status.value.wake?.window == later
    }

    val nowMs = clock.phoneNs / 1_000_000
    assertEquals(later.bounds(nowMs).endUtcMs, host.armed!!.endUtcMs)
    assertEquals(id, host.armed!!.recordingId)
    assertEquals("the screen shows the new window", host.armed, RecordingRepository.status.value.wake)
    assertTrue(eventsOf(id).contains("smart alarm"))
  }

  @Test
  fun `switching the alarm off while recording drops it and nothing rings`() {
    val host = startWithWindow()
    val sensor = host.sensor as SimulatedSensor

    control!!.changeWake(null)
    await("the window to be dropped") { host.armed == null }
    sensor.connect()
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    var accFrames = 0
    FrameLog.scan(java.io.File(tmp.root.listFiles()!!.single(), "${host.activeSessionId}/acc.log")) { accFrames++ }
    assertTrue("movement reached the recording", accFrames > 0)
    assertEquals(0, host.firedAlarms)
    assertNull(RecordingRepository.status.value.wake)
    assertTrue("the recording goes on", host.activeSessionId != null)
  }

  @Test
  fun `switching the alarm on while recording rings for that window`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, null)) }
    await("the recording to start") { host.activeSessionId != null && host.refreshed > 0 }
    assertNull(host.armed)

    control!!.changeWake(windowAroundNow())
    await("the window to be armed") { host.armed != null }
    sensor.connect()
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    await("the alarm to ring") { host.firedAlarms == 1 }
    assertTrue("the screen says it rang", RecordingRepository.status.value.wakeRangAtUtcMs != null)
  }

  @Test
  fun `a window moved after it rang can ring again`() {
    val host = startWithWindow()
    val sensor = host.sensor as SimulatedSensor
    sensor.connect()
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }
    await("the first alarm") { host.firedAlarms == 1 }

    control!!.changeWake(windowAroundNow())
    await("the window to be armed again") { host.armed != null }
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    await("the second alarm") { host.firedAlarms == 2 }
  }

  private fun eventsOf(id: String): String =
      java.io.File(tmp.root.listFiles()!!.single(), "$id/events.jsonl").readText()

  /** A window that is open now: ten minutes before this moment and twenty after it. */
  private fun windowAroundNow(): WakeWindow {
    val local = Instant.ofEpochMilli(clock.phoneNs / 1_000_000).atZone(ZoneId.systemDefault())
    fun minute(time: java.time.ZonedDateTime) = time.hour * 60 + time.minute
    return WakeWindow(
        startMinute = minute(local.minusMinutes(10)),
        lengthMinutes = 30,
        heartRate = false,
        movement = true,
    )
  }

  /** Starts one streamed recording with a window around now and returns its host. */
  private fun startWithWindow(): FakeHost {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, windowAroundNow())) }
    await("the window to be armed and the streams open") {
      host.armed != null && host.activeSessionId != null && host.refreshed > 0
    }
    return host
  }

  private fun request(
      sensor: SimulatedSensor,
      window: WakeWindow?,
      mode: RecordingMode = RecordingMode.STREAM,
  ) =
      StartRequest(
          kind = SensorKind.SIMULATED,
          sensorId = sensor.id,
          sensorName = sensor.name,
          ecg = false,
          acc = true,
          mode = mode,
          wake = window,
      )

  private fun await(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(10)
    }
    assertTrue("timed out waiting for $what", condition())
  }

  private class FakeLock : RecordingWakeLock {
    override var isHeld = false
      private set

    override fun acquire(timeoutMs: Long) {
      isHeld = true
    }

    override fun release() {
      isHeld = false
    }
  }

  private class FakeHost(val sensor: SleepSensor) : RecordingHost {
    override var activeSessionId: String? = null
    override var heartbeatMs = 0L
    var armed: ArmedWake? = null
    var firedAlarms = 0
    var running = false

    override fun obtain(kind: SensorKind, id: String, name: String): SleepSensor = sensor

    override fun mayStart(mode: RecordingMode) = true

    override fun readinessSummary() = "test readiness"

    override fun newWakeLock(): RecordingWakeLock = FakeLock()

    override fun scheduleWatchdog() = Unit

    override fun cancelWatchdog() = Unit

    override fun wakeWindow(): ArmedWake? = armed

    override fun armWake(armed: ArmedWake) {
      this.armed = armed
    }

    override fun disarmWake() {
      armed = null
    }

    override fun fireWakeAlarm(reason: WakeReason) {
      firedAlarms++
    }

    override fun markRunning(running: Boolean) {
      this.running = running
    }

    override val device =
        RecordingDevice(
            manufacturer = "JVM",
            model = "test",
            android = "0",
            sdk = 0,
            appVersion = "test",
            appVersionCode = 1,
        )

    override fun message(message: RecordingMessage) = message.name

    /** Counts the recorder's last step of a start, which comes after the streams are open. */
    @Volatile var refreshed = 0

    override fun refreshRecordings() {
      refreshed++
    }

    override fun onState(state: RecordingState) = Unit

    override fun onRecordingFinished(recordingId: String) = Unit

    override fun onFetchCompleted(recordingId: String) = Unit

    override fun finishService() = Unit
  }
}
