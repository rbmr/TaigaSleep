package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.RecordingMode

/**
 * What a stopped recording is doing before it is safely finished. The [Step]s apply in the order
 * they are declared, and the mode decides which of them a recording passes through: an on-device
 * night is fetched from the tracker between stopping it and saving; a streamed one is already on
 * the phone when it stops.
 *
 * Owned by [RecordingControl] and published through [RecordingRepository]; the Home screen only
 * draws it.
 */
data class FinishingState(
    val recordingId: String,
    val sensorName: String,
    val mode: RecordingMode,
    val step: Step,
    /** How far the on-device fetch got; null while there is nothing to report. */
    val fetch: ImportProgress? = null,
) {
  enum class Step {
    STOPPING_TRACKER,
    FETCHING,
    SAVING,
    DONE,
  }

  val steps: List<Step>
    get() =
        when (mode) {
          RecordingMode.STREAM -> listOf(Step.STOPPING_TRACKER, Step.SAVING, Step.DONE)
          RecordingMode.ON_DEVICE ->
              listOf(Step.STOPPING_TRACKER, Step.FETCHING, Step.SAVING, Step.DONE)
        }

  /** The night is on the phone and the tracker can be taken off. */
  val done: Boolean
    get() = step == Step.DONE
}
