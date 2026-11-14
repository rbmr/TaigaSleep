package com.rbmr.taigasleep.recording

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
  fun `optional is the default setting`() {
    assertEquals(EndRecordingOnAlarm.OPTIONAL, EndRecordingOnAlarm.fromWire(null))
    assertEquals(EndRecordingOnAlarm.OPTIONAL, EndRecordingOnAlarm.fromWire("unknown"))
  }
}
