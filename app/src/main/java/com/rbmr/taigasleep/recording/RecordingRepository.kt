package com.rbmr.taigasleep.recording

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase {
  IDLE,
  RECORDING,
  STOPPING,
}

/** A recording whose night is still on the tracker and can be fetched again. */
data class PendingFetch(
    val recordingId: String,
    val sensorName: String,
    val startUtcMs: Long,
    val error: String?,
)

data class CaptureStatus(
    val phase: Phase = Phase.IDLE,
    val recordingId: String? = null,
    val startUtcMs: Long = 0,
    val config: RecordingConfig? = null,
    val sensorName: String? = null,
    /** False for sensors that only store on the device; the UI then shows just the connection. */
    val liveStreaming: Boolean = true,
    /** The one live snapshot; the UI and the notification only format it. */
    val state: RecordingState = RecordingState(),
    /** Why the last start was refused or the recording ended abnormally. */
    val error: String? = null,
    /** Set instead of [phase] when the tracker still holds a night that must be fetched again. */
    val fetchPending: PendingFetch? = null,
    /** The steps a stopped recording is going through, until the user leaves the done card. */
    val finishing: FinishingState? = null,
)

/** Observable recorder state: written by [RecordingService], read by the UI. */
object RecordingRepository {
  private val _status = MutableStateFlow(CaptureStatus())
  val status: StateFlow<CaptureStatus> = _status.asStateFlow()

  private val _sessions = MutableStateFlow<List<Recording>>(emptyList())
  val sessions: StateFlow<List<Recording>> = _sessions.asStateFlow()

  internal fun update(f: (CaptureStatus) -> CaptureStatus) = _status.update(f)

  internal fun setIdle(error: String? = null) {
    _status.value = CaptureStatus(error = error)
  }

  internal fun setFetchPending(pending: PendingFetch) {
    _status.value = CaptureStatus(fetchPending = pending)
  }

  /** Publishes how far a stopped recording got; the done card stays until it is dismissed. */
  internal fun setFinishing(finishing: FinishingState) {
    _status.value =
        CaptureStatus(
            phase = if (finishing.done) Phase.IDLE else Phase.STOPPING,
            recordingId = finishing.recordingId,
            sensorName = finishing.sensorName,
            finishing = finishing,
        )
  }

  /** The user has seen the done card: Home goes back to the start flow. */
  internal fun dismissFinishing() {
    if (_status.value.finishing != null) setIdle()
  }

  /** Re-reads the recording list from disk (cheap for the handful of recordings kept on the phone). */
  fun refreshRecordings(context: Context) {
    _sessions.value = context.appContainer.recordingStore.list()
  }

  /**
   * On app start: a streamed recording whose marker is set is handed back to the service, which
   * decides whether it can continue; only a recording the process left behind without a marker
   * becomes `interrupted`. A recording the tracker stores on its own stays active: nothing on the
   * phone has to be running for it.
   */
  fun reconcile(context: Context) {
    if (RecordingService.running) return
    val prefs = RecordingPrefs.get(context)
    val store = context.appContainer.recordingStore
    val live = RecordingRecovery.onAppear(store, prefs.activeSessionId)
    if (live != null) {
      prefs.activeSessionId = live.recordingId
      try {
        RecordingService.send(context, RecordingService.ACTION_RESUME)
      } catch (e: Exception) {
        // A refused start must not take the screen down: the marker stays, so the watchdog or the
        // next app start hands the recording to the service again.
        Diagnostics.log("resume_refused", mapOf("error" to e.message))
      }
      refreshRecordings(context)
      return
    }
    val onDevice = store.onDeviceInProgress()
    val pending = store.fetchPending()
    prefs.activeSessionId = onDevice?.recordingId
    // Nothing here can be resumed, so a window left from an earlier process rings nothing.
    WakeAlarm.disarm(context)
    WatchdogReceiver.cancel(context)
    if (onDevice != null) {
      _status.value =
          CaptureStatus(
              phase = Phase.RECORDING,
              recordingId = onDevice.recordingId,
              startUtcMs = onDevice.startUtcMs,
              config = onDevice.config,
              sensorName = onDevice.sensorName,
              liveStreaming = false,
          )
    } else if (pending != null) {
      _status.value =
          CaptureStatus(
              fetchPending =
                  PendingFetch(
                      recordingId = pending.recordingId,
                      sensorName = pending.sensorName,
                      startUtcMs = pending.startUtcMs,
                      error = null,
                  )
          )
    }
    // Opening the app connects the tracker again, which is when the copies that could not be
    // removed when their recordings ended get their next chance.
    if (store.pendingTrackerRemovals().isNotEmpty()) {
      try {
        RecordingService.cleanup(context)
      } catch (e: Exception) {
        Diagnostics.log("cleanup_refused", mapOf("error" to e.message))
      }
    }
    refreshRecordings(context)
  }
}
