package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.night.toNightRecord
import com.rbmr.taigasleep.night.buildNights
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.AnalysisListDto
import com.rbmr.taigasleep.remote.AnalysisResultDto
import com.rbmr.taigasleep.remote.RecordingDto
import com.rbmr.taigasleep.remote.RecordingSummaryDto
import com.rbmr.taigasleep.remote.RecordingSummaryListDto
import com.rbmr.taigasleep.remote.SleepQualityDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.remote.StageTimelineDto
import com.rbmr.taigasleep.remote.StubTaigaClient
import com.rbmr.taigasleep.remote.TaigaHttpException
import com.rbmr.taigasleep.storage.SessionInfo
import com.rbmr.taigasleep.storage.SessionState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MemoryRecords : UploadRecordsStorage {
  var json: String? = null

  override fun read(): String? = json

  override fun write(json: String) {
    this.json = json
  }
}

private fun recording(id: String = "r1", complete: Boolean = true) =
    RecordingDto(
        id = id,
        state = "stopped",
        device = "H10",
        started_at = "2026-10-23T20:30:00Z",
        duration_s = 3600.0,
        complete = complete,
    )

private fun analysis(
    id: String,
    status: String,
    created: String = "2026-10-24T05:00:00Z",
    updated: String = created,
    error: String = "",
) =
    AnalysisDto(
        id = id,
        recording = "r1",
        algorithm = "baseline",
        version = "1.0.0",
        status = status,
        created = created,
        updated = updated,
        error = error,
    )

private fun withResult(analysis: AnalysisDto, score: Double = 81.0) =
    analysis.copy(
        result =
            AnalysisResultDto(
                contract_version = 3,
                model = "baseline",
                model_version = "1.0.0",
                stages = StageTimelineDto(count = 2, segments = listOf(StageSegmentDto("light", 2))),
                sleep_quality = SleepQualityDto(score = score),
            )
    )

/** What one read of a recording stores: terminal and pending analyses, stale ids, vanished runs. */
class AnalysisSyncTest {
  private lateinit var root: File
  private lateinit var results: ResultCache
  private lateinit var store: UploadStore

  @Before
  fun setUp() {
    root = Files.createTempDirectory("taiga-sync").toFile()
    results = ResultCache(root)
    store = UploadStore(MemoryRecords())
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  private fun sync(client: FakeClient) = AnalysisSync(client, store, results)

  private fun refresh(client: FakeClient, preferred: List<String?> = emptyList(), key: String = "r1") =
      runBlocking { sync(client).refresh(key, "r1", preferred) }

  @Test
  fun `a succeeded analysis is downloaded, cached and marks the night done`() {
    val client =
        FakeClient(
            listing = listOf(analysis("a1", "succeeded")),
            full = mapOf("a1" to withResult(analysis("a1", "succeeded"))),
        )

    val snapshot = refresh(client)

    assertEquals("a1", snapshot.shown?.id)
    assertEquals(81.0, snapshot.shown?.result?.sleep_quality?.score ?: 0.0, 0.001)
    val record = store.record("r1")!!
    assertEquals(UploadPhase.DONE, record.phase)
    assertEquals("a1", record.analysisId)
    assertEquals(81.0, record.sleepScore ?: 0.0, 0.001)
    assertTrue(record.resultDownloaded)
    assertTrue(results.has("r1", "a1"))
  }

  @Test
  fun `a result that is on the phone is not downloaded again`() {
    val client =
        FakeClient(
            listing = listOf(analysis("a1", "succeeded")),
            full = mapOf("a1" to withResult(analysis("a1", "succeeded"))),
        )
    refresh(client)
    client.fetched.clear()

    val again = refresh(client)

    assertTrue(client.fetched.isEmpty())
    assertEquals(81.0, again.shown?.result?.sleep_quality?.score ?: 0.0, 0.001)
    assertEquals(UploadPhase.DONE, store.record("r1")?.phase)
  }

  @Test
  fun `a failed analysis marks the night failed with the worker's message`() {
    val client = FakeClient(listing = listOf(analysis("a1", "failed", error = "no valid heart rate")))

    refresh(client)

    val record = store.record("r1")!!
    assertEquals(UploadPhase.FAILED, record.phase)
    assertEquals(UploadFailure.ANALYSIS, record.failure)
    assertEquals("no valid heart rate", record.errorDetail)
    // A failure is read from the list: there is no result to fetch.
    assertTrue(client.fetched.isEmpty())
  }

  @Test
  fun `queued and running analyses leave the night processing under their id`() {
    for (status in listOf("queued", "running")) {
      store = UploadStore(MemoryRecords())
      val client = FakeClient(listing = listOf(analysis("a1", status)))

      refresh(client)

      val record = store.record("r1")!!
      assertEquals(status, UploadPhase.PROCESSING, record.phase)
      assertEquals("a1", record.analysisId)
      assertTrue(client.fetched.isEmpty())
    }
  }

  @Test
  fun `a sealed recording without an analysis is processing without an id`() {
    store.markProcessing("r1", "gone")

    refresh(FakeClient())

    val record = store.record("r1")!!
    assertEquals(UploadPhase.PROCESSING, record.phase)
    assertNull(record.analysisId)
  }

  @Test
  fun `a recording that is still uploading does not change the night's upload state`() {
    store.markUploading("r1", 0.4f)
    val client = FakeClient(recording = recording(complete = false))

    refresh(client)

    val record = store.record("r1")!!
    assertEquals(UploadPhase.UPLOADING, record.phase)
    assertEquals(0.4f, record.progress, 0.001f)
  }

  @Test
  fun `a remembered analysis the server replaced is not chosen`() {
    // A retry deletes the failed analysis and queues a new one under another id.
    store.markFailed("r1", UploadFailure.ANALYSIS, "boom")
    store.markProcessing("r1", "old")
    val client = FakeClient(listing = listOf(analysis("fresh", "queued", created = "2026-10-24T06:00:00Z")))

    val snapshot = refresh(client, preferred = listOf("old"))

    assertEquals("fresh", snapshot.shown?.id)
    val record = store.record("r1")!!
    assertEquals(UploadPhase.PROCESSING, record.phase)
    assertEquals("fresh", record.analysisId)
  }

  @Test
  fun `a deleted analysis leaves the night waiting for a new one`() {
    store.markProcessing("r1", "gone")

    val snapshot = refresh(FakeClient(listing = emptyList()), preferred = listOf("gone"))

    assertNull(snapshot.shown)
    assertNull(store.record("r1")?.analysisId)
  }

  @Test
  fun `an analysis deleted between the list and the download is skipped`() {
    val client =
        FakeClient(
            listing =
                listOf(
                    analysis("a1", "succeeded", updated = "2026-10-24T09:00:00Z"),
                    analysis("a2", "failed", created = "2026-10-24T07:00:00Z", error = "x"),
                ),
            full = emptyMap(), // a1 answers 404
        )

    val snapshot = refresh(client)

    assertEquals("a2", snapshot.shown?.id)
    assertEquals(listOf("a2"), snapshot.analyses.map { it.id })
    assertEquals(UploadPhase.FAILED, store.record("r1")?.phase)
  }

  @Test
  fun `a recording that is gone is reported to the caller`() {
    val client = FakeClient(recordingError = TaigaHttpException(404, "recording not found", "not_found"))

    val failure = assertThrows(TaigaHttpException::class.java) { refresh(client) }

    assertEquals(404, failure.status)
    assertNull(store.record("r1"))
  }

  @Test
  fun `a server error is not hidden`() {
    val client =
        FakeClient(
            listing = listOf(analysis("a1", "succeeded")),
            analysisError = TaigaHttpException(500, "boom"),
        )

    val failure = assertThrows(TaigaHttpException::class.java) { refresh(client) }

    assertEquals(500, failure.status)
  }

  @Test
  fun `the preferred analysis wins over a newer finished one`() {
    val client =
        FakeClient(
            listing =
                listOf(
                    analysis("manual", "running", created = "2026-10-24T05:00:00Z"),
                    analysis("auto", "succeeded", updated = "2026-10-24T09:00:00Z"),
                ),
            full = mapOf("auto" to withResult(analysis("auto", "succeeded"))),
        )

    assertEquals("manual", refresh(client, preferred = listOf(null, "manual")).shown?.id)
    assertEquals("auto", refresh(client, preferred = listOf(null)).shown?.id)
  }

  @Test
  fun `without a preference the newest finished run wins, else the newest run`() {
    assertEquals(
        "newer",
        chooseAnalysis(
                listOf(
                    analysis("newer", "succeeded", updated = "2026-10-24T09:00:00Z"),
                    analysis("older", "succeeded", updated = "2026-10-24T07:00:00Z"),
                    analysis("failed", "failed", created = "2026-10-24T10:00:00Z"),
                ),
                emptyList(),
            )
            ?.id,
    )
    assertEquals(
        "new",
        chooseAnalysis(
                listOf(
                    analysis("old", "failed", created = "2026-10-24T05:00:00Z"),
                    analysis("new", "running", created = "2026-10-24T06:00:00Z"),
                ),
                emptyList(),
            )
            ?.id,
    )
    assertNull(chooseAnalysis(emptyList(), listOf("gone")))
  }

  private class FakeClient(
      private val recording: RecordingDto = recording(),
      private val listing: List<AnalysisDto> = emptyList(),
      private val full: Map<String, AnalysisDto> = emptyMap(),
      private val recordingError: TaigaHttpException? = null,
      private val analysisError: TaigaHttpException? = null,
  ) : StubTaigaClient() {
    val fetched = mutableListOf<String>()

    override suspend fun getRecording(id: String): RecordingDto {
      recordingError?.let { throw it }
      return recording
    }

    override suspend fun listAnalyses(recording: String?, limit: Int, offset: Int) =
        AnalysisListDto(items = listing, total = listing.size.toLong())

    override suspend fun getAnalysis(analysisId: String): AnalysisDto {
      fetched += analysisId
      analysisError?.let { throw it }
      return full[analysisId] ?: throw TaigaHttpException(404, "analysis not found", "not_found")
    }
  }
}

/** Which nights a reconcile reads again: everything not settled, and nothing that is. */
class PendingRecordingsTest {
  private fun summary(
      analyses: List<AnalysisDto>,
      id: String = "r1",
      complete: Boolean = true,
  ) = RecordingSummaryDto(recording = recording(id, complete), analyses = analyses)

  private fun record(
      key: String = "remote-r1",
      phase: UploadPhase,
      analysisId: String? = null,
      downloaded: Boolean = false,
  ) =
      UploadRecord(
          id = key,
          onServer = true,
          phase = phase,
          analysisId = analysisId,
          resultDownloaded = downloaded,
          model = if (analysisId != null) "baseline" else null,
          modelVersion = if (analysisId != null) "1.0.0" else null,
      )

  private fun pending(
      summaries: List<RecordingSummaryDto>,
      vararg records: UploadRecord,
      local: List<SessionInfo> = emptyList(),
  ): List<PendingRecording> {
    val byKey = records.associateBy { it.id }
    val nights =
        buildNights(
            localSessions = local,
            records = byKey.mapValues { it.value.toNightRecord() },
            remote = RecordingSummaryListDto(summaries),
        )
    return pendingRecordings(nights, byKey)
  }

  @Test
  fun `a night whose result is on the phone is settled`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "succeeded")))),
            record(phase = UploadPhase.DONE, analysisId = "a1", downloaded = true),
        )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a failed night is settled`() {
    assertTrue(pending(listOf(summary(listOf(analysis("a1", "failed", error = "x"))))).isEmpty())
  }

  @Test
  fun `a finished analysis whose result is not on the phone is read for it`() {
    val result = pending(listOf(summary(listOf(analysis("a1", "succeeded")))))

    assertEquals(listOf(PendingRecording("remote-r1", "r1", "a1")), result)
  }

  @Test
  fun `queued and running analyses are pending`() {
    for (status in listOf("queued", "running")) {
      val result = pending(listOf(summary(listOf(analysis("a1", status)))))

      assertEquals(status, listOf("remote-r1"), result.map { it.key })
    }
  }

  @Test
  fun `a night stored as processing that the server finished is settled by a read`() {
    // It finished while the app was closed: the stored state still says processing.
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "succeeded")))),
            record(phase = UploadPhase.PROCESSING, analysisId = "a1"),
        )

    assertEquals(listOf("remote-r1"), result.map { it.key })
  }

  @Test
  fun `a night stored as processing that the server failed is settled by a read`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "failed", error = "x")))),
            record(phase = UploadPhase.PROCESSING, analysisId = "a1"),
        )

    assertEquals(listOf("remote-r1"), result.map { it.key })
  }

  @Test
  fun `a stored analysis id the server does not know is pending until a read replaces it`() {
    // The analysis was deleted, or replaced by a retry under another id.
    val result =
        pending(
            listOf(summary(listOf(analysis("fresh", "queued")))),
            record(phase = UploadPhase.PROCESSING, analysisId = "gone"),
        )
    assertEquals(listOf("remote-r1"), result.map { it.key })

    val none =
        pending(
            listOf(summary(emptyList())),
            record(phase = UploadPhase.PROCESSING, analysisId = "gone"),
        )
    assertEquals(listOf("remote-r1"), none.map { it.key })
  }

  @Test
  fun `a night uploaded with automatic analysis off is not read again and again`() {
    val result =
        pending(
            listOf(summary(emptyList())),
            record(phase = UploadPhase.PROCESSING, analysisId = null),
        )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a night with analyses on the server that the app never heard of is read`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "failed", error = "x")))),
            record(phase = UploadPhase.PROCESSING, analysisId = null),
        )

    assertEquals(listOf("remote-r1"), result.map { it.key })
  }

  @Test
  fun `a processing night older than the list reaches is read by its own id`() {
    // On the phone, uploaded, and past the 100 recordings the History list reaches.
    val id = "20261001_223000-k3f9"
    val night =
        SessionInfo(
            id = id,
            dir = File("/tmp/$id"),
            state = SessionState.STOPPED,
            startUtcMs = 1_790_000_000_000,
            endUtcMs = 1_790_003_600_000,
            sizeBytes = 10,
            sensorName = "Polar H10",
        )

    val result =
        pending(
            emptyList(),
            record(key = id, phase = UploadPhase.PROCESSING, analysisId = "a9"),
            local = listOf(night),
        )

    assertEquals(listOf(PendingRecording(id, id, "a9")), result)
  }

  @Test
  fun `a recording the server has not sealed is still uploading and is left alone`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "running")), complete = false)),
            record(phase = UploadPhase.PROCESSING, analysisId = "a1"),
        )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a night that is only on the phone is not read`() {
    val result =
        pending(
            emptyList(),
            UploadRecord(id = "20261023_223000-k3f9", phase = UploadPhase.QUEUED),
        )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `only the pending nights of several are returned`() {
    val result =
        pending(
            listOf(
                summary(listOf(analysis("a1", "succeeded")), id = "r1"),
                summary(listOf(analysis("a2", "running")), id = "r2"),
                summary(listOf(analysis("a3", "failed", error = "x")), id = "r3"),
            ),
            record(key = "remote-r1", phase = UploadPhase.DONE, analysisId = "a1", downloaded = true),
        )

    assertEquals(listOf("remote-r2"), result.map { it.key })
    assertFalse(result.any { it.key == "remote-r1" })
  }
}
