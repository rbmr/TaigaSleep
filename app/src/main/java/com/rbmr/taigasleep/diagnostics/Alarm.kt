package com.rbmr.taigasleep.diagnostics

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * A slow inexact alarm that keeps the log alive while the JVM timer is paused (screen off, Doze).
 * `setAndAllowWhileIdle` needs no permission; the system batches it, so it costs about four
 * wakeups an hour at most. How late it fires is itself evidence of Doze or standby deferral.
 */
object DiagnosticsAlarm {
  const val INTERVAL_MS = 15L * 60_000
  private const val REQUEST_CODE = 4242
  const val EXTRA_AT = "scheduled_elapsed_ms"

  fun schedule(context: Context) {
    try {
      val at = SystemClock.elapsedRealtime() + INTERVAL_MS
      val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context, at))
    } catch (_: Exception) {}
  }

  private fun pending(context: Context, at: Long): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          REQUEST_CODE,
          Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_AT, at),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
}

/** Declared in the manifest so a fired alarm also records that the process was started for it. */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    Diagnostics.alarmFired(intent.getLongExtra(DiagnosticsAlarm.EXTRA_AT, 0L))
    DiagnosticsAlarm.schedule(context)
  }
}
