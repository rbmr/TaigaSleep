package com.rbmr.taigasleep.recording

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.diagnostics.Diagnostics

/**
 * Fires the alarm at the end of the wake-up window. The alarm is an alarm clock, so this receiver
 * also runs after the process was killed or the recording service is gone; that is the whole point
 * of scheduling the end separately from the live decision.
 */
class WakeAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      ACTION_DISMISS -> WakeAlarm.dismiss(context)
      ACTION_STOP -> WakeAlarm.stopRecording(context)
      else -> {
        Diagnostics.log("wake_alarm", mapOf("source" to "window_end"))
        // The window is spent: nothing arms it again for this recording.
        RecordingPrefs.get(context).clearArmedWake()
        WakeAlarm.fire(context, WakeReason.WINDOW_END)
      }
    }
  }

  companion object {
    const val ACTION_DISMISS = "com.rbmr.taigasleep.recording.WAKE_DISMISS"
    const val ACTION_STOP = "com.rbmr.taigasleep.recording.WAKE_STOP"

    fun dismiss(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WakeAlarmReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun stop(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, WakeAlarmReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
  }
}
