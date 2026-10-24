package com.rbmr.taigasleep.recording

import android.content.Context
import com.rbmr.taigasleep.sensor.ConnectionState
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase {
  IDLE,
  RECORDING,
  STOPPING,
}

/** What the service has actually written so far. Ages are null until the first sample. */
data class LiveStats(
    val hrBpm: Int? = null,
    val hrSamples: Long = 0,
    val ecgSamples: Long = 0,
    val accSamples: Long = 0,
    val ecgRateHz: Double = 0.0,
    val accRateHz: Double = 0.0,
    val lastHrAgeMs: Long? = null,
    val lastEcgAgeMs: Long? = null,
    val lastAccAgeMs: Long? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val battery: Int? = null,
    val writeFailure: String? = null,
    val contact: Boolean? = null,
    val ecgPoor: Boolean = false,
)

data class RecordingStatus(
    val phase: Phase = Phase.IDLE,
    val sessionId: String? = null,
    val startUtcMs: Long = 0,
    val config: SessionConfig? = null,
    val sensorName: String? = null,
    /** False for sensors that only store on the device; the UI then shows just the connection. */
    val liveStreaming: Boolean = true,
    val live: LiveStats = LiveStats(),
    /** Why the last start was refused or the session ended abnormally. */
    val error: String? = null,
)

/** Observable recorder state: written by [RecordingService], read by the UI. */
object RecordingRepository {
  private val _status = MutableStateFlow(RecordingStatus())
  val status: StateFlow<RecordingStatus> = _status.asStateFlow()

  private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
  val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

  internal fun update(f: (RecordingStatus) -> RecordingStatus) = _status.update(f)

  internal fun setIdle(error: String? = null) {
    _status.value = RecordingStatus(error = error)
  }

  /** Re-reads the session list from disk (cheap for the handful of nights kept on the phone). */
  fun refreshSessions(context: Context) {
    _sessions.value = SessionStore.of(context).list()
  }

  /** On app start: sessions left in `recording` with no live service become `interrupted`. */
  fun reconcile(context: Context) {
    if (RecordingService.running) return
    val prefs = RecordingPrefs.get(context)
    val changed = SessionStore.of(context).markOrphans(liveId = null)
    if (changed.isNotEmpty() || prefs.activeSessionId != null) {
      prefs.activeSessionId = null
      WatchdogReceiver.cancel(context)
    }
    refreshSessions(context)
  }
}
