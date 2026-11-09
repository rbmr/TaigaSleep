package com.rbmr.taigasleep.ui.home

import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.PendingFetch
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.SensorCapabilities
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSensor(
    override val name: String = "Polar H10",
    private val modes: Set<RecordingMode> = setOf(RecordingMode.STREAM),
    private val ecg: Boolean = true,
    private val acc: Boolean = true,
) : SleepSensor {
  override val kind = SensorKind.POLAR_H10
  override val id = "S1"
  override val capabilities =
      SensorCapabilities(ecg = ecg, acc = acc, rr = true, live = true)
  override val connection = MutableStateFlow(ConnectionState.CONNECTED)
  override val battery = MutableStateFlow<Int?>(80)
  override val firmware = MutableStateFlow<String?>(null)

  override fun recordingModes(config: StreamConfig): Set<RecordingMode> = modes

  override fun connect() {}

  override fun disconnect() {}

  override fun startStreams(config: StreamConfig, listener: SampleListener) {}

  override fun stopStreams() {}
}

private class FakeWork : HomeWork {
  override val found = MutableStateFlow<List<FoundSensor>>(emptyList())
  override val scanning = MutableStateFlow(false)
  val calls = mutableListOf<String>()
  var bluetooth = true
  var streams = Streams(ecg = true)
  var modes = setOf(RecordingMode.STREAM)
  var mode = RecordingMode.STREAM
  var guard = HomeState.READY
  var canEcg = true
  var canAcc = false

  override fun onFirstShow() {
    calls += "first"
  }

  override fun hasBluetooth(): Boolean = bluetooth

  override fun restoreLast(canConnect: Boolean) {
    calls += "restore:$canConnect"
  }

  override fun savedConfig(sensor: SleepSensor) =
      StartConfig(streams, modes, mode, canEcg, canAcc)

  override fun setEcg(sensorId: String, on: Boolean) {
    calls += "ecg:$on"
    streams = streams.copy(ecg = on)
  }

  override fun setAcc(sensorId: String, on: Boolean) {
    calls += "acc:$on"
    streams = streams.copy(acc = on)
  }

  override fun setMode(sensorId: String, mode: RecordingMode) {
    calls += "mode:$mode"
    this.mode = mode
  }

  override fun guard(mode: RecordingMode): HomeState = guard

  override fun start(config: StartConfig) {
    calls += "start:${config.mode}:${config.streams.ecg}:${config.streams.acc}"
  }

  override fun stop() {
    calls += "stop"
  }

  override fun forceStop() {
    calls += "forceStop"
  }

  override fun discard() {
    calls += "discard"
  }

  override fun fetchAgain(recordingId: String) {
    calls += "fetch:$recordingId"
  }

  override fun disconnect() {
    calls += "disconnect"
  }

  override fun startScan() {
    calls += "scan"
  }

  override fun stopScan() {
    calls += "stopScan"
  }

  override fun select(found: FoundSensor) {
    calls += "select:${found.id}"
  }
}

private fun config(
    mode: RecordingMode = RecordingMode.STREAM,
    ecg: Boolean = true,
    modes: Set<RecordingMode> = setOf(RecordingMode.STREAM),
) = StartConfig(Streams(ecg = ecg, acc = false), modes, mode, canRecordEcg = true, canRecordAcc = false)

private val tracker = TrackerView("Polar H10", ConnectionState.CONNECTED, 80)
private val disconnected = TrackerView("Polar H10", ConnectionState.CONNECTING, 80)

/** The seven states of the start flow, and both recording modes, without a phone. */
class HomeStepTest {
  @Test
  fun `no tracker is the first step`() {
    val state = homeState(CaptureStatus(), null, null, HomeState.READY, false, SensorPicker())

    assertEquals(HomeUiState.NoTracker(HomeState.READY), state)
  }

  @Test
  fun `a selected tracker that is not connected is connecting`() {
    val state =
        homeState(CaptureStatus(), disconnected, null, HomeState.READY, false, SensorPicker())

    assertEquals(HomeUiState.Connecting(disconnected, HomeState.READY), state)
  }

  @Test
  fun `a connected tracker with the configuration sheet open is configuring`() {
    val state =
        homeState(CaptureStatus(), tracker, config(), HomeState.READY, true, SensorPicker())

    assertEquals(HomeUiState.Configure(tracker, config(), HomeState.READY), state)
  }

  @Test
  fun `a streamed night on an unprepared phone needs the guard`() {
    val state =
        homeState(
            CaptureStatus(),
            tracker,
            config(),
            HomeState.NEEDS_SETTINGS,
            false,
            SensorPicker(),
        )

    assertEquals(HomeUiState.NeedsGuard(tracker, config()), state)
  }

  @Test
  fun `a streamed night on a prepared phone is ready to start`() {
    val state =
        homeState(CaptureStatus(), tracker, config(), HomeState.READY, false, SensorPicker())

    assertEquals(HomeUiState.ReadyToStart(tracker, config(), HomeState.READY), state)
  }

  @Test
  fun `an on-device night needs no guard however the phone is set up`() {
    listOf(HomeState.NEEDS_SETTINGS, HomeState.NOT_NEEDED, HomeState.READY).forEach { guard ->
      val onDevice = config(mode = RecordingMode.ON_DEVICE)
      val state = homeState(CaptureStatus(), tracker, onDevice, guard, false, SensorPicker())

      assertEquals(HomeUiState.ReadyToStart(tracker, onDevice, guard), state)
    }
  }

  @Test
  fun `the recording state says which mode is running`() {
    val streamed =
        homeState(
            CaptureStatus(phase = Phase.RECORDING, liveStreaming = true),
            tracker,
            config(),
            HomeState.READY,
            false,
            SensorPicker(),
        )
    val onDevice =
        homeState(
            CaptureStatus(phase = Phase.RECORDING, liveStreaming = false),
            tracker,
            config(),
            HomeState.READY,
            false,
            SensorPicker(),
        )

    assertEquals(RecordingMode.STREAM, (streamed as HomeUiState.Recording).mode)
    assertEquals(RecordingMode.ON_DEVICE, (onDevice as HomeUiState.Recording).mode)
    assertFalse(streamed.stopping)
  }

  @Test
  fun `a stopping night keeps the recording state and disables its actions`() {
    val state =
        homeState(
            CaptureStatus(phase = Phase.STOPPING),
            tracker,
            config(),
            HomeState.READY,
            false,
            SensorPicker(),
        )

    assertTrue((state as HomeUiState.Recording).stopping)
  }

  @Test
  fun `a night still on the tracker is its own step`() {
    val pending = PendingFetch("20261023_223000", "Polar H10", 1_792_798_200_000L, "timed out")
    val state =
        homeState(
            CaptureStatus(fetchPending = pending),
            null,
            null,
            HomeState.READY,
            false,
            SensorPicker(),
        )

    assertEquals(
        HomeUiState.FetchPending(
            "20261023_223000",
            "Polar H10",
            "timed out",
            SensorPicker(),
        ),
        state,
    )
  }

  @Test
  fun `the service's last error reaches the screen`() {
    val state =
        homeState(
            CaptureStatus(error = "the sensor is not connected"),
            null,
            null,
            HomeState.READY,
            false,
            SensorPicker(),
        )

    assertEquals("the sensor is not connected", state.error)
  }
}

class HomeViewModelTest {
  private val work = FakeWork()
  private val sensor = MutableStateFlow<SleepSensor?>(null)
  private val status = MutableStateFlow(CaptureStatus())

  private fun viewModel(): HomeViewModel =
      HomeViewModel(work, sensor, status, Dispatchers.Unconfined)

  @Test
  fun `the screen starts without a tracker`() {
    assertEquals(HomeUiState.NoTracker(HomeState.NEEDS_SETTINGS), viewModel().state.value)
  }

  @Test
  fun `a connected tracker that is ready can start`() {
    val model = viewModel()

    sensor.value = FakeSensor()

    assertEquals(
        HomeUiState.ReadyToStart(tracker, config(), HomeState.READY),
        model.state.value,
    )
  }

  @Test
  fun `a tracker that is still connecting is not ready`() {
    val model = viewModel()
    val connecting = FakeSensor()
    connecting.connection.value = ConnectionState.CONNECTING

    sensor.value = connecting

    assertTrue(model.state.value is HomeUiState.Connecting)
  }

  @Test
  fun `a connected tracker on an unprepared phone needs the guard`() {
    work.guard = HomeState.NEEDS_SETTINGS
    val model = viewModel()

    sensor.value = FakeSensor()

    assertEquals(HomeUiState.NeedsGuard(tracker, config()), model.state.value)
  }

  @Test
  fun `an on-device tracker never needs the guard`() {
    work.guard = HomeState.NEEDS_SETTINGS
    work.modes = setOf(RecordingMode.ON_DEVICE)
    work.mode = RecordingMode.ON_DEVICE
    val model = viewModel()

    sensor.value = FakeSensor(modes = setOf(RecordingMode.ON_DEVICE))

    assertTrue(model.state.value is HomeUiState.ReadyToStart)
    assertEquals(
        RecordingMode.ON_DEVICE,
        (model.state.value as HomeUiState.ReadyToStart).config.mode,
    )
  }

  @Test
  fun `starting the night passes the chosen configuration to the recorder`() {
    val model = viewModel()
    sensor.value = FakeSensor()

    model.start()

    assertEquals(listOf("start:STREAM:true:false"), work.calls.filter { it.startsWith("start") })
  }

  @Test
  fun `the three ways to end a night go to the recorder`() {
    val model = viewModel()
    status.value = CaptureStatus(phase = Phase.RECORDING)

    model.stop()
    model.forceStop()
    model.discard()

    assertEquals(listOf("stop", "forceStop", "discard"), work.calls)
  }

  @Test
  fun `the fetch pending screen retries that recording`() {
    val model = viewModel()
    status.value =
        CaptureStatus(
            fetchPending = PendingFetch("20261023_223000", "Polar H10", 1_792_798_200_000L, null)
        )

    model.fetchAgain()

    assertEquals(listOf("fetch:20261023_223000"), work.calls)
  }

  @Test
  fun `opening and closing the configuration sheet drives the configuring step`() {
    val model = viewModel()
    sensor.value = FakeSensor()

    model.openConfiguration()

    assertTrue(model.state.value is HomeUiState.Configure)

    model.closeConfiguration()

    assertTrue(model.state.value is HomeUiState.ReadyToStart)
  }

  @Test
  fun `a stream choice is stored and the guard is read again`() {
    val model = viewModel()
    sensor.value = FakeSensor()

    model.setEcg(false)

    assertTrue("ecg:false" in work.calls)
    assertEquals(
        Streams(ecg = false, acc = false),
        (model.state.value as HomeUiState.ReadyToStart).config.streams,
    )
  }

  @Test
  fun `the on-device mode is stored as a mode choice`() {
    work.modes = setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE)
    work.guard = HomeState.NEEDS_SETTINGS
    val model = viewModel()
    sensor.value = FakeSensor(modes = setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE))
    assertTrue(model.state.value is HomeUiState.NeedsGuard)

    model.setMode(RecordingMode.ON_DEVICE)

    assertTrue("mode:ON_DEVICE" in work.calls)
    assertTrue(model.state.value is HomeUiState.ReadyToStart)
  }

  @Test
  fun `the readiness answer is read again when the screen resumes`() {
    val model = viewModel()
    sensor.value = FakeSensor()
    work.guard = HomeState.NEEDS_SETTINGS

    model.refreshGuard()

    assertEquals(HomeUiState.NeedsGuard(tracker, config()), model.state.value)
  }

  @Test
  fun `the first show repairs the recorder and reconnects the remembered tracker`() {
    val model = viewModel()

    model.onFirstShow()

    assertEquals(listOf("first"), work.calls)
  }

  @Test
  fun `asking for a sensor without Bluetooth asks for the permission first`() {
    work.bluetooth = false
    val model = viewModel()

    model.selectSensor()

    assertTrue(model.state.value.picker.permissionRequested)
    assertFalse(model.state.value.picker.visible)
    assertFalse("scan" in work.calls)
  }

  @Test
  fun `a granted permission opens the picker and reconnects the remembered tracker`() {
    work.bluetooth = false
    val model = viewModel()

    model.bluetoothAnswered(granted = true)

    assertTrue(model.state.value.picker.visible)
    assertTrue("restore:true" in work.calls)
  }

  @Test
  fun `a refused permission is shown on the sensor card`() {
    work.bluetooth = false
    val model = viewModel()

    model.bluetoothAnswered(granted = false)

    assertTrue(model.state.value.picker.denied)
    assertFalse(model.state.value.picker.visible)
  }

  @Test
  fun `the picker lists what the scan found and a choice selects it`() {
    val model = viewModel()
    val found = FoundSensor(SensorKind.POLAR_H10, "S1", "Polar H10", -50)

    model.selectSensor()
    work.found.value = listOf(found)
    work.scanning.value = true

    assertEquals(listOf(found), model.state.value.picker.found)
    assertTrue(model.state.value.picker.scanning)

    model.choose(found)

    assertEquals(listOf("scan", "select:S1"), work.calls.filter { it != "first" })
    assertFalse(model.state.value.picker.visible)
  }

  @Test
  fun `closing the picker stops the scan`() {
    val model = viewModel()
    model.selectSensor()

    model.closeScanner()

    assertTrue("stopScan" in work.calls)
    assertFalse(model.state.value.picker.visible)
  }

  @Test
  fun `disconnecting drops the remembered tracker`() {
    val model = viewModel()

    model.disconnect()

    assertEquals(listOf("disconnect"), work.calls)
    assertNull(model.state.value.error)
  }
}
