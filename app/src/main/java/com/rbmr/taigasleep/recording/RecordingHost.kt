package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor

/** A partial wake lock as the live session holds it. A test supplies a fake one. */
interface RecordingWakeLock {
  val isHeld: Boolean

  fun acquire(timeoutMs: Long)

  fun release()
}

/** The phone facts a new recording's metadata records. */
data class RecordingDevice(
    val manufacturer: String,
    val model: String,
    val android: String,
    val sdk: Int,
    val appVersion: String,
    val appVersionCode: Int,
)

/** A user-facing message the control flow needs, in the language the host resolves it to. */
enum class RecordingMessage {
  NO_SENSOR,
  MODE,
  NOT_READY,
  SPACE,
  STORAGE,
  FETCH_MISSING,
  FETCH_FAILED,
  FETCH_RECOVERY,
  FOREGROUND,
}

/**
 * The Android side of one recording: everything [RecordingControl] cannot do itself. The service
 * implements it, which keeps the control flow free of Android types so it can be unit-tested
 * against a fake sensor and a fake clock.
 *
 * Everything the host reports as a value ([activeSessionId], [heartbeatMs], [device]) is the
 * persisted or build-time fact the recording is governed by, not a copy the control flow keeps.
 */
interface RecordingHost {
  /**
   * The tracker for a recording, created or reused from the one already connected. Throws
   * [IllegalStateException] when the app cannot make one, with the reason for the user.
   */
  fun obtain(kind: SensorKind, id: String, name: String): SleepSensor

  /** Whether the phone may record in [mode]; false refuses the start and tells the user why. */
  fun mayStart(mode: RecordingMode): Boolean

  /** One line naming the phone settings that decide whether a recording survives. */
  fun readinessSummary(): String

  /** A fresh partial wake lock, or null when the phone refuses to give one. */
  fun newWakeLock(): RecordingWakeLock?

  /** Arms the exact alarm that watches the recording, or replaces the one already armed. */
  fun scheduleWatchdog()

  fun cancelWatchdog()

  /** The recording the service is keeping alive; null when nothing is live. */
  var activeSessionId: String?

  /** Wall-clock ms of the last sign of life the watchdog can see. */
  var heartbeatMs: Long

  /** True while this process has a live recording. */
  fun markRunning(running: Boolean)

  val device: RecordingDevice

  /** The message in the user's language. */
  fun message(message: RecordingMessage): String

  /** Re-reads the recordings on disk for the screens. */
  fun refreshRecordings()

  /** A new live snapshot, from the same state the screens read. */
  fun onState(state: RecordingState)

  /** The installed hooks: a recording ended and was kept, not discarded. */
  fun onRecordingFinished(recordingId: String)

  /** The installed hooks: a fetch-pending recording finally got the rest of its night. */
  fun onFetchCompleted(recordingId: String)

  /** Drops the foreground state and stops the service; it has nothing left to run. */
  fun finishService()
}
