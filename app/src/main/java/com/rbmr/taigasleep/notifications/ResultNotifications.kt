package com.rbmr.taigasleep.notifications

import android.content.Context
import android.content.Intent
import androidx.annotation.PluralsRes
import com.rbmr.taigasleep.R

/**
 * The summaries of upload and analysis results, one notification per type, updated in place.
 *
 * A result updates the notification of its own type instead of posting another: the counts it
 * shows are the outcomes of that type since it was last opened or dismissed, and [clear] and
 * [dismiss] are what those two actions call. An outcome of a type that Android blocks is not
 * counted and never appears, so a summary lists only outcomes Android allows and nothing is posted
 * while nothing allowed is waiting.
 *
 * Nothing here waits or polls: the work that learns an outcome tells this class.
 */
class ResultNotifications internal constructor(
    private val context: Context,
    private val access: NotificationAccess,
) {
  constructor(context: Context) : this(context, PhoneNotificationAccess(context))

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

  /** The app was opened from a results notification: every summary starts again. */
  fun clear() {
    synchronized(lock) {
      prefs.edit().clear().commit()
      Notifier.cancel(context, Notifier.resultsId(NotificationType.UPLOADS))
      Notifier.cancel(context, Notifier.resultsId(NotificationType.ANALYSES))
    }
  }

  /** The summary of [type] was dismissed: its counts start again, the other summary is untouched. */
  fun dismiss(type: NotificationType) {
    val outcomes = Outcome.entries.filter { it.type == type }
    if (outcomes.isEmpty()) return
    synchronized(lock) {
      prefs.edit().apply { outcomes.forEach { remove(it.key) } }.commit()
      Notifier.cancel(context, Notifier.resultsId(type))
    }
  }

  /** Counts one outcome of an allowed type and shows the summary it belongs to. */
  private fun count(outcome: Outcome) {
    if (!on(outcome.type)) return
    synchronized(lock) {
      prefs.edit().putInt(outcome.key, prefs.getInt(outcome.key, 0) + 1).commit()
      val text = text(outcome.type)
      if (text.isEmpty()) return
      Notifier.post(
          context,
          Notifier.resultsId(outcome.type),
          Notifier.results(context, outcome.type, text, Notifier.APP_ACTIVITY),
      )
    }
  }

  /** One plain line per outcome of [type] that has happened, joined in the app's separator. */
  private fun text(type: NotificationType): String =
      Outcome.entries
          .filter { it.type == type }
          .map { quantity(it.plural, prefs.getInt(it.key, 0)) }
          .filter { it.isNotEmpty() }
          .joinToString(" · ")

  private fun quantity(id: Int, count: Int): String =
      if (count == 0) "" else context.resources.getQuantityString(id, count, count)

  private fun on(type: NotificationType): Boolean =
      notificationTypeState(type, access).on

  private enum class Outcome(
      val type: NotificationType,
      val key: String,
      @PluralsRes val plural: Int,
  ) {
    UPLOADED(NotificationType.UPLOADS, "uploaded", R.plurals.results_uploaded),
    UPLOAD_FAILED(NotificationType.UPLOADS, "upload_failed", R.plurals.results_upload_failed),
    ANALYSIS_FINISHED(
        NotificationType.ANALYSES,
        "analysis_finished",
        R.plurals.results_analysis_finished,
    ),
    ANALYSIS_FAILED(
        NotificationType.ANALYSES,
        "analysis_failed",
        R.plurals.results_analysis_failed,
    ),
  }

  private companion object {
    const val PREFS = "notification_results"

    /** One lock for every instance: a summary belongs to the app, not to one caller. */
    val lock = Any()
  }
}

/**
 * The app was opened from a results notification: its counts are done. A start from anywhere
 * else changes nothing.
 */
fun resultsOpened(context: Context, intent: Intent?) {
  if (intent?.getBooleanExtra(Notifier.EXTRA_RESULTS_OPENED, false) == true) {
    ResultNotifications(context).clear()
  }
}
