package com.rbmr.taigasleep.tracker.polar

import com.rbmr.taigasleep.tracker.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PolarRecordingModesTest {
  @Test
  fun hrOnlyCanStayOnTheStrap() {
    assertEquals(
        setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE),
        polarRecordingModes(setOf("hr")),
    )
    assertEquals(
        setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE),
        polarRecordingModes(setOf("rr")),
    )
  }

  @Test
  fun ecgOrAccHasToStream() {
    assertEquals(setOf(RecordingMode.STREAM), polarRecordingModes(setOf("hr", "ecg")))
    assertEquals(setOf(RecordingMode.STREAM), polarRecordingModes(setOf("acc")))
  }

  @Test
  fun nothingSelectedOffersNoMode() {
    assertEquals(emptySet<RecordingMode>(), polarRecordingModes(emptySet()))
  }
}
