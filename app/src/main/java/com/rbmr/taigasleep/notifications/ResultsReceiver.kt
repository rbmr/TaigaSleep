package com.rbmr.taigasleep.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The results notification was dismissed: the counts it showed are done, so the next outcome starts
 * a new summary. This starts no work of its own.
 */
class ResultsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_DISMISSED) return
    ResultNotifications(context).clear()
  }

  companion object {
    const val ACTION_DISMISSED = "com.rbmr.taigasleep.notifications.RESULTS_DISMISSED"
  }
}
