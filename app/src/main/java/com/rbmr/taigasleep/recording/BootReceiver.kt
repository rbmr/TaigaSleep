package com.rbmr.taigasleep.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.RecordingStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Picks a recording up again after a reboot, with the same resume action the watchdog and the app
 * start use, so the service stays the only thing that decides whether a recording can go on.
 *
 * The marker and the recordings are read from disk, so the work runs off the main thread the
 * broadcast arrives on. The receiver asks for the pending result and finishes it when the decision
 * and the start are done, so the process lives long enough for them.
 */
class BootReceiver(
    /** The work a boot asks for: the app resumes the interrupted recording, a test says what ran. */
    private val boot: (Context) -> Unit,
) : BroadcastReceiver() {
  /** The system creates the receiver, so a boot asks for the recording the reboot interrupted. */
  constructor() : this({ context -> resumeInterruptedRecording(context) })

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val pending = goAsync()
    worker.execute {
      try {
        boot(context.applicationContext)
      } finally {
        pending.finish()
      }
    }
  }

  private companion object {
    /** One thread for the decision; a boot arrives once per process. */
    val worker: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "boot-resume") }
  }
}

/**
 * What a boot hands back, as the receiver always decided it: the recording the marker names, noted
 * in the diagnostics log and resumed through the service. Nothing when the reboot interrupted no
 * recording.
 */
internal fun resumeAfterBoot(
    store: RecordingStore,
    activeSessionId: String?,
    log: (String) -> Unit,
    resume: () -> Unit,
) {
  val live = RecordingRecovery.onBoot(store, activeSessionId) ?: return
  log(live.recordingId)
  resume()
}

/** Reads the marker and the recordings, then hands the interrupted one back to the service. */
private fun resumeInterruptedRecording(context: Context) {
  resumeAfterBoot(
      store = context.appContainer.recordingStore,
      activeSessionId = RecordingPrefs.get(context).activeSessionId,
      log = { id -> Diagnostics.log("boot_resume", mapOf("recording" to id)) },
      resume = {
        try {
          RecordingService.send(context, RecordingService.ACTION_RESUME)
        } catch (e: Exception) {
          // A refused background start must not take the receiver down with it.
          Log.e(TAG, "could not resume the recording after boot", e)
        }
      },
  )
}

private const val TAG = "BootReceiver"
