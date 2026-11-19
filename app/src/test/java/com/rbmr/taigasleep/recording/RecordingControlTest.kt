package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.PolarCodec
import com.rbmr.taigasleep.tracker.TrackerCapabilities
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.StreamType
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  /** Tracker calls a test made hang must be let go, or their threads outlive the test. */
  private var release: (() -> Unit)? = null

  @After
  fun tearDown() {
    release?.invoke()
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

  @Test
  fun `a blocked control thread is recovered by a watchdog restart that writes again`() {
    val store = store()
    val dir = open(store)
    val sensor = FakeSensor()
    val host = TestRecordingHost(sensor).apply { activeSessionId = ID }
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
    awaitCondition("the recording to be attached again") { sensor.starts == 2 }
    awaitCondition("the sensor to be closed") { sensor.stopCalls >= 1 }
    sensor.deliverHr(clock)
    awaitCondition("the recording to write again") { frames(dir) > 0 }

    assertEquals(RecordingPhase.RECORDING, RecordingMeta.read(dir)!!.state)
    assertEquals(ID, host.activeSessionId)
    assertTrue(host.running)
    assertEquals("the recording is the same one", ID, RecordingMeta.read(dir)!!.recordingId)
  }

  @Test
  fun `a refused foreground start leaves an on-device night waiting for its fetch`() {
    val store = store()
    val dir = open(store, RecordingMode.ON_DEVICE)
    val host = TestRecordingHost(FakeSensor()).apply { activeSessionId = ID }
    val control = RecordingControl(store, clock, host).also { this.control = it }

    control.foregroundRefused()

    // The refused start is closed off the control thread, so wait for the whole step, not just the
    // metadata write: the marker is cleared after it.
    awaitCondition("the refused recording to be closed") {
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
        TestRecordingHost(FakeSensor()).apply {
          activeSessionId = ID
          armed = ArmedWake(ID, window, nowMs, nowMs + 60 * 60_000L)
        }
    val control = RecordingControl(store, clock, host).also { this.control = it }

    control.foregroundRefused()

    awaitCondition("the refused recording to be closed") {
      RecordingMeta.read(dir)!!.state == RecordingPhase.INTERRUPTED && host.finished
    }
    assertNull("a refused recording does not ring", host.armed)
  }

  @Test
  fun `a stop that does not complete can be forced`() {
    val store = store()
    val dir = open(store)
    val sensor = FakeSensor(stallsStart = false, stallsStop = true)
    val host = TestRecordingHost(sensor).apply { activeSessionId = ID }
    val control = RecordingControl(store, clock, host).also { this.control = it }
    release = sensor::releaseStop

    control.resume()
    awaitCondition("the recording to be attached") { host.running && sensor.starts == 1 }
    sensor.deliverHr(clock)
    awaitCondition("the data written so far") { frames(dir) > 0 }
    val written = frames(dir)

    control.stop()
    // The tracker never answers the stop, so the control thread is stuck in the stopping phase.
    assertTrue(sensor.stopEntered.await(5, TimeUnit.SECONDS))
    assertEquals(RecordingPhase.RECORDING, RecordingMeta.read(dir)!!.state)

    control.forceStop()

    awaitCondition("the recording to be forced") {
      RecordingMeta.read(dir)!!.state == RecordingPhase.FORCED_STOP && host.finished
    }
    val meta = RecordingMeta.read(dir)!!
    assertEquals(RecordingPhase.FORCED_STOP, meta.state)
    assertEquals("the end of the forced recording is written", clock.phoneNs / 1_000_000L, meta.endUtcMs)
    assertEquals("what was written before the force stop stays", written, frames(dir))
    awaitCondition("the tracker to be let go") { sensor.stopCalls >= 2 }
    assertNull("the marker is cleared so nothing resumes it", host.activeSessionId)
    assertFalse("the service is gone", host.running)
    assertEquals(Phase.IDLE, RecordingRepository.status.value.phase)
    assertNull("no half-finished card is left behind", RecordingRepository.status.value.finishing)
  }

  @Test
  fun `a start with sound adds the sound stream and runs the microphone`() {
    val store = store()
    val sensor = FakeSensor(stallsStart = false)
    val host = TestRecordingHost(sensor)
    val recorder = FakeSoundRecorder()
    val control =
        RecordingControl(store, clock, host, openSound = { _, _ -> recorder }).also {
          this.control = it
        }

    control.start(
        StartRequest(
            kind = SensorKind.POLAR_H10,
            sensorId = ID,
            sensorName = "Test strap",
            selected = setOf("hr"),
            mode = RecordingMode.STREAM,
            sound = SoundChoice.COMPRESSED,
        )
    )

    awaitCondition("the microphone to start") { recorder.startCalls == 1 }
    val dir = File(store.root, host.activeSessionId!!)
    val sound = RecordingMeta.read(dir)!!.stream(SoundChoice.SOUND_STREAM_ID)!!
    assertEquals(StreamType.SOUND, sound.type)
    assertEquals(Codec.SOUND_OPUS, sound.codec)
    assertEquals(16_000.0, sound.rateHz)
    assertEquals("sound.log", sound.file)

    control.stop()
    awaitCondition("the microphone to stop") { recorder.stopCalls >= 1 && host.finished }
  }

  @Test
  fun `a start without sound adds no sound stream and runs no microphone`() {
    val store = store()
    val sensor = FakeSensor(stallsStart = false)
    val host = TestRecordingHost(sensor)
    val recorder = FakeSoundRecorder()
    val control =
        RecordingControl(store, clock, host, openSound = { _, _ -> recorder }).also {
          this.control = it
        }

    control.start(
        StartRequest(
            kind = SensorKind.POLAR_H10,
            sensorId = ID,
            sensorName = "Test strap",
            selected = setOf("hr"),
            mode = RecordingMode.STREAM,
            sound = SoundChoice.OFF,
        )
    )

    awaitCondition("the recording to be attached") { host.activeSessionId != null && host.running }
    val dir = File(store.root, host.activeSessionId!!)
    assertNull(RecordingMeta.read(dir)!!.stream(SoundChoice.SOUND_STREAM_ID))
    assertEquals(0, recorder.startCalls)

    control.stop()
    awaitCondition("the recording to stop") { host.finished }
  }

  /** The microphone as the control flow starts and stops it. */
  private class FakeSoundRecorder : SoundRecorder {
    @Volatile var startCalls = 0
      private set
    @Volatile var stopCalls = 0
      private set

    override fun start(): Boolean {
      startCalls++
      return true
    }

    override fun stop() {
      stopCalls++
    }
  }

  /** A tracker whose first stream start never returns, like a call that hangs in the SDK. */
  private class FakeSensor(
      private val stallsStart: Boolean = true,
      private val stallsStop: Boolean = false,
  ) : Tracker {
    override val kind = SensorKind.POLAR_H10
    override val id = ID
    override val name = "Test strap"
    override val streams =
        listOf(
            OfferedStream("hr", StreamType.HEART_RATE, Codec.POLAR_HR, "hr.log", listOf(1.0)),
            OfferedStream("rr", StreamType.RR_INTERVAL, Codec.POLAR_HR, "hr.log"),
        )
    override val capabilities = TrackerCapabilities()

    private val _connection = MutableStateFlow(ConnectionState.CONNECTED)
    override val connection: StateFlow<ConnectionState> = _connection
    private val _battery = MutableStateFlow<Int?>(80)
    override val battery: StateFlow<Int?> = _battery
    private val _firmware = MutableStateFlow<String?>("1.0.0")
    override val firmware: StateFlow<String?> = _firmware

    val firstStartEntered = CountDownLatch(1)
    private val firstStartReleased = CountDownLatch(1)
    private val stopReleased = CountDownLatch(1)

    val stopEntered = CountDownLatch(1)

    @Volatile private var listener: TrackerListener? = null
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

    override fun startStreams(selected: Set<String>, listener: TrackerListener) {
      this.listener = listener
      starts++
      if (!stallsStart || starts > 1) return
      firstStartEntered.countDown()
      // The stall ends only when the interrupted control thread is let go.
      try {
        firstStartReleased.await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }

    // Best effort, like the SDK: a start that supersedes this one owns the samples again.
    override fun stopStreams() {
      stopCalls++
      if (!stallsStop) return
      stopEntered.countDown()
      try {
        stopReleased.await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }

    /** Lets a hanging stop return, as a tracker that finally answers would. */
    fun releaseStop() {
      stopReleased.countDown()
    }

    fun deliverHr(clock: TestClock) {
      val stamp = Stamp.now(clock)
      val enc =
          PolarCodec.encodeHr(
              stamp.phoneNs,
              stamp.elapsedNs,
              60,
              PolarCodec.hrFlags(true, true, true),
              intArrayOf(1_000),
          )
      listener?.onFrame(TrackerFrame("hr", stamp, enc.bytes, enc.clamped))
    }

  }
}
