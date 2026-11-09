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
  /** The tracker still holds part of the recording; a fetch can be tried again. */
  FETCH_PENDING,
}

/** One thing the service has to do for a transition. The machine decides, the service performs. */
sealed interface LifecycleEffect {
  /** Keep the phone awake: streaming needs it, on-device recording does not. */
  data object AcquireWakeLock : LifecycleEffect

  /** Close the live streams and wait for the tracker, within the service's timeout. */
  data object StopStreams : LifecycleEffect

  /** Ask the tracker to stop without waiting for it. */
  data object StopStreamsAsync : LifecycleEffect

  /** Stop the tracker, download its recording, write it and flush it. */
  data object FetchFromTracker : LifecycleEffect

  /** Remove the tracker's copy, only after [FetchFromTracker] verified the phone's samples. */
  data object RemoveFromTracker : LifecycleEffect

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
class RecordingLifecycle(
    val mode: RecordingMode,
    initialState: LifecycleState = LifecycleState.IDLE,
) {
  var state: LifecycleState = initialState
    private set

  /** A transition is only allowed from [LifecycleState.RECORDING]. */
  fun start(): List<LifecycleEffect> {
    if (state != LifecycleState.IDLE) return emptyList()
    state = LifecycleState.RECORDING
    return if (mode == RecordingMode.STREAM) listOf(LifecycleEffect.AcquireWakeLock) else emptyList()
  }

  /**
   * Graceful stop: close the streams, or fetch everything. An on-device stop stays in
   * [LifecycleState.STOPPING] until [fetchSucceeded] or [fetchFailed] says how the fetch went.
   */
  fun stop(): List<LifecycleEffect> {
    if (!beginStop()) return emptyList()
    return when (mode) {
      RecordingMode.STREAM ->
          listOf(
              LifecycleEffect.StopStreams,
              LifecycleEffect.Finish(RecordingPhase.STOPPED),
          )
              .also { state = LifecycleState.FINISHED }
      RecordingMode.ON_DEVICE -> listOf(LifecycleEffect.FetchFromTracker)
    }
  }

  /** The fetch is complete and verified: the tracker's copy may go and the recording is stopped. */
  fun fetchSucceeded(): List<LifecycleEffect> {
    if (state != LifecycleState.STOPPING) return emptyList()
    state = LifecycleState.FINISHED
    return listOf(
        LifecycleEffect.RemoveFromTracker,
        LifecycleEffect.Finish(RecordingPhase.STOPPED),
    )
  }

  /** The fetch did not complete: keep the tracker's copy and offer a retry. */
  fun fetchFailed(): List<LifecycleEffect> {
    if (state != LifecycleState.STOPPING) return emptyList()
    state = LifecycleState.FETCH_PENDING
    return listOf(LifecycleEffect.Finish(RecordingPhase.FETCH_PENDING))
  }

  /** A new attempt from [LifecycleState.FETCH_PENDING]; its outcome is decided the same way. */
  fun retryFetch(): List<LifecycleEffect> {
    if (state != LifecycleState.FETCH_PENDING) return emptyList()
    state = LifecycleState.STOPPING
    return listOf(LifecycleEffect.FetchFromTracker)
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

  /**
   * The pipeline of a recording that is still open is stuck: drop its streams so everything can be
   * attached again on the same directory. The recording is not finished and its state does not move.
   */
  fun restart(): List<LifecycleEffect> {
    if (state != LifecycleState.RECORDING) return emptyList()
    return when (mode) {
      RecordingMode.STREAM -> listOf(LifecycleEffect.StopStreamsAsync)
      RecordingMode.ON_DEVICE -> emptyList()
    }
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
