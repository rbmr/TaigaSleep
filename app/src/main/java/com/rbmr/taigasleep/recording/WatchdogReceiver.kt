package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.diagnostics.Diagnostics
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.rbmr.taigasleep.sensor.SystemClockSource
import com.rbmr.taigasleep.storage.EventLog
import com.rbmr.taigasleep.storage.SessionStore

/**
 * Fires every few minutes while a session is active and restarts the service if it, or its
 * heartbeat, is gone. Works in "Unrestricted" and "Optimized" battery modes; the readiness gate
 * exists because it does not work in "Restricted".
 */
class WatchdogReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val prefs = RecordingPrefs.get(context)
    val id = prefs.activeSessionId ?: return
    schedule(context)
    val age = System.currentTimeMillis() - prefs.heartbeatMs
    Diagnostics.log(
        "watchdog_fire",
        mapOf("service_running" to RecordingService.running, "last_heartbeat_age_s" to age / 1000),
    )
    if (RecordingService.running && age < STALE_MS) return
    Log.w(TAG, "service missing or stale (heartbeat ${age / 1000}s), restarting")
    try {
      EventLog.append(
          SessionStore.of(context).dir(id),
          SystemClockSource.phoneNs(),
          SystemClockSource.elapsedNs(),
          "watchdog",
          "service not running, restarting (heartbeat ${age / 1000}s old)",
      )
    } catch (_: Exception) {}
    try {
      context.startForegroundService(RecordingService.intent(context, RecordingService.ACTION_RESUME))
    } catch (e: Exception) {
      Log.e(TAG, "could not restart service", e)
    }
  }

  companion object {
    private const val TAG = "WatchdogReceiver"
    private const val INTERVAL_MS = 5 * 60_000L
    private const val STALE_MS = 150_000L

    private fun pending(context: Context) =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context) {
      val alarms = context.getSystemService(AlarmManager::class.java)
      val at = SystemClock.elapsedRealtime() + INTERVAL_MS
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
      } catch (_: SecurityException) {
        // Exact alarms not permitted (Android 12L and older without the permission).
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
      }
    }

    fun cancel(context: Context) {
      context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }
  }
}
