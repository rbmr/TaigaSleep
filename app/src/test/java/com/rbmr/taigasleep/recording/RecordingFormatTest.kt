package com.rbmr.taigasleep.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFormatTest {
  @Test
  fun aRateIsRoundedToWholeHz() {
    assertEquals("ECG 130 Hz", RecordingFormat.rate("ECG", 129.6))
    assertEquals("Movement 51 Hz", RecordingFormat.rate("Movement", 51.0))
    assertEquals("Movement 50 Hz", RecordingFormat.rate("Movement", 50.4))
  }

  @Test
  fun theRoundedRateIsTheSameNumberTheScreenFormats() {
    assertEquals(130L, RecordingFormat.rateHz(129.6))
    assertEquals(51L, RecordingFormat.rateHz(51.0))
  }
}
