package com.rbmr.taigasleep.notifications

import android.content.Context
import android.content.Intent
import com.rbmr.taigasleep.R

/**
 * The one notification for upload and analysis results.
 *
 * A result updates that notification in place instead of posting another: the counts it shows are
 * the outcomes since it was last opened or dismissed, and [clear] is what those two actions call.
 * An outcome of a type that is off, or that Android blocks, is not counted and never appears, so a
 * summary lists only the outcomes whose switch is on and nothing is posted when nothing on is
 * waiting.
 *
 * Nothing here waits or polls: the work that learns an outcome tells this class.
 */
class ResultNotifications internal constructor(
    private val context: Context,
    private val settings: NotificationSettings,
    private val access: NotificationAccess,
) {
  constructor(context: Context) :
      this(context, NotificationSettings(context), PhoneNotificationAccess(context))

  private val prefs =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** A recording reached the server. */
  fun uploadFinished() = count(Outcome.UPLOADED)

  /** An upload stopped for good. */
  fun uploadFailed() = count(Outcome.UPLOAD_FAILED)

  /** An analysis finished and its result is stored. */
  fun analysisFinished() = count(Outcome.ANALYSIS_FINISHED)

  /** An analysis failed. */
  fun analysisFailed() = count(Outcome.ANALYSIS_FAILED)

  /** The notification was opened or dismissed: the next outcome starts a new summary. */
  fun clear() {
    synchronized(lock) {
      prefs.edit().clear().commit()
      Notifier.cancel(context, Notifier.RESULTS_ID)
    }
  }

  /** Counts one outcome of an enabled type and shows the summary it belongs to. */
  private fun count(outcome: Outcome) {
    if (!on(outcome.type)) return
    synchronized(lock) {
      prefs.edit().putInt(outcome.key, count(outcome.key) + 1).commit()
      val text = text()
      if (text.isEmpty()) return
      Notifier.post(
          context,
          Notifier.RESULTS_ID,
          Notifier.results(context, text, Notifier.APP_ACTIVITY),
      )
    }
  }

  /** One plain line per outcome that is on and has happened, joined in the app's separator. */
  private fun text(): String {
    val uploadsOn = on(NotificationType.UPLOADS)
    val analysesOn = on(NotificationType.ANALYSES)
    val uploaded = count(Outcome.UPLOADED.key)
    val uploadFailed = count(Outcome.UPLOAD_FAILED.key)
    val finished = count(Outcome.ANALYSIS_FINISHED.key)
    val analysisFailed = count(Outcome.ANALYSIS_FAILED.key)
    return buildList {
          if (uploadsOn) add(quantity(R.plurals.results_uploaded, uploaded))
          if (analysesOn) add(quantity(R.plurals.results_analysis_finished, finished))
          if (uploadsOn) add(quantity(R.plurals.results_upload_failed, uploadFailed))
          if (analysesOn) add(quantity(R.plurals.results_analysis_failed, analysisFailed))
        }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
  }

  private fun quantity(id: Int, count: Int): String =
      if (count == 0) "" else context.resources.getQuantityString(id, count, count)

  private fun on(type: NotificationType): Boolean =
      notificationTypeState(type, settings, access).on

  private fun count(key: String): Int = prefs.getInt(key, 0)

  private enum class Outcome(val type: NotificationType, val key: String) {
    UPLOADED(NotificationType.UPLOADS, "uploaded"),
    UPLOAD_FAILED(NotificationType.UPLOADS, "upload_failed"),
    ANALYSIS_FINISHED(NotificationType.ANALYSES, "analysis_finished"),
    ANALYSIS_FAILED(NotificationType.ANALYSES, "analysis_failed"),
  }

  private companion object {
    const val PREFS = "notification_results"

    /** One lock for every instance: the summary belongs to the app, not to one caller. */
    val lock = Any()
  }
}

/**
 * The app was opened from the results notification: its counts are done. A start from anywhere
 * else changes nothing.
 */
fun resultsOpened(context: Context, intent: Intent?) {
  if (intent?.getBooleanExtra(Notifier.EXTRA_RESULTS_OPENED, false) == true) {
    ResultNotifications(context).clear()
  }
}
