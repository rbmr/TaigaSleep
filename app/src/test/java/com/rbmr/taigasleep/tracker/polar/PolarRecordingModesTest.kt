package com.rbmr.taigasleep.tracker.polar

import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PolarRecordingModesTest {
  @Test
  fun hrOnlyCanStayOnTheStrap() {
    assertEquals(
        setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE),
        polarRecordingModes(StreamConfig(ecg = false, acc = false)),
    )
  }

  @Test
  fun ecgOrAccHasToStream() {
    assertEquals(
        setOf(RecordingMode.STREAM),
        polarRecordingModes(StreamConfig(ecg = true, acc = false)),
    )
    assertEquals(
        setOf(RecordingMode.STREAM),
        polarRecordingModes(StreamConfig(ecg = false, acc = true)),
    )
  }
}
