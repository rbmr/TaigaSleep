package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SimulatedSensor
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** How long a wait may last before the test fails instead of hanging the build. */
private const val HANG_GUARD_MS = 60_000L

/** What the fake host did, in the order the recording did it: the tests wait on these, not on time. */
private enum class HostSignal {
  DISARMED,
  REFRESHED,
  FINISHED,
}

/**
 * A wake-up window on a real recording: the simulated sensor always moves, so a window around "now"
 * rings a minute after the recording starts, and the end of the window is armed with the system for
 * the case where nothing is left running to decide.
 *
 * Time only moves through [TestClock] and the sensor's [SimulatedSensor.pump]; the tests wait for a
 * signal of the code under test, never for a duration or a polled condition.
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
    val mark = host.mark()
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, windowAroundNow())) }
    // The last step of a start: the window is armed and the streams are open.
    host.awaitAfter(mark, HostSignal.REFRESHED)

    sensor.connect()
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    // The trigger is handed to the control thread; the ringing service then reports the sound.
    awaitStatus { host.firedAlarms == 1 }
    control!!.wakeRang(WakeAlarmService.SOURCE_SIGNAL)
    awaitStatus { it.wakeRangAtUtcMs != null }
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
    val mark = host.mark()
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, window)) }
    host.awaitAfter(mark, HostSignal.REFRESHED)
    assertTrue(host.armed != null)
    assertTrue(host.activeSessionId != null)

    val armed = host.armed!!
    assertEquals(window.bounds(nowMs).endUtcMs, armed.endUtcMs)
    assertEquals(host.activeSessionId, armed.recordingId)
  }

  @Test
  fun `stopping a recording drops its window`() {
    val host = startWithWindow()
    val mark = host.mark()
    control!!.stop()
    host.awaitAfter(mark, HostSignal.DISARMED)
    assertTrue("the recording ended", host.activeSessionId == null)

    assertNull("a stopped recording does not ring", host.armed)
  }

  @Test
  fun `force stopping a recording drops its window`() {
    val host = startWithWindow()
    val mark = host.mark()
    control!!.forceStop()
    host.awaitAfter(mark, HostSignal.DISARMED)
    assertTrue("the recording ended", host.activeSessionId == null)

    assertNull("a force-stopped recording does not ring", host.armed)
  }

  @Test
  fun `discarding a recording drops its window`() {
    val host = startWithWindow()
    val mark = host.mark()
    control!!.discard()
    host.awaitAfter(mark, HostSignal.DISARMED)
    assertTrue("the recording ended", host.activeSessionId == null)

    assertNull("a discarded recording does not ring", host.armed)
  }

  @Test
  fun `resuming a recording keeps its window armed`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val started = host.mark()
    val first = RecordingControl(store, clock, host).also { it.start(request(sensor, windowAroundNow())) }
    host.awaitAfter(started, HostSignal.REFRESHED)
    assertTrue(host.armed != null)
    val armed = host.armed!!
    val id = host.activeSessionId!!

    // The process died; the marker on disk and the system alarm are still there.
    first.shutdown()
    host.running = false

    val resumed = host.mark()
    control = RecordingControl(store, clock, host)
    control!!.resume()
    host.awaitAfter(resumed, HostSignal.REFRESHED)
    assertTrue("the recording resumed", host.running)
    assertEquals(id, host.activeSessionId)

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

    val resumed = host.mark()
    control!!.resume()
    host.awaitAfter(resumed, HostSignal.DISARMED)
    assertTrue("the marker was dropped", host.activeSessionId == null)

    assertNull("a recording that cannot be resumed does not ring", host.armed)
  }

  @Test
  fun `a recording the tracker stores itself arms no window`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val mark = host.mark()
    control =
        RecordingControl(store, clock, host).also {
          it.start(request(sensor, windowAroundNow(), RecordingMode.ON_DEVICE))
        }
    // The tracker answers at once and the service lets go of it: the last step of that is the end.
    host.awaitAfter(mark, HostSignal.FINISHED)
    assertEquals(1, sensor.storedEntryCount())

    assertNull(host.armed)
    assertEquals(0, host.firedAlarms)
  }

  @Test
  fun `a window without a chosen signal still arms its end`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val window = WakeWindow(6 * 60 + 30, 30, heartRate = false, movement = false)
    val mark = host.mark()
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, window)) }
    host.awaitAfter(mark, HostSignal.REFRESHED)
    assertTrue(host.armed != null)

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
    // The screen is updated after the end alarm and the live watch: once it shows the new window,
    // both follow it.
    awaitStatus { it.wake?.window == later }

    val nowMs = clock.phoneNs / 1_000_000
    assertEquals(later, host.armed!!.window)
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
    // Off is published after the live watch is dropped: samples pumped from here reach no alarm.
    awaitStatus { it.wake == null }
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
    val mark = host.mark()
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, null)) }
    host.awaitAfter(mark, HostSignal.REFRESHED)
    assertNull(host.armed)

    control!!.changeWake(windowAroundNow())
    // The screen shows the window after the watch has it: samples pumped from here reach the alarm.
    awaitStatus { it.wake != null }
    sensor.connect()
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    awaitStatus { host.firedAlarms == 1 }
    control!!.wakeRang(WakeAlarmService.SOURCE_SIGNAL)
    awaitStatus { it.wakeRangAtUtcMs != null }
    assertTrue("the screen says it rang", RecordingRepository.status.value.wakeRangAtUtcMs != null)
    assertEquals(1, host.firedAlarms)
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
    awaitStatus { host.firedAlarms == 1 }
    control!!.wakeRang(WakeAlarmService.SOURCE_SIGNAL)
    awaitStatus { it.wakeRangAtUtcMs != null }

    control!!.changeWake(windowAroundNow())
    // The move is applied when the screen shows an armed, not yet rung, window.
    awaitStatus { it.wake != null && it.wakeRangAtUtcMs == null }
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    awaitStatus { host.firedAlarms == 2 }
    control!!.wakeRang(WakeAlarmService.SOURCE_SIGNAL)
    awaitStatus { it.wakeRangAtUtcMs != null }
    assertEquals(2, host.firedAlarms)
  }

  @Test
  fun `a resumed recording still says when its alarm rang`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    val started = host.mark()
    val first = RecordingControl(store, clock, host).also { it.start(request(sensor, windowAroundNow())) }
    host.awaitAfter(started, HostSignal.REFRESHED)
    sensor.connect()
    repeat(1_200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }
    awaitStatus { host.firedAlarms == 1 }
    first.wakeRang(WakeAlarmService.SOURCE_SIGNAL)
    val rang = awaitStatus { it.wakeRangAtUtcMs != null }.wakeRangAtUtcMs!!
    val id = host.activeSessionId!!

    // The process died: only the recording on disk is left to say that the alarm rang.
    first.shutdown()
    host.running = false
    RecordingRepository.setIdle()
    val resumed = host.mark()
    control = RecordingControl(store, clock, host)
    control!!.resume()
    host.awaitAfter(resumed, HostSignal.REFRESHED)
    assertTrue("the recording resumed", host.running)
    assertEquals(id, host.activeSessionId)

    // The screen shows the minute; the event and the screen read the clock a moment apart.
    val back = awaitStatus { it.wakeRangAtUtcMs != null }.wakeRangAtUtcMs!!
    assertTrue("the ring is read back", kotlin.math.abs(back - rang) < 60_000)
    assertNull(RecordingRepository.status.value.wake)
  }

  @Suppress("FunctionNaming")
  @Test
  fun `the end of the window is noted in the recording when the sound started`() {
    val host = startWithWindow()
    val id = host.activeSessionId!!

    control!!.wakeRang(WakeAlarmService.SOURCE_WINDOW_END)

    // The event is written before the screen says the alarm rang.
    awaitStatus { it.wakeRangAtUtcMs != null }
    assertTrue(eventsOf(id).contains("\"wake_rang\""))
    assertTrue(eventsOf(id).contains("the alarm window ended"))
  }

  /**
   * Waits until the screen carries the state the code under test publishes last, so whatever the
   * test reads next is already visible. It suspends on the repository: the recording decides when
   * the wait ends. [HANG_GUARD_MS] is only a guard so that a regression fails the test with a
   * message instead of hanging the build; a run needs a fraction of a second, and no result
   * depends on how long it takes.
   */
  private fun awaitStatus(predicate: (CaptureStatus) -> Boolean): CaptureStatus = runBlocking {
    withTimeoutOrNull(HANG_GUARD_MS) { RecordingRepository.status.first(predicate) }
        ?: error("the recording never reached the state the test waits for")
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
    val mark = host.mark()
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, windowAroundNow())) }
    host.awaitAfter(mark, HostSignal.REFRESHED)
    assertTrue("the window is armed", host.armed != null)
    assertTrue("the streams are open", host.activeSessionId != null)
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
          selected = if (mode == RecordingMode.ON_DEVICE) setOf("hr") else setOf("hr", "acc"),
          mode = mode,
          wake = window,
      )

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

  private class FakeHost(val sensor: Tracker) : RecordingHost {
    @Volatile override var activeSessionId: String? = null
    @Volatile override var heartbeatMs = 0L
    @Volatile var armed: ArmedWake? = null
    @Volatile var firedAlarms = 0
    @Volatile var running = false

    private val signals = LinkedBlockingQueue<Pair<Long, HostSignal>>()
    private val issued = AtomicLong()

    /** Where the host's history stands now: a wait looks only at what comes after it. */
    fun mark(): Long = issued.get()

    /**
     * Blocks until this host reports [signal] after [mark]. The recording decides when that is;
     * [HANG_GUARD_MS] only turns a regression into a failure instead of a hung build.
     */
    fun awaitAfter(mark: Long, signal: HostSignal) {
      while (true) {
        val (at, seen) =
            signals.poll(HANG_GUARD_MS, TimeUnit.MILLISECONDS)
                ?: error("the host never reported $signal")
        if (at > mark && seen == signal) return
      }
    }

    private fun signal(signal: HostSignal) {
      signals.put(issued.incrementAndGet() to signal)
    }

    override fun obtain(kind: SensorKind, id: String, name: String): Tracker = sensor

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
      signal(HostSignal.DISARMED)
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
      signal(HostSignal.REFRESHED)
    }

    override fun onState(state: RecordingState) = Unit

    override fun onRecordingFinished(recordingId: String) = Unit

    override fun onFetchCompleted(recordingId: String) = Unit

    override fun finishService() {
      signal(HostSignal.FINISHED)
    }
  }
}
