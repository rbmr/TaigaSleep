package com.rbmr.taigasleep.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.diagnostics.Diagnostics

/**
 * Picks a recording up again after a reboot, with the same resume action the watchdog and the app
 * start use, so the service stays the only thing that decides whether a recording can go on.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val live =
        RecordingRecovery.onBoot(
            context.appContainer.recordingStore,
            RecordingPrefs.get(context).activeSessionId,
        ) ?: return
    Diagnostics.log("boot_resume", mapOf("recording" to live.recordingId))
    try {
      RecordingService.send(context, RecordingService.ACTION_RESUME)
    } catch (e: Exception) {
      // A refused background start must not take the receiver down with it.
      Log.e(TAG, "could not resume the recording after boot", e)
    }
  }

  private companion object {
    const val TAG = "BootReceiver"
  }
}
