package com.rbmr.taigasleep.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.EventLog
import com.rbmr.taigasleep.tracker.SystemClockSource

/**
 * Fires every few minutes while a recording is active and restarts the service if it, or its
 * heartbeat, is gone. A service that is still there with a stale heartbeat has its pipeline rebuilt,
 * since only that can recover a stuck one. Works in "Unrestricted" and "Optimized" battery modes; the
 * readiness gate exists because it does not work in "Restricted".
 */
class WatchdogReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val prefs = RecordingPrefs.get(context)
    val id = prefs.activeSessionId ?: return
    schedule(context)
    val age = System.currentTimeMillis() - prefs.heartbeatMs
    val action = RecordingRecovery.onWatchdog(id, RecordingService.running, age) ?: return
    Diagnostics.log(
        "watchdog_fire",
        mapOf(
            "action" to action.name.lowercase(),
            "service_running" to RecordingService.running,
            "last_heartbeat_age_s" to age / 1000,
        ),
    )
    val reason =
        when (action) {
          RecoveryAction.RESTART ->
              "service stalled, rebuilding the pipeline (heartbeat ${age / 1000}s old)"
          else -> "service not running, restarting (heartbeat ${age / 1000}s old)"
        }
    Log.w(TAG, reason)
    try {
      EventLog.append(
          context.appContainer.recordingStore.dir(id),
          SystemClockSource.phoneNs(),
          SystemClockSource.elapsedNs(),
          "watchdog",
          reason,
      )
    } catch (_: Exception) {}
    try {
      val serviceAction =
          when (action) {
            RecoveryAction.RESTART -> RecordingService.ACTION_RESTART
            else -> RecordingService.ACTION_RESUME
          }
      context.startForegroundService(RecordingService.intent(context, serviceAction))
    } catch (e: Exception) {
      Log.e(TAG, "could not restart service", e)
    }
  }

  companion object {
    private const val TAG = "WatchdogReceiver"
    private const val INTERVAL_MS = 5 * 60_000L

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
