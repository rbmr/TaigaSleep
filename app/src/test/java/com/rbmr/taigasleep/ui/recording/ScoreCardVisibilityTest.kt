package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.sync.UploadFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score card is the only place that says a result is coming, so a night without one must not
 * show it. The status card already says what such a night is.
 */
class ScoreCardVisibilityTest {
  @Test
  fun aNightWithoutAResultShowsTheCardOnlyWhenItsAnalysisFinished() {
    // The first analysis is queued or running: there is nothing to summarise yet.
    assertFalse(showsScoreCard(RecordingStatus.PROCESSING, hasResult = false))
    assertFalse(showsScoreCard(RecordingStatus.NOT_ANALYZED, hasResult = false))
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

  @Test
  fun theAnalysisButtonNeedsAFinishedAnalysis() {
    fun state(status: RecordingStatus, failure: UploadFailure? = null) =
        RecordingDetailUiState(recording = viewOf(status, failure))

    assertTrue(showsAnalysisChoice(state(RecordingStatus.READY)))
    assertTrue(showsAnalysisChoice(state(RecordingStatus.FAILED, UploadFailure.ANALYSIS)))
    // The first analysis is still running: it has not analysed anything yet.
    assertFalse(showsAnalysisChoice(state(RecordingStatus.PROCESSING)))
    assertFalse(showsAnalysisChoice(state(RecordingStatus.NOT_ANALYZED)))
    assertFalse(showsAnalysisChoice(state(RecordingStatus.FAILED, UploadFailure.UPLOAD)))
  }

  private fun viewOf(status: RecordingStatus, failure: UploadFailure?) =
      RecordingView(
          key = "r1",
          localId = null,
          remoteId = "r1",
          startUtcMs = 1_790_000_000_000,
          endUtcMs = 1_790_003_600_000,
          durationMs = 3_600_000,
          device = "Tracker",
          status = status,
          progress = 0f,
          failure = failure,
          errorDetail = null,
          warning = null,
          analyses = emptyList(),
          selectedAnalysisId = null,
          backup = BackupState(),
          local = null,
          remote = null,
          resultDownloaded = false,
      )
}
