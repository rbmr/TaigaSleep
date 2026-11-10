package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.EventLog
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.RecordingMode

/** What a path that found a recording still open has to do about it. */
enum class RecoveryAction {
  /** Ask the service to pick the recording up; it decides whether it can continue. */
  RESUME,

  /** Ask the service to rebuild the live session for the same recording. */
  RESTART,

  /** Leave the recording alone: it is still being written. */
  WAIT,

  /** Nothing can continue it: close it as interrupted. */
  INTERRUPT,
}

/**
 * The rules that keep a recording alive across a dead process, a reboot and a long night.
 *
 * Four paths ask them and only perform the answer: opening the app, a reboot, the watchdog and an
 * upload that finds the recording still open. They have no Android types, so every recovery decision
 * is unit-tested once instead of being repeated in each path.
 */
object RecordingRecovery {
  /** How old the service's heartbeat may be before the watchdog rebuilds its pipeline. */
  const val STALE_MS = 150_000L

  /** The partial wake lock is timed, so it is held for this long and renewed while recording goes on. */
  const val WAKE_LOCK_MS = 10 * 60_000L

  /** Less than this left on the wake lock means it has to be renewed now. */
  const val WAKE_LOCK_RENEW_BEFORE_MS = 60_000L

  /**
   * The recording the service can pick up: streamed to the phone and still open, or null. A
   * recording the tracker stores on its own is not resumed; it needs nothing from the phone.
   */
  fun live(store: RecordingStore, activeSessionId: String?): RecordingMeta? =
      activeSessionId
          ?.let { RecordingMeta.read(store.dir(it)) }
          ?.takeIf { it.state == RecordingPhase.RECORDING && it.mode == RecordingMode.STREAM }

  /**
   * Opening the app: the recording to hand to the service, or null when nothing is live. A
   * recording whose marker is set is left exactly as it is on disk, so the service can resume it on
   * the same directory; only the recordings without a marker are orphans and get closed.
   */
  fun onAppear(store: RecordingStore, activeSessionId: String?): RecordingMeta? {
    live(store, activeSessionId)?.let { return it }
    store.markOrphans(liveId = null)
    return null
  }

  /**
   * After a reboot: the recording to hand back to the service, or null when the phone was not
   * recording. Nothing else is touched: a reboot orphans nothing, it only interrupted the service.
   */
  fun onBoot(store: RecordingStore, activeSessionId: String?): RecordingMeta? = live(store, activeSessionId)

  /**
   * The watchdog: a marker with no service behind it is resumed, and a service that is alive but
   * whose heartbeat stopped is rebuilt. Resuming that one would do nothing, because its live session
   * is still there. Null when the service is healthy or no recording is active.
   */
  fun onWatchdog(
      activeSessionId: String?,
      serviceRunning: Boolean,
      heartbeatAgeMs: Long,
  ): RecoveryAction? =
      when {
        activeSessionId == null -> null
        !serviceRunning -> RecoveryAction.RESUME
        heartbeatAgeMs >= STALE_MS -> RecoveryAction.RESTART
        else -> null
      }

  /**
   * An upload found a recording still marked open. A streamed recording whose marker is set belongs
   * to the service, which decides whether it can continue. An on-device recording keeps going on the
   * tracker, so nothing may touch it. Without a marker the process died and the recording is closed.
   */
  fun onUploadFoundOpen(
      mode: RecordingMode,
      activeSessionId: String?,
      recordingId: String,
  ): RecoveryAction =
      when {
        mode == RecordingMode.ON_DEVICE -> RecoveryAction.WAIT
        activeSessionId == recordingId -> RecoveryAction.RESUME
        else -> RecoveryAction.INTERRUPT
      }

  /** True once a lock held for [heldForMs] is close enough to its timeout to be renewed. */
  fun wakeLockNeedsRenewal(heldForMs: Long): Boolean =
      heldForMs >= WAKE_LOCK_MS - WAKE_LOCK_RENEW_BEFORE_MS

  /**
   * Closes the recording the phone still marks live because the system refused to keep the service
   * in the foreground, and returns its id. Null when nothing was open. Nothing is thrown: the service
   * is on its way out and must not crash on the way.
   */
  fun abandon(
      store: RecordingStore,
      activeSessionId: String?,
      reason: String,
      clock: Clock,
  ): String? {
    val meta = activeSessionId?.let { RecordingMeta.read(store.dir(it)) } ?: return null
    if (meta.state != RecordingPhase.RECORDING) return null
    return try {
      val dir = store.dir(meta.recordingId)
      EventLog.append(dir, clock.phoneNs(), clock.elapsedNs(), "error", reason)
      val ended =
          meta.copy(
              state = RecordingPhase.INTERRUPTED,
              endUtcMs = store.lastFrameMs(dir) ?: meta.startUtcMs,
          )
      if (ended.write(dir)) meta.recordingId else null
    } catch (_: Exception) {
      null
    }
  }

  /** A recording the tracker already stores is adopted on resume, never started a second time. */
  fun resumesParked(mode: RecordingMode): Boolean = mode == RecordingMode.ON_DEVICE

  /** Whether attaching has to ask the tracker to start: never for a recording it already stores. */
  fun startsOnTracker(mode: RecordingMode, parked: Boolean): Boolean =
      mode == RecordingMode.ON_DEVICE && !parked
}
