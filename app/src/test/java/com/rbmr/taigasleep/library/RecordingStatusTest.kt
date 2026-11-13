package com.rbmr.taigasleep.library

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStatusTest {
  private fun run(status: AnalysisStatus) =
      Analysis(id = status.name, algorithm = "a", version = "1", status = status)

  @Test
  fun `no analysis and none requested is not analyzed`() {
    assertEquals(RecordingStatus.NOT_ANALYZED, deriveFromRuns(emptyList(), analysisRequested = false))
  }

  @Test
  fun `no listed analysis while one is being requested is processing`() {
    assertEquals(RecordingStatus.PROCESSING, deriveFromRuns(emptyList(), analysisRequested = true))
  }

  @Test
  fun `a queued or running analysis is processing`() {
    for (status in listOf(AnalysisStatus.QUEUED, AnalysisStatus.RUNNING)) {
      assertEquals(
          status.name,
          RecordingStatus.PROCESSING,
          deriveFromRuns(listOf(run(status)), analysisRequested = false),
      )
    }
  }

  @Test
  fun `a succeeded analysis is ready`() {
    val runs = listOf(run(AnalysisStatus.FAILED), run(AnalysisStatus.DONE))
    assertEquals(RecordingStatus.READY, deriveFromRuns(runs, analysisRequested = false))
  }

  @Test
  fun `only failed analyses is failed`() {
    assertEquals(
        RecordingStatus.FAILED,
        deriveFromRuns(listOf(run(AnalysisStatus.FAILED)), analysisRequested = false),
    )
  }
}
