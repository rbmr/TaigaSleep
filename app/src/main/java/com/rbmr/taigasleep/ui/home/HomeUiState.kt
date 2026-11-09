package com.rbmr.taigasleep.ui.home

import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode

/** The streams the user chose. Heart rate is always recorded. */
data class Streams(val ecg: Boolean = false, val acc: Boolean = false)

/**
 * What the tracker will record and how: the configuration step of the start flow. The mode follows
 * from the tracker and the chosen streams, so both are part of the same answer.
 */
data class StartConfig(
    val streams: Streams,
    /** The modes this tracker offers for these streams, in the order it reports them. */
    val modes: Set<RecordingMode>,
    val mode: RecordingMode,
    val canRecordEcg: Boolean,
    val canRecordAcc: Boolean,
    /** Every mode the tracker offers for some configuration; a mode outside [modes] is greyed out. */
    val offeredModes: Set<RecordingMode> = modes,
)

/** The connected tracker as the screen shows it: who it is, whether it is here, and its battery. */
data class TrackerView(
    val name: String,
    val connection: ConnectionState,
    val battery: Int?,
)

/** The sensor picker sheet, which every state before a recording can open. */
data class SensorPicker(
    val visible: Boolean = false,
    val scanning: Boolean = false,
    val found: List<FoundSensor> = emptyList(),
    /** Bluetooth was refused, so no scan can start until the permission is granted. */
    val denied: Boolean = false,
    /** The screen asked for the permission; it clears the flag once the answer is handled. */
    val permissionRequested: Boolean = false,
)

/**
 * The start flow of docs/architecture.md as named states: connect a tracker ([NoTracker],
 * [Connecting]), configure what to record ([Configure]), the preventing-shutdown flow when the
 * mode needs it ([NeedsGuard]), start ([ReadyToStart]) and the recording itself ([Recording]).
 *
 * The states are produced by [homeState], a pure function of the tracker, the chosen configuration,
 * the readiness answer and the recording state.
 */
sealed interface HomeUiState {
  /** The preventing-shutdown answer the readiness card shows; null while a recording is running. */
  val guard: HomeState?

  val picker: SensorPicker

  /** Why the last start was refused or the last recording ended abnormally. */
  val error: String?

  /** No tracker has been selected yet. */
  data class NoTracker(
      override val guard: HomeState,
      override val picker: SensorPicker = SensorPicker(),
      override val error: String? = null,
  ) : HomeUiState

  /** A tracker is selected but not connected yet. */
  data class Connecting(
      val tracker: TrackerView,
      override val guard: HomeState,
      override val picker: SensorPicker = SensorPicker(),
      override val error: String? = null,
  ) : HomeUiState

  /** Connected: choosing what to record, which is the sheet the gear opens. */
  data class Configure(
      val tracker: TrackerView,
      val config: StartConfig,
      override val guard: HomeState,
      override val picker: SensorPicker = SensorPicker(),
      override val error: String? = null,
  ) : HomeUiState

  /** Connected, recording mode Stream, and the phone is not ready for a recording yet. */
  data class NeedsGuard(
      val tracker: TrackerView,
      val config: StartConfig,
      override val picker: SensorPicker = SensorPicker(),
      override val error: String? = null,
  ) : HomeUiState {
    override val guard: HomeState
      get() = HomeState.NEEDS_SETTINGS
  }

  /** Connected and ready: the recording can start. */
  data class ReadyToStart(
      val tracker: TrackerView,
      val config: StartConfig,
      override val guard: HomeState,
      override val picker: SensorPicker = SensorPicker(),
      override val error: String? = null,
  ) : HomeUiState

  /** The tracker still holds a night; the only action is to fetch it again. */
  data class FetchPending(
      val recordingId: String,
      val sensorName: String,
      override val error: String?,
      override val picker: SensorPicker = SensorPicker(),
  ) : HomeUiState {
    override val guard: HomeState?
      get() = null
  }

  /** A recording is running, with the three ways to end it. */
  data class Recording(
      val status: CaptureStatus,
      override val picker: SensorPicker = SensorPicker(),
  ) : HomeUiState {
    override val guard: HomeState?
      get() = null

    override val error: String?
      get() = null

    /** The mode the running recording was started with; the service reports whether it streams. */
    val mode: RecordingMode
      get() = if (status.liveStreaming) RecordingMode.STREAM else RecordingMode.ON_DEVICE

    val stopping: Boolean
      get() = status.phase == Phase.STOPPING
  }
}

/**
 * One step of the start flow. Pure on purpose: the tracker, the chosen configuration, the readiness
 * answer and the recording state are the only inputs, so every step and both recording modes can be
 * tested without a phone.
 */
internal fun homeState(
    status: CaptureStatus,
    tracker: TrackerView?,
    config: StartConfig?,
    guard: HomeState,
    configuring: Boolean,
    picker: SensorPicker,
): HomeUiState {
  if (status.phase != Phase.IDLE) return HomeUiState.Recording(status, picker)
  status.fetchPending?.let {
    return HomeUiState.FetchPending(it.recordingId, it.sensorName, it.error, picker)
  }
  if (tracker == null) return HomeUiState.NoTracker(guard, picker, status.error)
  // Without a configuration there is nothing to show past the connection, so it is not ready.
  if (config == null || tracker.connection != ConnectionState.CONNECTED) {
    return HomeUiState.Connecting(tracker, guard, picker, status.error)
  }
  if (configuring) return HomeUiState.Configure(tracker, config, guard, picker, status.error)
  if (config.mode == RecordingMode.STREAM && guard == HomeState.NEEDS_SETTINGS) {
    return HomeUiState.NeedsGuard(tracker, config, picker, status.error)
  }
  return HomeUiState.ReadyToStart(tracker, config, guard, picker, status.error)
}
