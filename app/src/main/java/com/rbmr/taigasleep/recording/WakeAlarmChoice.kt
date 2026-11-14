package com.rbmr.taigasleep.recording

/** What the alarm does with the running recording when it rings. A global setting, [OPTIONAL]. */
enum class EndRecordingOnAlarm(val wire: String) {
  ALWAYS("always"),
  OPTIONAL("optional"),
  NEVER("never");

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
