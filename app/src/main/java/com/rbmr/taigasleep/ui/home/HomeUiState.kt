package com.rbmr.taigasleep.ui.home

import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.CaptureStatus
import com.rbmr.taigasleep.recording.FinishingState
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode

/** The data the user chose to record. */
data class Streams(val ecg: Boolean = false, val acc: Boolean = false, val hr: Boolean = true) {
  /** True when at least one kind of data is on; a recording needs one. */
  val any: Boolean
    get() = hr || ecg || acc
}

/**
 * The wake-up window the user set for tonight, as the screen holds it: local minutes of day and the
 * signals that may wake them. It is the screen's copy of `recording.WakeWindow`, the way [Streams]
 * is the copy of `tracker.StreamConfig`; the recorder owns the decision, the screen only sets it.
 */
data class WakeChoice(
    val startMinute: Int,
    val lengthMinutes: Int,
    val heartRate: Boolean = true,
    val movement: Boolean = false,
) {
  /** True when a signal may wake the user before the end of the window. */
  val triggers: Boolean
    get() = heartRate || movement

  companion object {
    /** The window lengths the screen offers, in minutes. */
    val LENGTH_CHOICES = listOf(10, 20, 30, 45, 60)

    const val DEFAULT_LENGTH = 30
  }
}

/**
 * What the tracker will record and how: the configuration step of the start flow. Only the
 * recording [mode] drives the rest. On the tracker the recording is heart rate only and the smart
 * alarm is off; streaming to the phone uses the choices the user made for it. [streams] and [wake]
 * are the effective values, the ones the recorder uses and the sheet shows.
 */
data class StartConfig(
    val streams: Streams,
    /** The modes this tracker offers; every one of them can always be chosen. */
    val modes: Set<RecordingMode>,
    val mode: RecordingMode,
    val canRecordEcg: Boolean,
    val canRecordAcc: Boolean,
    /** The smart alarm window of a streamed recording, or null when it is off (the default). */
    val wake: WakeChoice? = null,
) {
  /** The tracker stores the recording itself instead of streaming it to the phone. */
  val onTracker: Boolean
    get() = mode == RecordingMode.ON_DEVICE

  /** Something is on, so the recording can start; otherwise the next step is to choose data. */
  val canStart: Boolean
    get() = streams.any

  companion object {
    /**
     * The effective configuration for the user's stored choices. Heart rate is shared by both modes.
     * The tracker keeps heart rate only and has no smart alarm, so those choices are not applied
     * there, and not lost either: they stay stored and come back with the streaming mode.
     */
    fun resolve(
        saved: SavedChoices,
        modes: Set<RecordingMode>,
        canRecordEcg: Boolean,
        canRecordAcc: Boolean,
    ): StartConfig {
      val mode =
          when {
            saved.mode in modes -> saved.mode
            RecordingMode.STREAM in modes -> RecordingMode.STREAM
            else -> modes.firstOrNull() ?: RecordingMode.STREAM
          }
      val onTracker = mode == RecordingMode.ON_DEVICE
      return StartConfig(
          streams =
              Streams(
                  hr = saved.hr,
                  ecg = !onTracker && canRecordEcg && saved.ecg,
                  acc = !onTracker && canRecordAcc && saved.acc,
              ),
          modes = modes,
          mode = mode,
          canRecordEcg = canRecordEcg,
          canRecordAcc = canRecordAcc,
          wake = saved.wake.takeIf { !onTracker },
      )
    }
  }
}

/** What the user chose, stored as it was made: the streaming choices survive the other mode. */
data class SavedChoices(
    val hr: Boolean,
    val ecg: Boolean,
    val acc: Boolean,
    val mode: RecordingMode,
    val wake: WakeChoice?,
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
 * A stopped recording is finished before Home returns to the start flow: [Finishing] carries the
 * steps it goes through and the card that says the night is safe.
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

  /**
   * A recording that was stopped and is being finished: the steps that apply to its mode, and the
   * done card while the user can safely take the tracker off.
   */
  data class Finishing(
      val finishing: FinishingState,
      override val picker: SensorPicker = SensorPicker(),
  ) : HomeUiState {
    override val guard: HomeState?
      get() = null

    override val error: String?
      get() = null

    /** The night is on the phone; the card offers the link to it and a way back to Home. */
    val done: Boolean
      get() = finishing.done
  }

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

  /** A recording is running: Stop, and the other two ways to end it behind the overflow. */
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

    /** The night is on the tracker, so the confirmations say it has to be fetched first. */
    val onDevice: Boolean
      get() = mode == RecordingMode.ON_DEVICE

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
  status.finishing?.let { return HomeUiState.Finishing(it, picker) }
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
