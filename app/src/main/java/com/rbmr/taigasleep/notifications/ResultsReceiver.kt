package com.rbmr.taigasleep.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A results summary was dismissed: the counts it showed are done, so the next outcome of its type
 * starts a new summary. This starts no work of its own.
 */
class ResultsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_DISMISSED) return
    val type =
        intent.getStringExtra(Notifier.EXTRA_RESULTS_TYPE)?.let { name ->
          NotificationType.entries.firstOrNull { it.name == name }
        } ?: return
    ResultNotifications(context).dismiss(type)
  }

  companion object {
    const val ACTION_DISMISSED = "com.rbmr.taigasleep.notifications.RESULTS_DISMISSED"
  }
}
