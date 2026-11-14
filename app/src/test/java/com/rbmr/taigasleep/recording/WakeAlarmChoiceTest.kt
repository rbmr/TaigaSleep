package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The buttons of the ringing alarm, from the setting and whether a recording is running. */
class WakeAlarmChoiceTest {
  @Test
  fun `always offers only stop while a recording runs`() {
    assertEquals(
        listOf(WakeAlarmAction.STOP),
        wakeAlarmActions(EndRecordingOnAlarm.ALWAYS, recordingRunning = true),
    )
  }

  @Test
  fun `optional offers stop and dismiss while a recording runs`() {
    assertEquals(
        listOf(WakeAlarmAction.STOP, WakeAlarmAction.DISMISS),
        wakeAlarmActions(EndRecordingOnAlarm.OPTIONAL, recordingRunning = true),
    )
  }

  @Test
  fun `never offers only dismiss while a recording runs`() {
    assertEquals(
        listOf(WakeAlarmAction.DISMISS),
        wakeAlarmActions(EndRecordingOnAlarm.NEVER, recordingRunning = true),
    )
  }

  @Test
  fun `without a recording every setting offers only dismiss`() {
    for (setting in EndRecordingOnAlarm.entries) {
      assertEquals(
          "$setting has nothing to end",
          listOf(WakeAlarmAction.DISMISS),
          wakeAlarmActions(setting, recordingRunning = false),
      )
    }
  }

  @Test
  fun `the screen explains what its buttons do with the recording`() {
    val both = wakeScreen(WakeReason.SIGNAL, actions(EndRecordingOnAlarm.OPTIONAL, true), true)
    assertEquals(WakeScreen(R.string.wake_reason_signal, R.string.wake_screen_offer_both, true, true), both)

    val stop = wakeScreen(WakeReason.WINDOW_END, actions(EndRecordingOnAlarm.ALWAYS, true), true)
    assertEquals(WakeScreen(R.string.wake_reason_end, R.string.wake_screen_stop_ends, true, false), stop)

    val dismiss = wakeScreen(WakeReason.SIGNAL, actions(EndRecordingOnAlarm.NEVER, true), true)
    assertEquals(R.string.wake_screen_dismiss_running, dismiss.details)
    assertEquals(false to true, dismiss.stop to dismiss.dismiss)
  }

  @Test
  fun `without a recording the screen has only dismiss and nothing to explain`() {
    val screen = wakeScreen(WakeReason.WINDOW_END, actions(EndRecordingOnAlarm.ALWAYS, false), false)

    assertEquals(WakeScreen(R.string.wake_reason_end, null, stop = false, dismiss = true), screen)
  }

  @Test
  fun `optional is the default setting`() {
    assertEquals(EndRecordingOnAlarm.OPTIONAL, EndRecordingOnAlarm.fromWire(null))
    assertEquals(EndRecordingOnAlarm.OPTIONAL, EndRecordingOnAlarm.fromWire("unknown"))
  }

  private fun actions(setting: EndRecordingOnAlarm, running: Boolean) =
      wakeAlarmActions(setting, recordingRunning = running)
}
