package com.rbmr.taigasleep.night

import com.rbmr.taigasleep.remote.AnalysisSummaryDto
import com.rbmr.taigasleep.remote.QualityDto
import com.rbmr.taigasleep.remote.SessionDto
import com.rbmr.taigasleep.remote.SessionListDto
import com.rbmr.taigasleep.remote.SessionSummaryDto
import com.rbmr.taigasleep.remote.SleepQualityDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.remote.StageSummaryDto
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightMergeTest {
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
        NightRecord(
            id = "local",
            remoteId = "s1",
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "a1",
                        algorithm = "baseline",
                        version = "1.0.0",
                        status = AnalysisStatus.DONE,
                        segments = segmentSeconds(),
                        sleepScore = 81.0,
                    )
                ),
            selectedAnalysisId = "a1",
        )

    val night = buildNights(listOf(local), mapOf("local" to record), null).single()

    assertEquals("local", night.localId)
    assertEquals("s1", night.remoteId)
    assertEquals("a1", night.analysisId)
    assertEquals(NightStatus.READY, night.status)
    assertEquals(81.0, night.sleepScore ?: 0.0, 0.001)
    assertEquals(2, night.segments.size)
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
                                    sleep_quality = SleepQualityDto(score = 92.0),
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

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(NightStatus.READY, night.status)
    assertEquals("s1", night.remoteId)
    assertEquals("a1", night.analysisId)
    assertEquals(92.0, night.sleepScore ?: 0.0, 0.001)
    assertEquals(1L, night.segments.last().count.toLong())
  }

  @Test
  fun aDataQualityScoreIsNeverShownAsTheSleepScore() {
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
                        analyses =
                            listOf(
                                AnalysisSummaryDto(
                                    id = "a1",
                                    status = "done",
                                    quality = QualityDto(score = 88.0),
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

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(NightStatus.READY, night.status)
    assertNull(night.sleepScore)
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

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(NightStatus.PROCESSING, night.status)
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

    val night = buildNights(emptyList(), emptyMap(), remote).single()

    assertEquals("remote-s9", night.key)
    assertNull(night.localId)
    assertEquals("s9", night.remoteId)
    assertTrue(night.isDone)
    assertEquals(1800_000L, night.durationMs)
  }

  @Test
  fun aNightWithSeveralAnalysesCanHaveAResultAndKeepWorking() {
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
                        analyses =
                            listOf(
                                AnalysisSummaryDto(
                                    id = "auto",
                                    model = "baseline",
                                    model_version = "1.0.0",
                                    status = "done",
                                    stages =
                                        StageSummaryDto(
                                            epoch_seconds = 30,
                                            count = 1,
                                            segments = listOf(StageSegmentDto("light", 1)),
                                        ),
                                ),
                                AnalysisSummaryDto(
                                    id = "better",
                                    model = "sleep-ecg",
                                    model_version = "1.0.0",
                                    status = "done",
                                    stages =
                                        StageSummaryDto(
                                            epoch_seconds = 30,
                                            count = 1,
                                            segments = listOf(StageSegmentDto("deep", 1)),
                                        ),
                                ),
                                AnalysisSummaryDto(
                                    id = "running",
                                    model = "wav2sleep",
                                    model_version = "1.1.0",
                                    status = "running",
                                ),
                            ),
                    )
                )
        )

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(3, night.analyses.size)
    assertEquals(NightStatus.READY, night.status)
    // SleepECG is preferred over the baseline fallback, and the running wav2sleep is not selected.
    assertEquals("better", night.analysisId)
  }

  @Test
  fun aManualResultWinsTheDefaultSelection() {
    val record =
        NightRecord(
            id = "local",
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "auto",
                        algorithm = "wav2sleep",
                        version = "1.1.0",
                        status = AnalysisStatus.DONE,
                    ),
                    AnalysisRun(
                        id = "manual",
                        algorithm = "baseline",
                        version = "1.0.0",
                        status = AnalysisStatus.DONE,
                        origin = AnalysisOrigin.MANUAL,
                    ),
                ),
        )

    val night = buildNights(listOf(local), mapOf("local" to record), null).single()

    assertEquals("manual", night.analysisId)
  }

  @Test
  fun removedRawDataStillLeavesAUsableNight() {
    val trimmed = local.copy(hasRaw = false)
    val record =
        NightRecord(
            id = "local",
            remoteId = "s1",
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "a1",
                        algorithm = "baseline",
                        version = "1.0.0",
                        status = AnalysisStatus.DONE,
                    )
                ),
        )

    val night = buildNights(listOf(trimmed), mapOf("local" to record), null).single()

    assertFalse(night.hasRaw)
    assertEquals(NightStatus.READY, night.status)
    assertTrue(night.onServer)
  }

  @Test
  fun aLiveSessionIsRecordingRegardlessOfItsServerCopy() {
    val recording = local.copy(state = SessionState.RECORDING)
    val record =
        NightRecord(
            id = "local",
            remoteId = "s1",
            backup = BackupState(phase = BackupPhase.QUEUED),
            analyses = emptyList(),
        )

    val night = buildNights(listOf(recording), mapOf("local" to record), null).single()

    assertEquals(NightStatus.RECORDING, night.status)
    assertTrue(night.isRecording)
  }

  @Test
  fun aSelectedFailedRunDoesNotHideAFinishedResult() {
    val record =
        NightRecord(
            id = "local",
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "done",
                        algorithm = "wav2sleep",
                        version = "1.1.0",
                        status = AnalysisStatus.DONE,
                    ),
                    AnalysisRun(
                        id = "failed",
                        algorithm = "sleep-ecg",
                        version = "1.0.0",
                        status = AnalysisStatus.FAILED,
                        origin = AnalysisOrigin.MANUAL,
                    ),
                ),
            selectedAnalysisId = "failed",
        )

    val night = buildNights(listOf(local), mapOf("local" to record), null).single()

    assertEquals(NightStatus.READY, night.status)
    assertEquals("done", night.analysisId)
  }

  @Test
  fun aSelectedRunningRunDoesNotHideAFinishedResult() {
    val record =
        NightRecord(
            id = "local",
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "done",
                        algorithm = "baseline",
                        version = "1.0.0",
                        status = AnalysisStatus.DONE,
                    ),
                    AnalysisRun(
                        id = "running",
                        algorithm = "wav2sleep",
                        version = "1.1.0",
                        status = AnalysisStatus.RUNNING,
                        origin = AnalysisOrigin.MANUAL,
                    ),
                ),
            selectedAnalysisId = "running",
        )

    val night = buildNights(listOf(local), mapOf("local" to record), null).single()

    assertEquals(NightStatus.READY, night.status)
    assertEquals("done", night.analysisId)
  }

  @Test
  fun aCachedResultStaysDownloadedWhenTheServerSummaryArrives() {
    val record =
        NightRecord(
            id = "local",
            remoteId = "s1",
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "a1",
                        algorithm = "baseline",
                        version = "1.1.0",
                        status = AnalysisStatus.DONE,
                        origin = AnalysisOrigin.MANUAL,
                        jobId = "j1",
                        resultDownloaded = true,
                    )
                ),
            selectedAnalysisId = "a1",
        )
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
                        analyses =
                            listOf(
                                AnalysisSummaryDto(
                                    id = "a1",
                                    model = "baseline",
                                    model_version = "1.1.0",
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

    val night = buildNights(listOf(local), mapOf("local" to record), remote).single()

    assertTrue(night.resultDownloaded)
    assertEquals("j1", night.jobId)
    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun pocketBaseDatesAreParsedAsUtc() {
    val ms = parsePocketBaseDate("2026-10-25 14:00:00.000Z")

    assertTrue(ms > 0)
    assertTrue(formatNightDate(ms) != "—")
  }

  private fun segmentSeconds() =
      listOf(
          StageSegmentDto("wake", 1),
          StageSegmentDto("light", 2),
      )
}
