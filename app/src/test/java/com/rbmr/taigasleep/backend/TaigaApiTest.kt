package com.rbmr.taigasleep.backend

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaigaApiTest {
  private lateinit var server: MockWebServer
  private lateinit var api: OkHttpTaigaApi

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    api =
        OkHttpTaigaApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "test-token",
            client = HttpClients(OkHttpClient()).api,
        )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun putRecordingSendsTheMetaVerbatimAtThePhonesId() = runBlocking {
    server.enqueue(
        json(
            """
            {"id":"20261023_223000-k3f9","state":"stopped","device":"H10",
             "started_at":"2026-10-23T20:30:00Z","ended_at":"2026-10-24T05:41:12Z",
             "duration_s":33072,"complete":false,"size_bytes":0,
             "created":"2026-10-24T05:50:01Z","updated":"2026-10-24T05:50:01Z"}
            """,
            code = 201,
        )
    )
    val meta = """{"format":"taiga-recording","version":1,"session_id":"20261023_223000-k3f9"}"""

    val recording = api.putRecording("20261023_223000-k3f9", meta)

    assertEquals("20261023_223000-k3f9", recording.id)
    assertFalse(recording.complete)
    val request = server.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("/v1/recordings/20261023_223000-k3f9", request.path)
    assertEquals("Bearer test-token", request.getHeader("Authorization"))
    assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';'))
    // The recording's meta.json is the whole body, not wrapped in another object.
    assertEquals(meta, request.body.readUtf8())
  }

  @Test
  fun getRecordingReadsTheSeal() = runBlocking {
    server.enqueue(json("""{"id":"r1","state":"stopped","complete":true}"""))

    val recording = api.getRecording("r1")

    assertTrue(recording.complete)
    val request = server.takeRequest()
    assertEquals("GET", request.method)
    assertEquals("/v1/recordings/r1", request.path)
  }

  @Test
  fun headFileReadsTheStoredSizeAndTheEncoding() = runBlocking {
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .addHeader("Upload-Offset", "4096")
            .addHeader("Content-Encoding", "gzip")
    )
    server.enqueue(MockResponse().setResponseCode(200).addHeader("Upload-Offset", "10"))

    val gzip = api.headFile("r1", "hr.log")
    val plain = api.headFile("r1", "ecg.log")

    assertEquals(RemoteFile(size = 4096, encoding = "gzip"), gzip)
    assertEquals(RemoteFile(size = 10, encoding = ""), plain)
    val request = server.takeRequest()
    assertEquals("HEAD", request.method)
    assertEquals("/v1/recordings/r1/files/hr.log", request.path)
    assertEquals("Bearer test-token", request.getHeader("Authorization"))
  }

  @Test
  fun aFileWithoutAChunkYetStartsAtZero() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404))

    assertNull(api.headFile("r1", "hr.log"))
  }

  @Test
  fun appendSendsRawBytesAtTheServerOffset() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204).addHeader("Upload-Offset", "7"))
    val bytes = byteArrayOf(1, 2, 3)

    val newSize = api.appendFile("r1", "hr.log", 4, bytes)

    assertEquals(7L, newSize)
    val request = server.takeRequest()
    assertEquals("PATCH", request.method)
    assertEquals("/v1/recordings/r1/files/hr.log", request.path)
    assertEquals("Bearer test-token", request.getHeader("Authorization"))
    assertEquals("4", request.getHeader("Upload-Offset"))
    assertNull(request.getHeader("Content-Encoding"))
    assertEquals("application/octet-stream", request.getHeader("Content-Type")?.substringBefore(';'))
    assertArrayEquals(bytes, request.body.readByteArray())
  }

  @Test
  fun aCompressedAppendSaysSoInTheContentEncoding() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204).addHeader("Upload-Offset", "20"))

    val newSize = api.appendFile("r1", "hr.log", 0, byteArrayOf(1, 2, 3), "gzip")

    assertEquals(20L, newSize)
    val request = server.takeRequest()
    assertEquals("gzip", request.getHeader("Content-Encoding"))
    assertEquals("0", request.getHeader("Upload-Offset"))
    // The offset travels in a header, never in the query.
    assertEquals("/v1/recordings/r1/files/hr.log", request.path)
  }

  @Test
  fun anAppendAnswerWithoutTheNewSizeIsMalformed() {
    server.enqueue(MockResponse().setResponseCode(204))

    val error = runCatching { runBlocking { api.appendFile("r1", "hr.log", 0, byteArrayOf(1)) } }
        .exceptionOrNull()

    assertTrue(error is TaigaMalformedException)
  }

  @Test
  fun anOffsetMismatchCarriesTheOffsetTheServerHas() {
    server.enqueue(
        MockResponse()
            .setResponseCode(409)
            .addHeader("Upload-Offset", "7")
            .setBody("""{"code":"offset_mismatch","message":"the file is 7 bytes long, not 4"}""")
    )

    val error =
        runCatching {
              runBlocking { api.appendFile("r1", "hr.log", 4, byteArrayOf(1)) }
            }
            .exceptionOrNull()

    assertTrue(error is TaigaConflictException)
    error as TaigaConflictException
    assertEquals(ApiCode.OFFSET_MISMATCH, error.code)
    assertEquals(7L, error.uploadOffset)
    assertEquals("the file is 7 bytes long, not 4", error.message)
  }

  @Test
  fun anotherConflictKeepsItsCodeAndHasNoOffset() {
    server.enqueue(
        MockResponse()
            .setResponseCode(409)
            .setBody(
                """{"code":"recording_complete","message":"the recording is complete and can no longer change"}"""
            )
    )

    val error = runCatching { runBlocking { api.putRecording("r1", "{}") } }.exceptionOrNull()

    assertTrue(error is TaigaConflictException)
    error as TaigaConflictException
    assertEquals(ApiCode.RECORDING_COMPLETE, error.code)
    assertNull(error.uploadOffset)
  }

  @Test
  fun anExpiredTokenIsReportedAsUnauthorized() {
    server.enqueue(
        MockResponse()
            .setResponseCode(401)
            .setBody("""{"code":"unauthorized","message":"a valid token is required"}""")
    )

    val error = runCatching { runBlocking { api.getAnalysis("a1") } }.exceptionOrNull()

    assertTrue(error is TaigaUnauthorizedException)
    assertEquals("unauthorized", (error as TaigaUnauthorizedException).code)
  }

  @Test
  fun aForbiddenTokenIsUnauthorizedToo() {
    server.enqueue(
        MockResponse().setResponseCode(403).setBody("""{"code":"forbidden","message":"no"}""")
    )

    val error = runCatching { runBlocking { api.getAlgorithms() } }.exceptionOrNull()

    assertTrue(error is TaigaUnauthorizedException)
  }

  @Test
  fun anErrorBodyIsReadAsCodeAndMessage() {
    server.enqueue(
        MockResponse()
            .setResponseCode(413)
            .setBody("""{"code":"chunk_too_large","message":"a chunk is at most 1048576 bytes"}""")
    )

    val error = runCatching { runBlocking { api.appendFile("r1", "hr.log", 0, byteArrayOf(1)) } }
        .exceptionOrNull()

    assertTrue(error is TaigaHttpException)
    error as TaigaHttpException
    assertEquals(413, error.status)
    assertEquals(ApiCode.CHUNK_TOO_LARGE, error.code)
    assertEquals("a chunk is at most 1048576 bytes", error.message)
  }

  @Test
  fun anErrorWithoutAJsonBodyFallsBackToTheStatus() {
    server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))

    val error = runCatching { runBlocking { api.getRecording("r1") } }.exceptionOrNull()

    assertTrue(error is TaigaHttpException)
    error as TaigaHttpException
    assertEquals(502, error.status)
    assertEquals("", error.code)
    assertEquals("HTTP 502", error.message)
  }

  @Test
  fun aBodyThatIsNotJsonIsMalformed() {
    server.enqueue(json("this is not json"))

    val error = runCatching { runBlocking { api.getRecording("r1") } }.exceptionOrNull()

    assertTrue(error is TaigaMalformedException)
  }

  @Test
  fun aServerThatIsGoneIsANetworkError() {
    server.shutdown()

    val error = runCatching { runBlocking { api.getRecording("r1") } }.exceptionOrNull()

    assertTrue(error is TaigaNetworkException)
  }

  @Test
  fun completeSealsTheRecording() = runBlocking {
    server.enqueue(json("""{"id":"r1","state":"stopped","complete":true}"""))

    val recording = api.completeRecording("r1")

    assertTrue(recording.complete)
    val request = server.takeRequest()
    assertEquals("PATCH", request.method)
    assertEquals("/v1/recordings/r1", request.path)
    assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';'))
    assertEquals("""{"complete":true}""", request.body.readUtf8())
  }

  @Test
  fun anAnalysisIsRequestedByAlgorithmAndVersion() = runBlocking {
    server.enqueue(
        json(
            """
            {"id":"a1","recording":"r1","algorithm":"baseline","version":"1.0.0",
             "params":{},"status":"queued","error":"","attempts":0,"summary":null}
            """,
            code = 201,
        )
    )

    val analysis = api.requestAnalysis("r1", "baseline", "1.0.0")

    assertEquals("a1", analysis.id)
    assertEquals("queued", analysis.status)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/v1/recordings/r1/analyses", request.path)
    assertEquals("""{"algorithm":"baseline","version":"1.0.0"}""", request.body.readUtf8())
  }

  @Test
  fun aRetryRepeatsTheParamsOfTheFailedRun() = runBlocking {
    server.enqueue(json("""{"id":"a2","status":"queued"}""", code = 201))
    val params = JsonObject(mapOf("window" to JsonPrimitive(5)))

    api.requestAnalysis("r1", "baseline", "1.0.0", params)

    assertEquals(
        """{"algorithm":"baseline","version":"1.0.0","params":{"window":5}}""",
        server.takeRequest().body.readUtf8(),
    )
  }

  @Test
  fun aBlankVersionIsLeftToTheServer() = runBlocking {
    server.enqueue(json("""{"id":"a1","status":"queued"}""", code = 201))

    api.requestAnalysis("r1", "baseline", "")

    assertEquals("""{"algorithm":"baseline"}""", server.takeRequest().body.readUtf8())
  }

  @Test
  fun anAnalysisParsesItsResult() = runBlocking {
    server.enqueue(
        json(
            """
            {"id":"a1","recording":"r1","algorithm":"baseline","version":"1.0.0",
             "status":"succeeded",
             "result":{"contract_version":3,
               "stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":3,
                         "segments":[{"stage":"wake","count":1},{"stage":"light","count":2}]},
               "items":[{"kind":"number","id":"sleep_efficiency","label":"Sleep efficiency",
                          "group":"sleep","unit":"%","value":85.0,
                          "explanation":"Share of the recording scored as sleep.",
                          "sources":["stages"]}]}}
            """
        )
    )

    val analysis = api.getAnalysis("a1")

    val request = server.takeRequest()
    assertEquals("GET", request.method)
    assertEquals("/v1/analyses/a1", request.path)
    assertTrue(analysis.isSucceeded)
    assertEquals("light", analysis.result?.stages?.segments?.get(1)?.stage)
    assertEquals(85.0, analysis.result?.items?.single()?.value ?: 0.0, 0.001)
  }

  @Test
  fun recordingsAreListedNewestFirstWithAPageLimit() = runBlocking {
    server.enqueue(
        json(
            """
            {"items":[{"id":"r1","device":"H10","started_at":"2026-10-25T14:00:00Z",
                       "duration_s":3600,"complete":true}],
             "limit":100,"offset":0,"total":1}
            """
        )
    )

    val list = api.listRecordings(limit = 100)

    assertEquals("r1", list.items.single().id)
    assertEquals(1L, list.total)
    val request = server.takeRequest()
    assertEquals("/v1/recordings?limit=100&sort=-started_at", request.path)
  }

  @Test
  fun aPageLimitStaysWithinWhatTheServerAccepts() = runBlocking {
    server.enqueue(json("""{"items":[]}"""))

    api.listRecordings(limit = 1000)

    assertEquals("/v1/recordings?limit=200&sort=-started_at", server.takeRequest().path)
  }

  @Test
  fun analysesAreListedForTheAccountOrForOneRecording() = runBlocking {
    server.enqueue(json("""{"items":[{"id":"a1","recording":"r1","status":"running"}],"total":1}"""))
    server.enqueue(json("""{"items":[],"total":0}"""))

    val all = api.listAnalyses(offset = 200)
    api.listAnalyses(recording = "r1")

    assertEquals("running", all.items.single().status)
    assertEquals("/v1/analyses?limit=200&offset=200&sort=-created", server.takeRequest().path)
    assertEquals(
        "/v1/recordings/r1/analyses?limit=200&offset=0&sort=-created",
        server.takeRequest().path,
    )
  }

  @Test
  fun summariesGroupTheAnalysesOfEveryPageByRecording() = runBlocking {
    server.enqueue(json("""{"items":[{"id":"r1","complete":true},{"id":"r2","complete":true}]}"""))
    server.enqueue(
        json(
            """
            {"items":[{"id":"a1","recording":"r1"},{"id":"a2","recording":"r2"}],
             "limit":2,"offset":0,"total":4}
            """
        )
    )
    server.enqueue(
        json(
            """
            {"items":[{"id":"a3","recording":"r1"},{"id":"gone","recording":"older"}],
             "limit":2,"offset":2,"total":4}
            """
        )
    )

    val summaries = api.recordingSummaries()

    assertEquals(listOf("r1", "r2"), summaries.items.map { it.recording.id })
    assertEquals(listOf("a1", "a3"), summaries.items[0].analyses.map { it.id })
    assertEquals(listOf("a2"), summaries.items[1].analyses.map { it.id })
    server.takeRequest()
    assertEquals("/v1/analyses?limit=200&offset=0&sort=-created", server.takeRequest().path)
    // The second page starts after the analyses the first one returned.
    assertEquals("/v1/analyses?limit=200&offset=2&sort=-created", server.takeRequest().path)
  }

  @Test
  fun deleteRecordingUsesTheRecordingRoute() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))

    api.deleteRecording("r1")

    val request = server.takeRequest()
    assertEquals("DELETE", request.method)
    assertEquals("/v1/recordings/r1", request.path)
    assertEquals("Bearer test-token", request.getHeader("Authorization"))
  }

  @Test
  fun aDeleteOfAMissingRecordingIsA404() {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"x"}"""))

    val error = runCatching { runBlocking { api.deleteRecording("r1") } }.exceptionOrNull()

    assertEquals(404, (error as TaigaHttpException).status)
  }

  @Test
  fun filesAreListedWithTheirStoredSize() = runBlocking {
    server.enqueue(
        json(
            """
            {"items":[{"name":"hr.log","size":10,"encoding":"identity","uncompressed_size":10},
                      {"name":"ecg.log","size":5,"encoding":"gzip","uncompressed_size":50}]}
            """
        )
    )

    val files = api.listFiles("r1")

    assertEquals(listOf("hr.log", "ecg.log"), files.items.map { it.name })
    assertEquals(50L, files.items[1].uncompressed_size)
    assertEquals("/v1/recordings/r1/files", server.takeRequest().path)
  }

  @Test
  fun aDownloadAsksForTheOriginalBytesAndStreamsThem() = runBlocking {
    val bytes = ByteArray(5000) { (it % 251).toByte() }
    server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))
    val target = Files.createTempFile("taiga-download", ".part").toFile()

    api.downloadFile("r1", "hr.log", target)

    val request = server.takeRequest()
    assertEquals("GET", request.method)
    assertEquals("/v1/recordings/r1/files/hr.log", request.path)
    // OkHttp reads one gzip member at a time, so the server inflates the stored members itself.
    assertEquals("identity", request.getHeader("Accept-Encoding"))
    assertArrayEquals(bytes, target.readBytes())
    assertTrue(target.delete())
  }

  @Test
  fun aFailedDownloadLeavesNoPartialFile() {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"x"}"""))
    val target = Files.createTempFile("taiga-download", ".part").toFile()

    val error = runCatching { runBlocking { api.downloadFile("r1", "hr.log", target) } }
        .exceptionOrNull()

    assertEquals(404, (error as TaigaHttpException).status)
    assertFalse(File(target.path).exists())
  }

  @Test
  fun algorithmsExposeTheNeedsMetadata() = runBlocking {
    server.enqueue(
        json(
            """
            {"items":[
              {"id":"wav2sleep","version":"2.0.0","name":"wav2sleep",
               "needs":[{"name":"ecg","required":true,"min_rate_hz":100.0,"max_rate_hz":200.0}],
               "supported_stages":["wake","rem","light","deep","unknown"]},
              {"id":"baseline","version":"1.0.0","name":"Baseline",
               "needs":[{"name":"hr","required":true}]}
            ]}
            """
        )
    )

    val algorithms = api.getAlgorithms()

    assertEquals(2, algorithms.items.size)
    assertEquals("wav2sleep", algorithms.items[0].id)
    assertEquals("ecg", algorithms.items[0].needs[0].name)
    assertEquals(100.0, algorithms.items[0].needs[0].min_rate_hz ?: 0.0, 0.001)
    assertEquals("/v1/algorithms", server.takeRequest().path)
  }

  @Test
  fun accessTokensAreCreatedWithANameAndListedWithoutTheSecret() = runBlocking {
    server.enqueue(
        json(
            """{"id":"t1","name":"my assistant","hint":"wxyz","created":"2026-11-10T20:00:00Z",
                "token":"ts_secret"}""",
            code = 201,
        )
    )
    server.enqueue(json("""{"items":[{"id":"t1","name":"my assistant","hint":"wxyz"}]}"""))
    server.enqueue(MockResponse().setResponseCode(204))

    val created = api.createAccessToken("my assistant")
    val listed = api.listAccessTokens()
    api.deleteAccessToken("t1")

    assertEquals("ts_secret", created.token)
    assertNull(listed.items.single().token)
    val create = server.takeRequest()
    assertEquals("POST", create.method)
    assertEquals("/v1/tokens", create.path)
    assertEquals("""{"name":"my assistant"}""", create.body.readUtf8())
    assertEquals("Bearer test-token", create.getHeader("Authorization"))
    assertEquals("/v1/tokens", server.takeRequest().path)
    val delete = server.takeRequest()
    assertEquals("DELETE", delete.method)
    assertEquals("/v1/tokens/t1", delete.path)
  }

  private fun json(body: String, code: Int = 200): MockResponse =
      MockResponse()
          .setResponseCode(code)
          .addHeader("Content-Type", "application/json")
          .setBody(body.trimIndent())
}
