package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.AnalysisSummaryDto
import com.rbmr.taigasleep.backend.AnalysisSummaryScoreDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.backend.StageSummaryDto
import com.rbmr.taigasleep.backend.StageTimelineDto
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.UploadStore
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.storeScope
import com.rbmr.taigasleep.sync.db.testDatabase
import com.rbmr.taigasleep.sync.recordingIdOf
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingMergeTest {
  private val account = "https://sleep.example|me@example.com"

  private lateinit var database: TaigaDatabase
  private lateinit var scope: CoroutineScope
  private val accounts = MutableStateFlow<String?>(account)

  @Before
  fun setUp() {
    database = testDatabase()
    scope = storeScope()
  }

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
  }

  /** The upload state of the local recording, in the app's own tables. */
  private fun uploadStore() = UploadStore(database.uploadStates(), accounts, scope)

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

  /** The payloads the app keeps for the local night, as the database publishes them. */
  private fun cacheWith(vararg runs: AnalysisDto): Map<String, List<AnalysisDto>> {
    val results = ResultCache(database.analysisResults())
    runs.forEach { run -> runBlocking { results.save(account, "local", run.id, run) } }
    return runBlocking { results.observe(account).first() }
  }

  @Test
  fun aFinishedLocalResultSurvivesWithoutTheServer() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            phase = UploadPhase.DONE,
        )
    val cache =
        cacheWith(
            succeeded("a1", algorithm = "baseline", version = "1.0.0").full(
                segments = segmentSeconds(),
                sleepScore = 81.0,
            )
        )

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to record),
                null,
                cachedResults = cache,
            )
            .single()

    assertEquals("local", night.localId)
    // The recording keeps the phone's id on the server.
    assertEquals("local", night.remoteId)
    assertEquals("a1", night.analysisId)
    assertEquals(RecordingStatus.READY, night.status)
    assertEquals(81.0, night.sleepScore ?: 0.0, 0.001)
    assertEquals(2, night.segments.size)
  }

  @Test
  fun aFinishedUploadShowsTheCachedResultAndNotAFactOfItsOwn() {
    val store = uploadStore()
    runBlocking { store.markDone("local") }
    val uploaded = runBlocking { store.record("local")!! }
    val cache =
        cacheWith(
            succeeded("a1", algorithm = "baseline", version = "1.0.0")
                .full(segments = segmentSeconds(), sleepScore = 81.0)
        )

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to uploaded),
                null,
                cachedResults = cache,
            )
            .single()

    assertEquals(RecordingStatus.READY, night.status)
    assertEquals("a1", night.analysisId)
    assertEquals(81.0, night.sleepScore ?: 0.0, 0.001)
    assertEquals(2, night.segments.size)
  }

  @Test
  fun aCachedResultDoesNotBringBackARunTheServerNoLongerLists() {
    val record =
        UploadRecord(id = "local", onServer = true, phase = UploadPhase.PROCESSING)
    val remote = summaries(RecordingSummaryDto(recording = recording(), analyses = emptyList()))
    val cache = cacheWith(succeeded("a1", algorithm = "baseline", version = "1.0.0").full())

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to record),
                remote,
                cachedResults = cache,
            )
            .single()

    // The server lists no run for this night any more, so its old payload is not shown as the row.
    assertEquals(RecordingStatus.PROCESSING, night.status)
    assertNull(night.analysisId)
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
  fun aNightTheServerNoLongerHasIsLocalOnlyAndKeepsItsSavedResult() {
    val store = uploadStore()
    runBlocking {
      store.markDone("local")
      store.markLocal("local")
    }
    val cache =
        cacheWith(
            succeeded("a1", algorithm = "baseline", version = "1.0.0")
                .full(segments = segmentSeconds(), sleepScore = 81.0)
        )

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to runBlocking { store.record("local")!! }),
                null,
                cachedResults = cache,
            )
            .single()

    assertFalse(night.onServer)
    assertNull(night.remoteId)
    assertEquals(RecordingStatus.LOCAL_ONLY, night.status)
    assertEquals("a1", night.analysisId)
    assertEquals(81.0, night.sleepScore ?: 0.0, 0.001)
  }

  @Test
  fun aRemoteOnlyNightTheServerNoLongerHasLeavesTheList() {
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(id = "gone"),
                analyses = listOf(succeeded("a1")),
            ),
            RecordingSummaryDto(recording = recording(id = "kept")),
        )
    val history = RemoteHistoryCache(database.remoteRecordings(), account = { account })
    runBlocking { history.save(account, remote) }

    runBlocking { history.remove(account, "gone") }

    val nights =
        buildRecordings(emptyList(), emptyMap(), remote, hiddenRemoteIds = history.removed.value)
    assertEquals(listOf("remote-kept"), nights.map { it.key })
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
  fun `a pending-delete id is filtered from the remote list`() {
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(id = "gone"),
                analyses = listOf(succeeded("a1")),
            ),
            RecordingSummaryDto(recording = recording(id = "kept")),
        )

    val nights = buildRecordings(emptyList(), emptyMap(), remote, hiddenRemoteIds = setOf("gone"))

    assertEquals(listOf("remote-kept"), nights.map { it.key })
  }

  @Test
  fun `a local night stays while its server copy is being deleted`() {
    val remote = summaries(RecordingSummaryDto(recording = recording(id = "local")))

    val night = buildRecordings(listOf(local), emptyMap(), remote, hiddenRemoteIds = setOf("local")).single()

    // The server copy is on its way out; the night on the phone is still a night.
    assertEquals("local", night.localId)
    assertNull(night.remoteId)
    assertEquals(RecordingStatus.LOCAL_ONLY, night.status)
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
            phase = UploadPhase.DONE,
        )
    val cache =
        cacheWith(succeeded("a1", algorithm = "baseline", version = "1.0.0").full())

    val night =
        buildRecordings(
                listOf(trimmed),
                mapOf("local" to record),
                null,
                cachedResults = cache,
            )
            .single()

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

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to record),
                remote,
                selectedAnalysisIds = mapOf("local" to "failed"),
            )
            .single()

    assertEquals(RecordingStatus.READY, night.status)
    assertEquals("done", night.analysisId)
  }

  @Test
  fun aSelectedRunningRunDoesNotHideAFinishedResult() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
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

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to record),
                remote,
                selectedAnalysisIds = mapOf("local" to "running"),
            )
            .single()

    assertEquals(RecordingStatus.READY, night.status)
    assertEquals("done", night.analysisId)
  }

  @Test
  fun aCachedResultStaysDownloadedWhenTheServerSummaryArrives() {
    val record =
        UploadRecord(
            id = "local",
            onServer = true,
            phase = UploadPhase.DONE,
            manualAnalysisIds = listOf("a1"),
        )
    val remote =
        summaries(
            RecordingSummaryDto(
                recording = recording(),
                analyses = listOf(succeeded("a1", algorithm = "baseline", version = "1.1.0")),
            )
        )
    val cache = cacheWith(succeeded("a1", algorithm = "baseline", version = "1.1.0").full())

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to record),
                remote,
                cachedResults = cache,
            )
            .single()

    assertTrue(night.resultDownloaded)
    assertEquals(AnalysisOrigin.MANUAL, night.selectedAnalysis?.origin)
  }

  @Test
  fun aManualRunIsMarkedAsManual() {
    val record =
        UploadRecord(
            id = "local",
            phase = UploadPhase.DONE,
            manualAnalysisIds = listOf("a1"),
        )
    val cache = cacheWith(succeeded("a1", algorithm = "sleep-ecg", version = "1.0.0").full())

    val night =
        buildRecordings(
                listOf(local),
                mapOf("local" to record),
                null,
                cachedResults = cache,
            )
            .single()

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
                    items =
                        listOf(
                            ResultItemDto(
                                kind = "score",
                                id = "sleep_quality",
                                label = "Sleep score",
                                group = "score",
                                unit = "%",
                                value = 75.0,
                            )
                        ),
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
                  scores =
                      buildList {
                        sleepScore?.let { add(AnalysisSummaryScoreDto("sleep_quality", it)) }
                        quality?.let { add(AnalysisSummaryScoreDto("data_quality", it)) }
                      },
                  stages =
                      StageSummaryDto(
                          epoch_seconds = 30,
                          count = segments.sumOf { it.count },
                          segments = segments,
                      ),
              ),
      )

  /** The same run with its full payload, as the result cache keeps a downloaded result. */
  private fun AnalysisDto.full(
      segments: List<StageSegmentDto> = listOf(StageSegmentDto("light", 1)),
      sleepScore: Double = 80.0,
  ): AnalysisDto =
      copy(
          summary = null,
          result =
              AnalysisResultDto(
                  model = algorithm,
                  model_version = version,
                  stages =
                      StageTimelineDto(
                          epoch_seconds = 30,
                          count = segments.sumOf { it.count },
                          segments = segments,
                      ),
                  items =
                      listOf(
                          ResultItemDto(
                              kind = "score",
                              id = "sleep_quality",
                              label = "Sleep score",
                              group = "score",
                              unit = "%",
                              value = sleepScore,
                          )
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
