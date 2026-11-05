package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.AnalysisListDto
import com.rbmr.taigasleep.remote.AnalysisResultDto
import com.rbmr.taigasleep.remote.StubTaigaClient
import com.rbmr.taigasleep.remote.TaigaHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which analysis a poll reports on, including after the one the app remembered has gone. */
class CurrentAnalysisTest {
  private fun run(
      id: String,
      status: String,
      created: String = "2026-10-24T05:00:00Z",
      updated: String = created,
  ) =
      AnalysisDto(
          id = id,
          recording = "r1",
          algorithm = "baseline",
          version = "1.0.0",
          status = status,
          created = created,
          updated = updated,
      )

  private val full = AnalysisResultDto(contract_version = 3)

  @Test
  fun aKnownAnalysisIsFetchedByItsId() = runBlocking {
    val client = FakeClient(known = mapOf("a1" to run("a1", "running")))

    val analysis = client.currentAnalysis("r1", "a1")

    assertEquals("running", analysis?.status)
    assertEquals(listOf("a1"), client.fetched)
    assertEquals(0, client.listed)
  }

  @Test
  fun aSucceededKnownAnalysisCarriesItsResult() = runBlocking {
    val client = FakeClient(known = mapOf("a1" to run("a1", "succeeded").copy(result = full)))

    val analysis = client.currentAnalysis("r1", "a1")

    assertTrue(analysis?.isSucceeded == true)
    assertEquals(3, analysis?.result?.contract_version)
  }

  @Test
  fun anAnalysisThatIsGoneIsReplacedByWhatTheRecordingHasNow() = runBlocking {
    // A retry replaces a failed analysis under a new id, so the remembered id answers 404.
    val client =
        FakeClient(
            known = mapOf("fresh" to run("fresh", "queued")),
            listing = listOf(run("fresh", "queued", created = "2026-10-24T06:00:00Z")),
        )

    val analysis = client.currentAnalysis("r1", "gone")

    assertEquals("fresh", analysis?.id)
    assertEquals(listOf("gone"), client.fetched)
    assertEquals(1, client.listed)
  }

  @Test
  fun withoutAnIdTheNewestFinishedRunWinsAndIsFetchedWhole() = runBlocking {
    val client =
        FakeClient(
            known =
                mapOf(
                    "older" to run("older", "succeeded").copy(result = full),
                    "newer" to run("newer", "succeeded").copy(result = full),
                ),
            listing =
                listOf(
                    run("newer", "succeeded", updated = "2026-10-24T09:00:00Z"),
                    run("older", "succeeded", updated = "2026-10-24T07:00:00Z"),
                    run("failed", "failed", created = "2026-10-24T10:00:00Z"),
                ),
        )

    val analysis = client.currentAnalysis("r1", null)

    assertEquals("newer", analysis?.id)
    // The list leaves the result out, so the run is read again for it.
    assertEquals(listOf("newer"), client.fetched)
    assertEquals(3, analysis?.result?.contract_version)
  }

  @Test
  fun withoutAFinishedRunTheNewestRunIsReportedAsItIs() = runBlocking {
    val client =
        FakeClient(
            listing =
                listOf(
                    run("old", "failed", created = "2026-10-24T05:00:00Z"),
                    run("new", "running", created = "2026-10-24T06:00:00Z"),
                )
        )

    val analysis = client.currentAnalysis("r1", null)

    assertEquals("new", analysis?.id)
    assertTrue(client.fetched.isEmpty())
  }

  @Test
  fun aRecordingWithoutAnalysesHasNone() = runBlocking {
    assertNull(FakeClient().currentAnalysis("r1", null))
    assertNull(FakeClient().currentAnalysis("r1", "gone"))
  }

  @Test
  fun anErrorOtherThanNotFoundIsNotHidden() {
    val client = FakeClient(failWith = TaigaHttpException(500, "boom"))

    val error = runCatching { runBlocking { client.currentAnalysis("r1", "a1") } }.exceptionOrNull()

    assertEquals(500, (error as TaigaHttpException).status)
  }

  private class FakeClient(
      private val known: Map<String, AnalysisDto> = emptyMap(),
      private val listing: List<AnalysisDto> = emptyList(),
      private val failWith: TaigaHttpException? = null,
  ) : StubTaigaClient() {
    val fetched = mutableListOf<String>()
    var listed = 0

    override suspend fun getAnalysis(analysisId: String): AnalysisDto {
      fetched += analysisId
      failWith?.let { throw it }
      return known[analysisId] ?: throw TaigaHttpException(404, "analysis not found", "not_found")
    }

    override suspend fun listAnalyses(recording: String?, limit: Int, offset: Int): AnalysisListDto {
      listed++
      return AnalysisListDto(items = listing, total = listing.size.toLong())
    }
  }
}
