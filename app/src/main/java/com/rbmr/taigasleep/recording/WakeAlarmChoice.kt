package com.rbmr.taigasleep.recording

import androidx.annotation.StringRes
import com.rbmr.taigasleep.R

/** What the alarm does with the running recording when it rings. A global setting, [OPTIONAL]. */
enum class EndRecordingOnAlarm(val wire: String, @StringRes val label: Int) {
  ALWAYS("always", R.string.wake_end_always),
  OPTIONAL("optional", R.string.wake_end_optional),
  NEVER("never", R.string.wake_end_never);

  companion object {
    fun fromWire(value: String?): EndRecordingOnAlarm =
        entries.firstOrNull { it.wire == value } ?: OPTIONAL
  }
}

/** One button of the ringing alarm: end the recording, or only stop the alarm. */
enum class WakeAlarmAction {
  STOP,
  DISMISS,
}

/**
 * The buttons the ringing alarm offers, from the setting and whether a recording is running.
 *
 * Always offers Stop; Optional offers Stop and Dismiss; Never offers Dismiss. With no recording
 * running there is nothing to end, so every setting offers Dismiss alone.
 */
fun wakeAlarmActions(
    setting: EndRecordingOnAlarm,
    recordingRunning: Boolean,
): List<WakeAlarmAction> =
    when {
      !recordingRunning -> listOf(WakeAlarmAction.DISMISS)
      setting == EndRecordingOnAlarm.ALWAYS -> listOf(WakeAlarmAction.STOP)
      setting == EndRecordingOnAlarm.OPTIONAL ->
          listOf(WakeAlarmAction.STOP, WakeAlarmAction.DISMISS)
      else -> listOf(WakeAlarmAction.DISMISS)
    }

/**
 * What the alarm screen shows: why it rang, what its buttons do with the recording, and which
 * buttons it has. Without a recording there is nothing to explain, so [details] is null.
 */
data class WakeScreen(
    @StringRes val reason: Int,
    @StringRes val details: Int?,
    val stop: Boolean,
    val dismiss: Boolean,
)

fun wakeScreen(
    reason: WakeReason,
    actions: List<WakeAlarmAction>,
    recordingRunning: Boolean,
): WakeScreen {
  val stop = WakeAlarmAction.STOP in actions
  val dismiss = WakeAlarmAction.DISMISS in actions
  val details =
      when {
        stop && dismiss -> R.string.wake_screen_offer_both
        stop -> R.string.wake_screen_stop_ends
        recordingRunning -> R.string.wake_screen_dismiss_running
        else -> null
      }
  return WakeScreen(reason.text, details, stop, dismiss)
}
