package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisListDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.backend.StageTimelineDto
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaHttpException
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
  val documents = mutableMapOf<String, String>()

  override fun ids(): Set<String> = documents.keys

  override fun read(id: String): String? = documents[id]

  override fun write(id: String, json: String) {
    documents[id] = json
  }

  override fun delete(id: String) {
    documents.remove(id)
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
                items =
                    listOf(
                        ResultItemDto(
                            kind = "score",
                            id = "sleep_quality",
                            label = "Sleep score",
                            group = "score",
                            unit = "%",
                            value = score,
                        )
                    ),
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
    assertEquals(81.0, snapshot.shown?.result?.items?.single()?.value ?: 0.0, 0.001)
    val record = store.record("r1")!!
    assertEquals(UploadPhase.DONE, record.phase)
    assertTrue(record.onServer)
    assertTrue(results.has("r1", "a1"))
    assertEquals(81.0, results.load("r1", "a1")?.result?.items?.single()?.value ?: 0.0, 0.001)
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
    assertEquals(81.0, again.shown?.result?.items?.single()?.value ?: 0.0, 0.001)
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
      assertTrue(client.fetched.isEmpty())
    }
  }

  @Test
  fun `a sealed recording without an analysis is processing without an id`() {
    store.markProcessing("r1")

    refresh(FakeClient())

    assertEquals(UploadPhase.PROCESSING, store.record("r1")?.phase)
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
    store.markProcessing("r1")
    val client = FakeClient(listing = listOf(analysis("fresh", "queued", created = "2026-10-24T06:00:00Z")))

    val snapshot = refresh(client, preferred = listOf("old"))

    assertEquals("fresh", snapshot.shown?.id)
    assertEquals(UploadPhase.PROCESSING, store.record("r1")?.phase)
  }

  @Test
  fun `a deleted analysis leaves the night waiting for a new one`() {
    store.markProcessing("r1")

    val snapshot = refresh(FakeClient(listing = emptyList()), preferred = listOf("gone"))

    assertNull(snapshot.shown)
    assertEquals(UploadPhase.PROCESSING, store.record("r1")?.phase)
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
