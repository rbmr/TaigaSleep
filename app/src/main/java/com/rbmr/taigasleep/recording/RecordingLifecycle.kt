package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.RecordingMode

/** Where a recording is in its life. Terminal states do not change again. */
enum class LifecycleState {
  IDLE,
  RECORDING,
  STOPPING,
  FINISHED,
  DISCARDED,
}

/** One thing the service has to do for a transition. The machine decides, the service performs. */
sealed interface LifecycleEffect {
  /** Keep the phone awake: streaming needs it, on-device recording does not. */
  data object AcquireWakeLock : LifecycleEffect

  /** Close the live streams and wait for the tracker, within the service's timeout. */
  data object StopStreams : LifecycleEffect

  /** Ask the tracker to stop without waiting for it. */
  data object StopStreamsAsync : LifecycleEffect

  /** Download the recording the tracker made, now that the night is over. */
  data object FetchFromTracker : LifecycleEffect

  /** Add the final "stop"/"force_stop" event and write the final meta.json state. */
  data class Finish(val phase: RecordingPhase) : LifecycleEffect

  /** Remove this recording from the phone. */
  data object DeleteLocal : LifecycleEffect

  /** Tell an on-device tracker to stop and remove its copy too. */
  data object DiscardOnTracker : LifecycleEffect
}

/**
 * The one state machine for starting and ending a recording. Stop, force stop and discard are
 * transitions whose effects come from the [mode], exactly as the table in docs/architecture.md
 * describes. Nothing else decides what an action means.
 *
 * It has no Android dependencies, so every transition is unit-tested for both modes.
 */
class RecordingLifecycle(val mode: RecordingMode) {
  var state: LifecycleState = LifecycleState.IDLE
    private set

  /** A transition is only allowed from [LifecycleState.RECORDING]. */
  fun start(): List<LifecycleEffect> {
    if (state != LifecycleState.IDLE) return emptyList()
    state = LifecycleState.RECORDING
    return if (mode == RecordingMode.STREAM) listOf(LifecycleEffect.AcquireWakeLock) else emptyList()
  }

  /** Graceful stop: close or fetch everything, then finish the recording. */
  fun stop(): List<LifecycleEffect> {
    if (!beginStop()) return emptyList()
    return when (mode) {
      RecordingMode.STREAM ->
          listOf(
              LifecycleEffect.StopStreams,
              LifecycleEffect.Finish(RecordingPhase.STOPPED),
          )
      RecordingMode.ON_DEVICE ->
          listOf(
              LifecycleEffect.FetchFromTracker,
              LifecycleEffect.StopStreams,
              LifecycleEffect.Finish(RecordingPhase.STOPPED),
          )
    }.also { state = LifecycleState.FINISHED }
  }

  /** Force stop: accept what is already on the phone, do not wait for the tracker. */
  fun forceStop(): List<LifecycleEffect> {
    if (!beginStop()) return emptyList()
    return when (mode) {
      RecordingMode.STREAM ->
          listOf(
              LifecycleEffect.Finish(RecordingPhase.FORCED_STOP),
              LifecycleEffect.StopStreamsAsync,
          )
      RecordingMode.ON_DEVICE -> listOf(LifecycleEffect.Finish(RecordingPhase.FORCED_STOP))
    }.also { state = LifecycleState.FINISHED }
  }

  /** Discard: end the recording and remove every copy, local and on the tracker. */
  fun discard(): List<LifecycleEffect> {
    if (!beginStop()) return emptyList()
    return when (mode) {
      RecordingMode.STREAM ->
          listOf(
              LifecycleEffect.StopStreamsAsync,
              LifecycleEffect.DeleteLocal,
          )
      RecordingMode.ON_DEVICE ->
          listOf(
              LifecycleEffect.DiscardOnTracker,
              LifecycleEffect.DeleteLocal,
          )
    }.also { state = LifecycleState.DISCARDED }
  }

  /** The OS or the user killed the app: the recording was interrupted, keep what is on disk. */
  fun interrupted(): List<LifecycleEffect> {
    if (state == LifecycleState.IDLE || state == LifecycleState.FINISHED || state == LifecycleState.DISCARDED) {
      return emptyList()
    }
    state = LifecycleState.FINISHED
    return listOf(LifecycleEffect.Finish(RecordingPhase.INTERRUPTED))
  }

  private fun beginStop(): Boolean {
    if (state != LifecycleState.RECORDING) return false
    state = LifecycleState.STOPPING
    return true
  }
}
