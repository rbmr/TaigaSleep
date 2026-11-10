package com.rbmr.taigasleep.ui.home

import androidx.lifecycle.ViewModel
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SleepSensor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The work behind the Home screen, behind a port so the view model needs no Android types. The
 * tracker, the recorder and the readiness check are called only from here.
 */
interface HomeWork {
  val found: StateFlow<List<FoundSensor>>
  val scanning: StateFlow<Boolean>

  /** Once, when the screen is first shown: repair what a dead process left, reconnect the tracker. */
  fun onFirstShow()

  fun hasBluetooth(): Boolean

  fun restoreLast(canConnect: Boolean)

  /** The remembered configuration of this tracker: its streams, the modes they allow and the choice. */
  fun savedConfig(sensor: SleepSensor): StartConfig

  fun setEcg(sensorId: String, on: Boolean)

  fun setAcc(sensorId: String, on: Boolean)

  fun setMode(sensorId: String, mode: RecordingMode)

  /** The wake-up window of this tracker; null turns it off. */
  fun setWake(sensorId: String, wake: WakeChoice?)

  /** The shared answer of the preventing-shutdown flow for a recording mode. */
  fun guard(mode: RecordingMode): HomeState

  fun start(config: StartConfig)

  fun stop()

  fun forceStop()

  fun discard()

  /** Try again to move a night the tracker still holds onto the phone. */
  fun fetchAgain(recordingId: String)

  /** The user has seen the done card: Home goes back to the start flow. */
  fun dismissFinishing()

  fun disconnect()

  fun startScan()

  fun stopScan()

  fun select(found: FoundSensor)
}

/**
 * The state holder of the Home screen and the start flow. It follows the tracker and the recorder,
 * keeps the chosen configuration and the readiness answer, and produces one [HomeUiState].
 */
class HomeViewModel(
    private val work: HomeWork,
    private val sensor: StateFlow<SleepSensor?>,
    private val status: StateFlow<CaptureStatus>,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private var current: SleepSensor? = null
  private var tracker: TrackerView? = null
  private var config: StartConfig? = null
  private var guard: HomeState = HomeState.NEEDS_SETTINGS
  private var configuring = false
  private var picker = SensorPicker()
  private var capture = CaptureStatus()
  private var watching: Job? = null

  private val _state = MutableStateFlow<HomeUiState>(HomeUiState.NoTracker(guard))
  val state: StateFlow<HomeUiState> = _state.asStateFlow()

  init {
    scope.launch {
      status.collect {
        capture = it
        emit()
      }
    }
    scope.launch {
      sensor.collect { attach(it) }
    }
    scope.launch {
      combine(work.found, work.scanning) { found, scanning -> found to scanning }
          .collect { (found, scanning) ->
            picker = picker.copy(found = found, scanning = scanning)
            emit()
          }
    }
  }

  /** The screen was shown: the recorder is reconciled and the remembered tracker reconnects. */
  fun onFirstShow() {
    work.onFirstShow()
  }

  /** Something outside the app may have changed the answer: read the readiness again. */
  fun refreshGuard() {
    guard = work.guard(config?.mode ?: RecordingMode.STREAM)
    emit()
  }

  /** The gear on the sensor card: the sheet where the streams and the mode are chosen. */
  fun openConfiguration() {
    if (current == null) return
    configuring = true
    emit()
  }

  fun closeConfiguration() {
    configuring = false
    emit()
  }

  fun setEcg(on: Boolean) = updateConfig { work.setEcg(it.id, on) }

  fun setAcc(on: Boolean) = updateConfig { work.setAcc(it.id, on) }

  fun setMode(mode: RecordingMode) = updateConfig { work.setMode(it.id, mode) }

  fun setWake(wake: WakeChoice?) = updateConfig { work.setWake(it.id, wake) }

  /** The user asked for the sensor picker; the Bluetooth permission may have to be asked first. */
  fun selectSensor() {
    if (work.hasBluetooth()) {
      openScanner()
    } else {
      picker = picker.copy(permissionRequested = true)
      emit()
    }
  }

  /** The screen has launched the permission request; it must not launch it twice. */
  fun permissionShown() {
    picker = picker.copy(permissionRequested = false)
    emit()
  }

  /** The Bluetooth answer is in: a refusal is shown on the sensor card, a grant opens the picker. */
  fun bluetoothAnswered(granted: Boolean) {
    picker = picker.copy(denied = !granted)
    if (granted) {
      openScanner()
      work.restoreLast(canConnect = true)
    } else {
      emit()
    }
  }

  fun closeScanner() {
    picker = picker.copy(visible = false)
    work.stopScan()
    emit()
  }

  fun scanAgain() {
    work.startScan()
  }

  /** One sensor was picked from the sheet; it becomes the tracker from now on. */
  fun choose(found: FoundSensor) {
    work.select(found)
    picker = picker.copy(visible = false)
    emit()
  }

  fun disconnect() {
    work.disconnect()
  }

  /** Start the recording with the chosen configuration. */
  fun start() {
    val config = config ?: return
    work.start(config)
  }

  fun stop() {
    work.stop()
  }

  fun forceStop() {
    work.forceStop()
  }

  fun discard() {
    work.discard()
  }

  /** The screen is showing a fetch-pending night: retry its tracker fetch. */
  fun fetchAgain() {
    val pending = state.value as? HomeUiState.FetchPending ?: return
    work.fetchAgain(pending.recordingId)
  }

  /** The done card has been read: the next stop starts from the ordinary Home state. */
  fun dismissFinishing() {
    work.dismissFinishing()
  }

  override fun onCleared() {
    scope.cancel()
  }

  private fun openScanner() {
    picker = picker.copy(visible = true)
    work.startScan()
    emit()
  }

  /** A change of the chosen streams or mode was made: keep the configuration and the answer true. */
  private fun updateConfig(change: (SleepSensor) -> Unit) {
    val sensor = current ?: return
    change(sensor)
    config = work.savedConfig(sensor)
    guard = work.guard(config?.mode ?: RecordingMode.STREAM)
    emit()
  }

  /** One tracker at a time: the old connection is no longer watched once the sensor changes. */
  private fun attach(sensor: SleepSensor?) {
    watching?.cancel()
    current = sensor
    if (sensor == null) {
      tracker = null
      config = null
      emit()
      return
    }
    config = work.savedConfig(sensor)
    guard = work.guard(config?.mode ?: RecordingMode.STREAM)
    watching =
        scope.launch {
          combine(sensor.connection, sensor.battery) { connection, battery ->
                TrackerView(sensor.name, connection, battery)
              }
              .collect {
                tracker = it
                emit()
              }
        }
  }

  private fun emit() {
    _state.value = homeState(capture, tracker, config, guard, configuring, picker)
  }
}
