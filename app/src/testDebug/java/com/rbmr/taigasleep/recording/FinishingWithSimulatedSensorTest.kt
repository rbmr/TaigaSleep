package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SimulatedSensor
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.ui.home.HomeUiState
import com.rbmr.taigasleep.ui.home.SensorPicker
import com.rbmr.taigasleep.ui.home.homeState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stopping a recording with the [SimulatedSensor] and the real control flow: Home must show the
 * finishing steps of that mode and end on the done card, for a streamed night and for one the
 * tracker stored itself.
 */
class FinishingWithSimulatedSensorTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()
  private var control: RecordingControl? = null
  private var watcher: Job? = null
  private val scope = CoroutineScope(Dispatchers.Default)
  private val seen = CopyOnWriteArrayList<FinishingState>()

  @Before
  fun reset() {
    RecordingRepository.setIdle()
    watcher =
        scope.launch {
          // Every finishing state the service publishes, so the steps it passed through can be seen.
          RecordingRepository.status.collect { status ->
            status.finishing?.let { seen += it }
          }
        }
  }

  @After
  fun tearDown() {
    watcher?.cancel()
    scope.cancel()
    control?.shutdown()
    RecordingRepository.setIdle()
  }

  @Test
  fun `stopping a streamed night shows the steps and ends on the done card`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, RecordingMode.STREAM)) }
    await("the streamed recording to run") { host.activeSessionId != null }

    sensor.connect()
    repeat(100) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    control!!.stop(forced = false)
    await("the done card") { RecordingRepository.status.value.finishing?.done == true }

    val finishing = RecordingRepository.status.value.finishing!!
    val steps =
        listOf(
            FinishingState.Step.STOPPING_TRACKER,
            FinishingState.Step.SAVING,
            FinishingState.Step.DONE,
        )
    assertEquals(steps, finishing.steps)
    assertEquals(FinishingState.Step.DONE, finishing.step)
    assertNull(host.activeSessionId)

    // Home draws the done card for this state, with the link to the recording it is about.
    val home = homeFor(RecordingRepository.status.value)
    assertTrue(home is HomeUiState.Finishing)
    assertTrue((home as HomeUiState.Finishing).done)
    assertEquals(finishing.recordingId, home.finishing.recordingId)

    assertEquals(
        RecordingPhase.STOPPED,
        RecordingMeta.read(store.dir(finishing.recordingId))!!.state,
    )
    assertTrue(finishing.recordingId in host.finishedRecordings)
    await("the service to publish the last step") { seen.lastOrNull()?.done == true }
  }

  @Test
  fun `stopping an on-device night fetches it, shows the entry and then the done card`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, RecordingMode.ON_DEVICE)) }
    await("the tracker to hold the night") { sensor.storedEntryCount() == 1 }

    sensor.connect()
    // Enough beats that the import reports the entry both at its start and while it is stored.
    repeat(7_000) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }
    assertTrue(sensor.storedEntries().single().rrMs.isNotEmpty())

    sensor.fetchDelayMs = 150
    control!!.stop(forced = false)
    await("the done card") { RecordingRepository.status.value.finishing?.done == true }

    val finishing = RecordingRepository.status.value.finishing!!
    assertEquals(
        listOf(
            FinishingState.Step.STOPPING_TRACKER,
            FinishingState.Step.FETCHING,
            FinishingState.Step.SAVING,
            FinishingState.Step.DONE,
        ),
        finishing.steps,
    )
    val fetched = seen.filter { it.step == FinishingState.Step.FETCHING }
    assertTrue("the fetch step should be shown: $seen", fetched.isNotEmpty())
    assertTrue(
        "it should name the entry: ${fetched.map { it.fetch }}",
        fetched.any { it.fetch?.entries == 1 },
    )
    assertTrue(
        "it should count the samples stored so far: ${fetched.map { it.fetch }}",
        fetched.any { (it.fetch?.samples ?: 0) > 0 },
    )

    val home = homeFor(RecordingRepository.status.value)
    assertTrue((home as HomeUiState.Finishing).done)
    assertEquals(
        RecordingPhase.STOPPED,
        RecordingMeta.read(store.dir(finishing.recordingId))!!.state,
    )
    assertEquals("the tracker's copy is gone", 0, sensor.storedEntryCount())
  }

  @Test
  fun `a failed fetch shows why and the retry finishes the night`() {
    val store = RecordingStore(tmp.newFolder())
    val sensor = SimulatedSensor(clock, autoRun = false, disconnectEveryMs = 0)
    val host = FakeHost(sensor)
    control = RecordingControl(store, clock, host).also { it.start(request(sensor, RecordingMode.ON_DEVICE)) }
    await("the tracker to hold the night") { sensor.storedEntryCount() == 1 }

    sensor.connect()
    repeat(200) {
      clock.advanceMs(100)
      sensor.pump(clock.elapsedNs())
    }

    sensor.failFetchAfterEntries = 0
    control!!.stop(forced = false)
    await("the fetch-pending state") { RecordingRepository.status.value.fetchPending != null }

    val pending = RecordingRepository.status.value.fetchPending!!
    assertTrue(pending.error!!.contains("simulated tracker read failure"))
    val pendingHome = homeFor(RecordingRepository.status.value)
    assertTrue(pendingHome is HomeUiState.FetchPending)
    assertEquals(pending.error, pendingHome.error)

    sensor.failFetchAfterEntries = null
    control!!.fetchPending(pending.recordingId)
    await("the retry to finish") { RecordingRepository.status.value.finishing?.done == true }

    val finishing = RecordingRepository.status.value.finishing!!
    assertEquals(pending.recordingId, finishing.recordingId)
    assertEquals(
        RecordingPhase.STOPPED,
        RecordingMeta.read(store.dir(pending.recordingId))!!.state,
    )
    assertEquals("the tracker's copy is gone", 0, sensor.storedEntryCount())
  }

  private fun request(sensor: SimulatedSensor, mode: RecordingMode) =
      StartRequest(
          kind = SensorKind.SIMULATED,
          sensorId = sensor.id,
          sensorName = sensor.name,
          ecg = false,
          acc = false,
          mode = mode,
      )

  private fun homeFor(status: CaptureStatus) =
      homeState(status, null, null, HomeState.READY, false, SensorPicker())

  private fun await(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(10)
    }
    assertTrue("timed out waiting for $what; finishing=${RecordingRepository.status.value}", condition())
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
    private var running = false
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

    override fun onState(state: RecordingState) = Unit

    override fun onRecordingFinished(recordingId: String) {
      finishedRecordings += recordingId
    }

    override fun onFetchCompleted(recordingId: String) = Unit

    override fun finishService() = Unit
  }
}
