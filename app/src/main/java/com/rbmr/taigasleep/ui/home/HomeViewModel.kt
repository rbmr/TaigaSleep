package com.rbmr.taigasleep.ui.home

import androidx.lifecycle.ViewModel
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.AlarmTestResult
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.recording.SoundChoice
import com.rbmr.taigasleep.recording.WakeAlarmWarning
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
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
  fun savedConfig(sensor: Tracker): StartConfig

  /** Turns one of the tracker's streams on or off; the id is the one its driver offers. */
  fun setStream(sensorId: String, streamId: String, on: Boolean)

  fun setMode(sensorId: String, mode: RecordingMode)

  /** Whether the app may record from the microphone now. */
  fun hasMicrophone(): Boolean

  /** The phone's microphone of this tracker, and the codec it records in; off turns it off. */
  fun setSound(sensorId: String, choice: SoundChoice)

  /** The wake-up window of this tracker; null turns it off. */
  fun setWake(sensorId: String, wake: WakeChoice?)

  /**
   * Moves the smart alarm of the running recording, or switches it off with null. The change is
   * also the tracker's saved window, when the tracker is known.
   */
  fun changeLiveWake(sensorId: String?, wake: WakeChoice?)

  /** The shared answer of the preventing-shutdown flow for a recording mode. */
  fun guard(mode: RecordingMode): HomeState

  /** What could keep the smart alarm from being heard on this phone. */
  fun wakeWarnings(): Set<WakeAlarmWarning>

  /** The last test ring's result, updated by the ringing service. */
  val alarmTest: StateFlow<AlarmTestResult?>

  /** True while the ringing service has an alarm sounding. */
  val ringing: StateFlow<Boolean>

  /** Stops a ringing alarm without going through its notification. */
  fun stopAlarm()

  /** Rings the alarm for a few seconds through the same path the real alarm uses. */
  fun testAlarm()

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
    private val sensor: StateFlow<Tracker?>,
    private val status: StateFlow<CaptureStatus>,
    /** Whether a saved recording is uploaded by itself; the stop confirmation says which. */
    val autoUpload: Flow<Boolean> = emptyFlow(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private var current: Tracker? = null
  private var tracker: TrackerView? = null
  private var config: StartConfig? = null
  private var guard: HomeState = HomeState.NEEDS_SETTINGS
  private var configuring = false
  private var editingWake = false

  /** The last window the running recording's alarm had, so switching it back on restores it. */
  private var lastLiveWake: WakeChoice? = null
  private var picker = SensorPicker()
  private var capture = CaptureStatus()
  private var watching: Job? = null
  private var wakeWarnings: Set<WakeAlarmWarning> = emptySet()
  private var alarmTest: AlarmTestResult? = null

  /** The microphone request of the Sound section, and a refusal the section explains. */
  private var microphone = MicrophonePermission()

  /** The choice the user asked for before the microphone request: it is applied when granted. */
  private var pendingSound: SoundChoice? = null

  private val _state = MutableStateFlow<HomeUiState>(HomeUiState.NoTracker(guard))
  val state: StateFlow<HomeUiState> = _state.asStateFlow()

  /** The ringing alarm's state, straight from the service. */
  val ringing: StateFlow<Boolean> = work.ringing

  init {
    wakeWarnings = work.wakeWarnings()
    scope.launch {
      work.alarmTest.collect {
        alarmTest = it
        emit()
      }
    }
    scope.launch {
      status.collect {
        capture = it
        // The sheet belongs to one recording; the next one starts with it closed.
        if (it.phase != Phase.RECORDING) {
          editingWake = false
          lastLiveWake = null
        }
        it.wake?.window?.let { w -> lastLiveWake = WakeChoice(w.startMinute, w.lengthMinutes, w.heartRate, w.movement) }
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
    refreshWarnings()
    work.onFirstShow()
  }

  /** Something outside the app may have changed the answer: read the readiness again. */
  fun refreshGuard() {
    guard = work.guard(config?.mode ?: RecordingMode.STREAM)
    refreshWarnings()
    emit()
  }

  /** The gear on the sensor card: the sheet where the streams and the mode are chosen. */
  fun openConfiguration() {
    if (current == null) return
    refreshWarnings()
    configuring = true
    emit()
  }

  fun closeConfiguration() {
    configuring = false
    emit()
  }

  fun setStream(streamId: String, on: Boolean) =
      updateConfig { work.setStream(it.id, streamId, on) }

  fun setMode(mode: RecordingMode) = updateConfig { work.setMode(it.id, mode) }

  /**
   * The Sound section: off is stored at once. Turning sound on asks Android for the microphone first
   * when it has not been granted, and only a grant stores the choice. A refusal leaves sound off and
   * the section says what the permission is for.
   */
  fun setSound(choice: SoundChoice) {
    if (choice == SoundChoice.OFF || work.hasMicrophone()) {
      pendingSound = null
      microphone = MicrophonePermission()
      updateConfig { work.setSound(it.id, choice) }
      return
    }
    pendingSound = choice
    microphone = MicrophonePermission(requested = true)
    emit()
  }

  /** The screen has launched the microphone request; it must not launch it twice. */
  fun microphoneShown() {
    microphone = microphone.copy(requested = false)
    emit()
  }

  /** The microphone answer is in: a grant stores the choice, a refusal keeps sound off. */
  fun microphoneAnswered(granted: Boolean) {
    val choice = if (granted) pendingSound ?: SoundChoice.COMPRESSED else SoundChoice.OFF
    pendingSound = null
    microphone = MicrophonePermission(denied = !granted)
    updateConfig { work.setSound(it.id, choice) }
  }

  fun setWake(wake: WakeChoice?) = updateConfig { work.setWake(it.id, wake) }

  /** The smart alarm row of a running recording: the sheet that moves or switches the alarm. */
  fun openLiveWake() {
    refreshWarnings()
    editingWake = true
    emit()
  }

  fun closeLiveWake() {
    editingWake = false
    emit()
  }

  /** The user moved the smart alarm of the running recording, or switched it off with null. */
  fun setLiveWake(wake: WakeChoice?) {
    val sensor = current
    if (wake != null) lastLiveWake = wake
    work.changeLiveWake(sensor?.id, wake)
    if (sensor != null) config = work.savedConfig(sensor)
    refreshWarnings()
    emit()
  }

  /** The settings' test action: ring for a few seconds through the real alarm path. */
  fun testAlarm() {
    alarmTest = null
    work.testAlarm()
    emit()
  }

  /** Stops a ringing alarm from the screen; the recording goes on. */
  fun stopAlarm() = work.stopAlarm()

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
    val config = config?.takeIf { it.canStart } ?: return
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
  private fun updateConfig(change: (Tracker) -> Unit) {
    val sensor = current ?: return
    change(sensor)
    config = work.savedConfig(sensor)
    guard = work.guard(config?.mode ?: RecordingMode.STREAM)
    refreshWarnings()
    emit()
  }

  private fun refreshWarnings() {
    wakeWarnings = work.wakeWarnings()
  }

  /** One tracker at a time: the old connection is no longer watched once the sensor changes. */
  private fun attach(sensor: Tracker?) {
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
    _state.value =
        homeState(
            capture,
            tracker,
            config,
            guard,
            configuring,
            picker,
            editingWake,
            lastLiveWake,
            wakeWarnings,
            alarmTest,
            microphone,
        )
  }
}
