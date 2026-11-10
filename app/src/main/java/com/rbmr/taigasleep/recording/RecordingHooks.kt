package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.tracker.RecordingMode

/**
 * What [RecordingService] needs to know or tell the rest of the app without depending on it. The
 * app wiring supplies the implementation once, in `Application.onCreate` ([install]), so recording
 * never imports the packages that decide these things: the preventing-shutdown flow (`guard`) and
 * the upload queue (`sync`).
 */
interface RecordingHooks {
  /**
   * Whether a recording may start in [mode]. False refuses the start, and the service tells the
   * user that the phone is not ready. On-device recording needs no battery settings.
   */
  fun mayStart(context: Context, mode: RecordingMode): Boolean

  /**
   * One line naming the phone settings that decide whether a recording survives (battery optimisation,
   * standby bucket, background restriction). It is written to the recording's events when they
   * change, so two equal lines mean equal settings.
   */
  fun readinessSummary(context: Context): String

  /**
   * A recording ended and was kept: stopped, force stopped or interrupted, but not discarded.
   * [recordingId] is its id in the local store.
   */
  fun onRecordingFinished(context: Context, recordingId: String)

  /**
   * A recording that was waiting for the rest of its night got it from the tracker. The samples
   * arrived after anything that was queued before, so the recording starts over as an upload.
   * [recordingId] is its id in the local store.
   */
  fun onFetchCompleted(context: Context, recordingId: String)

  /**
   * The screen that rings a wake-up alarm. `recording` schedules the alarm and posts it; where its
   * screen lives is the app wiring's business, so this package never depends on `ui`.
   */
  fun wakeAlarmScreen(context: Context): Intent

  companion object {
    @Volatile private var installed: RecordingHooks? = null

    /** Sets the hooks every recording uses. Call before any recording can start. */
    fun install(hooks: RecordingHooks) {
      installed = hooks
    }

    /** The installed hooks. A missing installation is a wiring bug, so it fails loudly. */
    fun current(): RecordingHooks =
        checkNotNull(installed) { "RecordingHooks.install was not called by the app wiring" }
  }
}
