package com.rbmr.taigasleep.ui

import com.rbmr.taigasleep.remote.AnalysisSummaryDto
import com.rbmr.taigasleep.remote.QualityDto
import com.rbmr.taigasleep.remote.SessionDto
import com.rbmr.taigasleep.remote.SessionListDto
import com.rbmr.taigasleep.remote.SessionSummaryDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.remote.StageSummaryDto
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionState
import com.rbmr.taigasleep.upload.UploadPhase
import com.rbmr.taigasleep.upload.UploadRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDataTest {
  private val local =
      SessionInfo(
          id = "local",
          dir = File("/tmp/local"),
          state = SessionState.STOPPED,
          startUtcMs = 1_790_000_000_000,
          endUtcMs = 1_790_003_600_000,
          sizeBytes = 10,
          sensorName = "Polar H10",
      )

  @Test
  fun aFinishedLocalResultSurvivesWithoutTheServer() {
    val record =
        UploadRecord(
            id = "local",
            remoteId = "s1",
            analysisId = "a1",
            phase = UploadPhase.DONE,
            segments = segmentSeconds(),
            qualityScore = 81.0,
        )

    val item = buildNightItems(listOf(local), mapOf("local" to record), null).single()

    assertEquals("local", item.localId)
    assertEquals("s1", item.remoteId)
    assertEquals("a1", item.analysisId)
    assertEquals(UploadPhase.DONE, item.phase)
    assertEquals(81.0, item.qualityScore ?: 0.0, 0.001)
    assertEquals(2, item.segments.size)
  }

  @Test
  fun aRemoteDoneAnalysisWinsOverALocalPendingState() {
    val remote =
        SessionListDto(
            items =
                listOf(
                    SessionSummaryDto(
                        session =
                            SessionDto(
                                id = "s1",
                                client_session_id = "local",
                                device = "Polar H10",
                                started_at = "2026-10-25 14:00:00.000Z",
                                duration_s = 3600.0,
                                upload_status = "uploaded",
                            ),
                        analyses =
                            listOf(
                                AnalysisSummaryDto(
                                    id = "a1",
                                    session = "s1",
                                    model = "baseline",
                                    model_version = "1.0.0",
                                    status = "done",
                                    quality = QualityDto(score = 88.0),
                                    stages =
                                        StageSummaryDto(
                                            epoch_seconds = 30,
                                            count = 4,
                                            segments =
                                                listOf(
                                                    StageSegmentDto("light", 3),
                                                    StageSegmentDto("deep", 1),
                                                ),
                                        ),
                                )
                            ),
                    )
                )
        )

    val item = buildNightItems(listOf(local), emptyMap(), remote).single()

    assertEquals(UploadPhase.DONE, item.phase)
    assertEquals("s1", item.remoteId)
    assertEquals("a1", item.analysisId)
    assertEquals(88.0, item.qualityScore ?: 0.0, 0.001)
    assertEquals(1L, item.segments.last().count.toLong())
  }

  @Test
  fun anUploadedNightWithoutAResultIsProcessing() {
    val remote =
        SessionListDto(
            items =
                listOf(
                    SessionSummaryDto(
                        session =
                            SessionDto(
                                id = "s1",
                                client_session_id = "local",
                                upload_status = "uploaded",
                            ),
                        analyses = emptyList(),
                    )
                )
        )

    val item = buildNightItems(listOf(local), emptyMap(), remote).single()

    assertEquals(UploadPhase.PROCESSING, item.phase)
  }

  @Test
  fun aRemoteOnlyNightGetsItsOwnKey() {
    val remote =
        SessionListDto(
            items =
                listOf(
                    SessionSummaryDto(
                        session =
                            SessionDto(
                                id = "s9",
                                client_session_id = "gone",
                                device = "Polar H10",
                                started_at = "2026-10-25 14:00:00.000Z",
                                duration_s = 1800.0,
                                upload_status = "uploaded",
                            ),
                        analyses =
                            listOf(
                                AnalysisSummaryDto(
                                    id = "a9",
                                    status = "done",
                                    stages =
                                        StageSummaryDto(
                                            epoch_seconds = 30,
                                            count = 1,
                                            segments = listOf(StageSegmentDto("light", 1)),
                                        ),
                                )
                            ),
                    )
                )
        )

    val item = buildNightItems(emptyList(), emptyMap(), remote).single()

    assertEquals("remote-s9", item.key)
    assertNull(item.localId)
    assertEquals("s9", item.remoteId)
    assertTrue(item.isDone)
    assertEquals(1800_000L, item.durationMs)
  }

  @Test
  fun pocketBaseDatesAreParsedAsUtc() {
    val ms = parsePocketBaseDate("2026-10-25 14:00:00.000Z")

    assertTrue(ms > 0)
    assertTrue(formatNightDate(ms) != "—")
  }

  private fun night(key: String, phase: UploadPhase) =
      NightItem(
          key = key,
          localId = key,
          remoteId = null,
          analysisId = null,
          jobId = null,
          startUtcMs = 0,
          durationMs = 0,
          device = "H10",
          phase = phase,
          progress = 0f,
          failure = null,
          errorDetail = null,
          warning = null,
          epochSeconds = 30,
          segments = emptyList(),
          qualityScore = null,
          model = null,
          modelVersion = null,
          resultDownloaded = false,
          local = null,
      )

  private fun segmentSeconds() =
      listOf(
          StageSegmentDto("wake", 1),
          StageSegmentDto("light", 2),
      )
}
