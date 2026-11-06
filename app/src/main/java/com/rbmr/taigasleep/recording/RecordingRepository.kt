package com.rbmr.taigasleep.recording

import android.content.Context
import com.rbmr.taigasleep.appContainer
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

  /** Re-reads the recording list from disk (cheap for the handful of recordings kept on the phone). */
  fun refreshRecordings(context: Context) {
    _sessions.value = context.appContainer.recordingStore.list()
  }

  /** On app start: recordings left in `recording` with no live service become `interrupted`. */
  fun reconcile(context: Context) {
    if (RecordingService.running) return
    val prefs = RecordingPrefs.get(context)
    val changed = context.appContainer.recordingStore.markOrphans(liveId = null)
    if (changed.isNotEmpty() || prefs.activeSessionId != null) {
      prefs.activeSessionId = null
      WatchdogReceiver.cancel(context)
    }
    refreshRecordings(context)
  }
}
