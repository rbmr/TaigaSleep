package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.library.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score card is the only place that says a result is coming, so a night without one must not
 * show it. The status card already says what such a night is.
 */
class ScoreCardVisibilityTest {
  @Test
  fun aNightWithoutAResultShowsTheCardOnlyWhileTheServerAnalysesIt() {
    // The night is on the server and its analysis is queued or running: its result is coming.
    assertTrue(showsScoreCard(RecordingStatus.PROCESSING, hasResult = false))
    // The library knows the analysis finished, even when its payload is not read yet.
    assertTrue(showsScoreCard(RecordingStatus.READY, hasResult = false))

    // Nothing is on the server, so no result is coming from it.
    assertFalse(showsScoreCard(RecordingStatus.LOCAL_ONLY, hasResult = false))
    assertFalse(showsScoreCard(RecordingStatus.QUEUED, hasResult = false))
    assertFalse(showsScoreCard(RecordingStatus.UPLOADING, hasResult = false))
    assertFalse(showsScoreCard(RecordingStatus.RECORDING, hasResult = false))
    // The upload or the analysis failed: it does not continue by itself.
    assertFalse(showsScoreCard(RecordingStatus.FAILED, hasResult = false))
  }

  @Test
  fun aNightWithAResultShowsTheCardInEverySyncState() {
    RecordingStatus.entries.forEach { status ->
      assertTrue(showsScoreCard(status, hasResult = true))
    }
  }
}
