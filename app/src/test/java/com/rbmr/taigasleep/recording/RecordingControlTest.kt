package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.SensorCapabilities
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.StreamConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The service's control flow, without Android: a real writer on disk, a fake tracker and the fake
 * clock. It covers the two recovery paths that cannot be reached by a pure decision table: a
 * watchdog restart whose control thread is blocked, and a refused foreground start.
 */
private const val ID = "TEST0001"

class RecordingControlTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()
  private var control: RecordingControl? = null

  @After
  fun tearDown() {
    control?.shutdown()
  }

  private fun store() = RecordingStore(tmp.newFolder())

  private fun meta(mode: RecordingMode) =
      RecordingMeta(
          recordingId = ID,
          state = RecordingPhase.RECORDING,
          mode = mode,
          startUtcMs = clock.phoneNs / 1_000_000L,
          endUtcMs = null,
          timezone = "UTC",
          appVersion = "test",
          appVersionCode = 1,
          phoneManufacturer = "JVM",
          phoneModel = "test",
          android = "0",
          sdk = 0,
          sensorKind = SensorKind.POLAR_H10.wire,
          sensorId = ID,
          sensorName = "Test strap",
          sensorFirmware = "1.0.0",
          sensorBatteryAtStart = 80,
          config = RecordingConfig(ecg = false, acc = false),
      )

  /** An open recording whose writer died with the process: the service has to resume it. */
  private fun open(store: RecordingStore, mode: RecordingMode = RecordingMode.STREAM): File {
    val writer = RecordingWriter.create(store.root, meta(mode), clock)
    writer.close()
    return writer.dir
  }

  private fun frames(dir: File): Long {
    var frames = 0L
    FrameLog.scan(File(dir, "hr.log")) { frames++ }
    return frames
  }

  private fun await(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(10)
    }
    assertTrue("timed out waiting for $what", condition())
  }

  @Test
  fun `a blocked control thread is recovered by a watchdog restart that writes again`() {
    val store = store()
    val dir = open(store)
    val sensor = FakeSensor()
    val host = FakeHost(sensor).apply { activeSessionId = ID }
    val control = RecordingControl(store, clock, host).also { this.control = it }

    control.resume()
    // The tracker call the control thread is running never returns: that is the stall the watchdog
    // sees as a stopped heartbeat.
    assertTrue(sensor.firstStartEntered.await(5, TimeUnit.SECONDS))
    assertEquals(1, sensor.starts)
    assertEquals(0L, frames(dir))

    control.restart()

    // The restart is not queued behind the blocked thread: it closes the session, drops that thread
    // and opens the same recording on a new one.
    await("the recording to be attached again") { sensor.starts == 2 }
    await("the sensor to be closed") { sensor.stopCalls >= 1 }
    sensor.deliverHr(clock)
    await("the recording to write again") { frames(dir) > 0 }

    assertEquals(RecordingPhase.RECORDING, RecordingMeta.read(dir)!!.state)
    assertEquals(ID, host.activeSessionId)
    assertTrue(host.running)
    assertEquals("the recording is the same one", ID, RecordingMeta.read(dir)!!.recordingId)
  }

  @Test
  fun `a refused foreground start leaves an on-device night waiting for its fetch`() {
    val store = store()
    val dir = open(store, RecordingMode.ON_DEVICE)
    val host = FakeHost(FakeSensor()).apply { activeSessionId = ID }
    val control = RecordingControl(store, clock, host).also { this.control = it }

    control.foregroundRefused()

    // The refused start is closed off the control thread, so wait for the whole step, not just the
    // metadata write: the marker is cleared after it.
    await("the refused recording to be closed") {
      RecordingMeta.read(dir)!!.state == RecordingPhase.FETCH_PENDING && host.finished
    }
    val meta = RecordingMeta.read(dir)!!
    assertTrue("the tracker still holds the night", meta.fetchPending)
    // The fetch sets the end; the tracker's copy is the rest of the night.
    assertNull(meta.endUtcMs)
    assertEquals(ID, store.fetchPending()?.recordingId)
    assertNull("the marker is cleared so nothing resumes it", host.activeSessionId)
    assertTrue(host.finished)
    // It is not a finished recording: no upload is queued for half a night.
    assertEquals(emptyList<String>(), host.finishedRecordings)
    assertEquals(ID, RecordingRepository.status.value.fetchPending?.recordingId)
  }

  @Test
  fun `a refused foreground start drops the window of a streamed night`() {
    val store = store()
    val dir = open(store)
    val nowMs = clock.phoneNs / 1_000_000
    val window = WakeWindow(6 * 60 + 30, 30)
    val host =
        FakeHost(FakeSensor()).apply {
          activeSessionId = ID
          armed = ArmedWake(ID, window, nowMs, nowMs + 60 * 60_000L)
        }
    val control = RecordingControl(store, clock, host).also { this.control = it }

    control.foregroundRefused()

    await("the refused recording to be closed") {
      RecordingMeta.read(dir)!!.state == RecordingPhase.INTERRUPTED && host.finished
    }
    assertNull("a refused recording does not ring", host.armed)
  }

  /** A tracker whose first stream start never returns, like a call that hangs in the SDK. */
  private class FakeSensor : SleepSensor {
    override val kind = SensorKind.POLAR_H10
    override val id = ID
    override val name = "Test strap"
    override val capabilities = SensorCapabilities(ecg = false, acc = false, rr = true)

    private val _connection = MutableStateFlow(ConnectionState.CONNECTED)
    override val connection: StateFlow<ConnectionState> = _connection
    private val _battery = MutableStateFlow<Int?>(80)
    override val battery: StateFlow<Int?> = _battery
    private val _firmware = MutableStateFlow<String?>("1.0.0")
    override val firmware: StateFlow<String?> = _firmware

    val firstStartEntered = CountDownLatch(1)
    private val firstStartReleased = CountDownLatch(1)

    @Volatile private var listener: SampleListener? = null
    @Volatile var starts = 0
      private set
    @Volatile var stopCalls = 0
      private set

    override fun connect() {
      _connection.value = ConnectionState.CONNECTED
    }

    override fun disconnect() {
      _connection.value = ConnectionState.DISCONNECTED
    }

    override fun startStreams(config: StreamConfig, listener: SampleListener) {
      this.listener = listener
      starts++
      if (starts == 1) {
        firstStartEntered.countDown()
        // The stall ends only when the interrupted control thread is let go.
        try {
          firstStartReleased.await()
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
    }

    // Best effort, like the SDK: a start that supersedes this one owns the samples again.
    override fun stopStreams() {
      stopCalls++
    }

    fun deliverHr(clock: TestClock) {
      listener?.onHr(
          Stamp.now(clock),
          HrSample(
              60,
              contactSupported = true,
              contact = true,
              rrAvailable = true,
              rrMs = intArrayOf(1_000),
          ),
      )
    }

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

  private class FakeHost(private val sensor: SleepSensor) : RecordingHost {
    override var activeSessionId: String? = null
    override var heartbeatMs = 0L
    var running = false
    var finished = false
    var state: RecordingState? = null
    val finishedRecordings = mutableListOf<String>()

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

    var armed: ArmedWake? = null
    var firedAlarms = 0

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

    override fun refreshRecordings() = Unit

    override fun onState(state: RecordingState) {
      this.state = state
    }

    override fun onRecordingFinished(recordingId: String) {
      finishedRecordings += recordingId
    }

    override fun onFetchCompleted(recordingId: String) = Unit

    override fun finishService() {
      finished = true
    }
  }

}
