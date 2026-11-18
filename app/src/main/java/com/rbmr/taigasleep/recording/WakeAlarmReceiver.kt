package com.rbmr.taigasleep.recording

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires the alarm at the end of the wake-up window. The alarm is an alarm clock, so this receiver
 * also runs after the process was killed or the recording service is gone; that is the whole point
 * of scheduling the end separately from the live decision. It only starts the ringing service: the
 * silent notification and the sound are that service's.
 */
class WakeAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_DISMISS) {
      // A notification posted before the ringing service existed still stops the alarm.
      WakeAlarm.dismiss(context)
      WakeAlarm.stopNow(context)
      return
    }
    // The window is spent: nothing arms it again for this recording.
    RecordingPrefs.get(context).clearArmedWake()
    WakeAlarm.fire(context, WakeReason.WINDOW_END, WakeAlarmService.SOURCE_WINDOW_END)
  }

  companion object {
    const val ACTION_DISMISS = "com.rbmr.taigasleep.recording.WAKE_DISMISS"

    fun dismiss(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WakeAlarmReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
  }
}
