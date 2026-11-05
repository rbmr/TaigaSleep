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
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.recordingIdOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMergeTest {
  private val local =
      Recording(
          id = "local",
          dir = File("/tmp/local"),
          state = RecordingPhase.STOPPED,
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
            onServer = true,
            analysisId = "a1",
            phase = UploadPhase.DONE,
            model = "baseline",
            modelVersion = "1.0.0",
            segments = segmentSeconds(),
            sleepScore = 81.0,
        )

    val night = buildRecordings(listOf(local), mapOf("local" to record), null).single()

    assertEquals("local", night.localId)
    // The recording keeps the phone's id on the server.
    assertEquals("local", night.remoteId)
    assertEquals("a1", night.analysisId)
    assertEquals(RecordingStatus.READY, night.status)
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

    val night = buildRecordings(listOf(local), emptyMap(), remote).single()

    assertEquals(RecordingStatus.READY, night.status)
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

    val night = buildRecordings(listOf(local), emptyMap(), remote).single()

    assertEquals(RecordingStatus.READY, night.status)
    assertNull(night.sleepScore)
  }

  @Test
  fun anUploadedNightWithoutAResultIsProcessing() {
    val remote = summaries(RecordingSummaryDto(recording = recording(), analyses = emptyList()))

    val night = buildRecordings(listOf(local), emptyMap(), remote).single()

    assertEquals(RecordingStatus.PROCESSING, night.status)
  }

  @Test
  fun aServerCopyThatIsNotSealedYetIsStillUploading() {
    val remote =
        summaries(RecordingSummaryDto(recording = recording(complete = false), analyses = emptyList()))

    val night = buildRecordings(listOf(local), emptyMap(), remote).single()

    assertEquals(RecordingStatus.UPLOADING, night.status)
    assertEquals(BackupPhase.UPLOADING, night.backup.phase)
  }

  @Test
  fun aLocalNightWithoutAServerCopyHasNoRemoteId() {
    val night = buildRecordings(listOf(local), emptyMap(), null).single()

    assertNull(night.remoteId)
    assertEquals(RecordingStatus.LOCAL_ONLY, night.status)
  }

  @Test
  fun theServerCopyIsMatchedToTheLocalNightById() {
    val remote =
        summaries(
            RecordingSummaryDto(recording = recording(id = "local")),
            RecordingSummaryDto(recording = recording(id = "other")),
        )

    val nights = buildRecordings(listOf(local), emptyMap(), remote)

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

    val night = buildRecordings(emptyList(), emptyMap(), remote).single()

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

    val night = buildRecordings(listOf(local), emptyMap(), remote).single()

    assertEquals(3, night.analyses.size)
    assertEquals(RecordingStatus.READY, night.status)
    // SleepECG is preferred over the baseline fallback, and the running wav2sleep is not selected.
    assertEquals("better", night.analysisId)
  }

  @Test
  fun aManualResultWinsTheDefaultSelection() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            phase = UploadPhase.PROCESSING,
            manualAnalysisIds = listOf("manual"),
        )
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses =
                    listOf(
                        succeeded("auto", algorithm = "wav2sleep", version = "1.1.0"),
                        succeeded("manual", algorithm = "baseline", version = "1.0.0"),
                    ),
            )
        )

    val night = buildRecordings(listOf(local), mapOf("local" to record), remote).single()

    assertEquals("manual", night.analysisId)
  }

  @Test
  fun removedRawDataStillLeavesAUsableNight() {
    val trimmed = local.copy(hasRaw = false)
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            analysisId = "a1",
            phase = UploadPhase.DONE,
            model = "baseline",
            modelVersion = "1.0.0",
        )

    val night = buildRecordings(listOf(trimmed), mapOf("local" to record), null).single()

    assertFalse(night.hasRaw)
    assertEquals(RecordingStatus.READY, night.status)
    assertTrue(night.onServer)
  }

  @Test
  fun aLiveSessionIsRecordingRegardlessOfItsServerCopy() {
    val recording = local.copy(state = RecordingPhase.RECORDING)
    val record =
        UploadRecord(id = "local", onServer = true, phase = UploadPhase.QUEUED)

    val night = buildRecordings(listOf(recording), mapOf("local" to record), null).single()

    assertEquals(RecordingStatus.RECORDING, night.status)
    assertTrue(night.isRecording)
  }

  @Test
  fun aSelectedFailedRunDoesNotHideAFinishedResult() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            analysisId = "failed",
            phase = UploadPhase.FAILED,
            failure = UploadFailure.ANALYSIS,
            errorDetail = "worker failed",
        )
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses =
                    listOf(
                        succeeded("done", algorithm = "wav2sleep", version = "1.1.0"),
                        AnalysisDto(
                            id = "failed",
                            algorithm = "sleep-ecg",
                            version = "1.0.0",
                            status = "failed",
                        ),
                    ),
            )
        )

    val night = buildRecordings(listOf(local), mapOf("local" to record), remote).single()

    assertEquals(RecordingStatus.READY, night.status)
    assertEquals("done", night.analysisId)
  }

  @Test
  fun aSelectedRunningRunDoesNotHideAFinishedResult() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            analysisId = "running",
            phase = UploadPhase.PROCESSING,
        )
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses =
                    listOf(
                        succeeded("done", algorithm = "baseline", version = "1.0.0"),
                        AnalysisDto(
                            id = "running",
                            algorithm = "wav2sleep",
                            version = "1.1.0",
                            status = "running",
                        ),
                    ),
            )
        )

    val night = buildRecordings(listOf(local), mapOf("local" to record), remote).single()

    assertEquals(RecordingStatus.READY, night.status)
    assertEquals("done", night.analysisId)
  }

  @Test
  fun aCachedResultStaysDownloadedWhenTheServerSummaryArrives() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            analysisId = "a1",
            phase = UploadPhase.DONE,
            model = "baseline",
            modelVersion = "1.1.0",
            resultDownloaded = true,
            manualAnalysisIds = listOf("a1"),
        )
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses = listOf(succeeded("a1", algorithm = "baseline", version = "1.1.0")),
            )
        )

    val night = buildRecordings(listOf(local), mapOf("local" to record), remote).single()

    assertTrue(night.resultDownloaded)
    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun aManualRunIsMarkedAsManual() {
    val record =
        UploadRecord(
            id = "local",
            analysisId = "a1",
            phase = UploadPhase.DONE,
            model = "sleep-ecg",
            modelVersion = "1.0.0",
            manualAnalysisIds = listOf("a1"),
        )

    val night = buildRecordings(listOf(local), mapOf("local" to record), null).single()

    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun aManualIdMarksTheSameRemoteRunAsManual() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            phase = UploadPhase.PROCESSING,
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

    val night = buildRecordings(listOf(local), mapOf("local" to record), remote).single()

    assertEquals("a1", night.analysisId)
    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun apiDatesAreParsedAsUtc() {
    assertEquals(1_792_938_601_000L, parseApiDate("2026-10-25T14:30:01Z"))
    assertEquals(1_792_938_601_250L, parseApiDate("2026-10-25T14:30:01.250Z"))
    assertEquals(1_792_938_601_000L, parseApiDate("2026-10-25T16:30:01+02:00"))
    assertTrue(formatRecordingDate(parseApiDate("2026-10-25T14:00:00Z")) != "—")
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
