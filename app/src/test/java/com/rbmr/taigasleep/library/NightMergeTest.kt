package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.AnalysisSummaryDto
import com.rbmr.taigasleep.backend.QualityDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.SleepQualityDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.backend.StageSummaryDto
import com.rbmr.taigasleep.backend.StageTimelineDto
import com.rbmr.taigasleep.recording.store.SessionInfo
import com.rbmr.taigasleep.recording.store.SessionState
import com.rbmr.taigasleep.sync.recordingIdOf
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
            onServer = true,
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
    // The recording keeps the phone's id on the server.
    assertEquals("local", night.remoteId)
    assertEquals("a1", night.analysisId)
    assertEquals(NightStatus.READY, night.status)
    assertEquals(81.0, night.sleepScore ?: 0.0, 0.001)
    assertEquals(2, night.segments.size)
  }

  @Test
  fun aRemoteDoneAnalysisWinsOverALocalPendingState() {
    val remote =
        summaries(
            RecordingSummaryDto(
                recording =
                    recording(
                        device = "Polar H10",
                        startedAt = "2026-10-25T14:00:00Z",
                        durationS = 3600.0,
                    ),
                analyses =
                    listOf(
                        succeeded(
                            "a1",
                            quality = 88.0,
                            sleepScore = 92.0,
                            segments = listOf(StageSegmentDto("light", 3), StageSegmentDto("deep", 1)),
                        )
                    ),
            )
        )

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(NightStatus.READY, night.status)
    assertEquals("local", night.remoteId)
    assertEquals("a1", night.analysisId)
    assertEquals(92.0, night.sleepScore ?: 0.0, 0.001)
    assertEquals(1L, night.segments.last().count.toLong())
  }

  @Test
  fun aDataQualityScoreIsNeverShownAsTheSleepScore() {
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses = listOf(succeeded("a1", quality = 88.0)),
            )
        )

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(NightStatus.READY, night.status)
    assertNull(night.sleepScore)
  }

  @Test
  fun anUploadedNightWithoutAResultIsProcessing() {
    val remote = summaries(RecordingSummaryDto(recording = recording(), analyses = emptyList()))

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(NightStatus.PROCESSING, night.status)
  }

  @Test
  fun aServerCopyThatIsNotSealedYetIsStillUploading() {
    val remote =
        summaries(RecordingSummaryDto(recording = recording(complete = false), analyses = emptyList()))

    val night = buildNights(listOf(local), emptyMap(), remote).single()

    assertEquals(NightStatus.UPLOADING, night.status)
    assertEquals(BackupPhase.UPLOADING, night.backup.phase)
  }

  @Test
  fun aLocalNightWithoutAServerCopyHasNoRemoteId() {
    val night = buildNights(listOf(local), emptyMap(), null).single()

    assertNull(night.remoteId)
    assertEquals(NightStatus.LOCAL_ONLY, night.status)
  }

  @Test
  fun theServerCopyIsMatchedToTheLocalNightById() {
    val remote =
        summaries(
            RecordingSummaryDto(recording = recording(id = "local")),
            RecordingSummaryDto(recording = recording(id = "other")),
        )

    val nights = buildNights(listOf(local), emptyMap(), remote)

    assertEquals(listOf("local", "remote-other"), nights.map { it.key })
    assertEquals("local", nights.first { it.key == "local" }.localId)
  }

  @Test
  fun aRemoteOnlyNightGetsItsOwnKey() {
    val remote =
        summaries(
            RecordingSummaryDto(
                recording =
                    recording(
                        id = "20261025_140000-ab12",
                        device = "Polar H10",
                        startedAt = "2026-10-25T14:00:00Z",
                        durationS = 1800.0,
                    ),
                analyses = listOf(succeeded("a9")),
            )
        )

    val night = buildNights(emptyList(), emptyMap(), remote).single()

    assertEquals("remote-20261025_140000-ab12", night.key)
    assertNull(night.localId)
    assertEquals("20261025_140000-ab12", night.remoteId)
    assertEquals("20261025_140000-ab12", recordingIdOf(night.key))
    assertTrue(night.isDone)
    assertEquals(1800_000L, night.durationMs)
  }

  @Test
  fun aNightWithSeveralAnalysesCanHaveAResultAndKeepWorking() {
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses =
                    listOf(
                        succeeded("auto", algorithm = "baseline", version = "1.0.0"),
                        succeeded(
                            "better",
                            algorithm = "sleep-ecg",
                            version = "1.0.0",
                            segments = listOf(StageSegmentDto("deep", 1)),
                        ),
                        AnalysisDto(
                            id = "running",
                            algorithm = "wav2sleep",
                            version = "1.1.0",
                            status = "running",
                        ),
                    ),
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
            onServer = true,
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
            onServer = true,
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
            onServer = true,
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "a1",
                        algorithm = "baseline",
                        version = "1.1.0",
                        status = AnalysisStatus.DONE,
                        origin = AnalysisOrigin.MANUAL,
                        resultDownloaded = true,
                    )
                ),
            selectedAnalysisId = "a1",
        )
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses = listOf(succeeded("a1", algorithm = "baseline", version = "1.1.0")),
            )
        )

    val night = buildNights(listOf(local), mapOf("local" to record), remote).single()

    assertTrue(night.resultDownloaded)
    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun aManualRunIsMarkedAsManual() {
    val record =
        NightRecord(
            id = "local",
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            analyses =
                listOf(
                    AnalysisRun(
                        id = "a1",
                        algorithm = "sleep-ecg",
                        version = "1.0.0",
                        status = AnalysisStatus.DONE,
                    )
                ),
            selectedAnalysisId = "a1",
            manualAnalysisIds = listOf("a1"),
        )

    val night = buildNights(listOf(local), mapOf("local" to record), null).single()

    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun aManualIdMarksTheSameRemoteRunAsManual() {
    val record =
        NightRecord(
            id = "local",
            onServer = true,
            backup = BackupState(phase = BackupPhase.BACKED_UP, progress = 1f),
            manualAnalysisIds = listOf("a1"),
        )
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses =
                    listOf(
                        succeeded(
                            "a1",
                            algorithm = "sleep-ecg",
                            version = "1.0.0",
                            segments = listOf(StageSegmentDto("nrem", 1)),
                        )
                    ),
            )
        )

    val night = buildNights(listOf(local), mapOf("local" to record), remote).single()

    assertEquals("a1", night.analysisId)
    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun apiDatesAreParsedAsUtc() {
    assertEquals(1_792_938_601_000L, parseApiDate("2026-10-25T14:30:01Z"))
    assertEquals(1_792_938_601_250L, parseApiDate("2026-10-25T14:30:01.250Z"))
    assertEquals(1_792_938_601_000L, parseApiDate("2026-10-25T16:30:01+02:00"))
    assertTrue(formatNightDate(parseApiDate("2026-10-25T14:00:00Z")) != "—")
  }

  @Test
  fun anUnreadableApiDateIsZero() {
    assertEquals(0L, parseApiDate(""))
    assertEquals(0L, parseApiDate("2026-10-25 14:00:00.000Z"))
    assertEquals(0L, parseApiDate("not a date"))
  }

  @Test
  fun aRunReadsItsStagesFromTheResultWhenThereIsOneAndFromTheSummaryOtherwise() {
    val summarized = succeeded("a1", segments = listOf(StageSegmentDto("light", 2)), sleepScore = 70.0)
    val full =
        summarized.copy(
            summary = null,
            result =
                AnalysisResultDto(
                    stages = StageTimelineDto(count = 3, segments = listOf(StageSegmentDto("deep", 3))),
                    sleep_quality = SleepQualityDto(score = 75.0),
                ),
        )

    assertEquals(listOf(StageSegmentDto("light", 2)), summarized.toRun().segments)
    assertEquals(70.0, summarized.toRun().sleepScore ?: 0.0, 0.001)
    assertEquals(listOf(StageSegmentDto("deep", 3)), full.toRun(downloaded = true).segments)
    assertEquals(75.0, full.toRun().sleepScore ?: 0.0, 0.001)
    assertTrue(full.toRun(downloaded = true).resultDownloaded)
  }

  @Test
  fun everyApiStatusMapsToARunStatus() {
    fun status(wire: String) = AnalysisDto(id = "a", status = wire).toRun().status

    assertEquals(AnalysisStatus.QUEUED, status("queued"))
    assertEquals(AnalysisStatus.RUNNING, status("running"))
    assertEquals(AnalysisStatus.DONE, status("succeeded"))
    assertEquals(AnalysisStatus.FAILED, status("failed"))
  }

  private fun recording(
      id: String = "local",
      complete: Boolean = true,
      device: String = "",
      startedAt: String = "",
      durationS: Double = 0.0,
  ) =
      RecordingDto(
          id = id,
          state = "stopped",
          device = device,
          started_at = startedAt,
          duration_s = durationS,
          complete = complete,
      )

  private fun succeeded(
      id: String,
      algorithm: String = "baseline",
      version: String = "1.0.0",
      quality: Double? = null,
      sleepScore: Double? = null,
      segments: List<StageSegmentDto> = listOf(StageSegmentDto("light", 1)),
  ) =
      AnalysisDto(
          id = id,
          algorithm = algorithm,
          version = version,
          status = "succeeded",
          summary =
              AnalysisSummaryDto(
                  quality = quality?.let { QualityDto(score = it) },
                  sleep_quality = sleepScore?.let { SleepQualityDto(score = it) },
                  stages =
                      StageSummaryDto(
                          epoch_seconds = 30,
                          count = segments.sumOf { it.count },
                          segments = segments,
                      ),
              ),
      )

  private fun summaries(vararg items: RecordingSummaryDto) =
      RecordingSummaryListDto(items = items.toList())

  private fun segmentSeconds() =
      listOf(
          StageSegmentDto("wake", 1),
          StageSegmentDto("light", 2),
      )
}
