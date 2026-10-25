package com.rbmr.taigasleep.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun createSessionSendsMetaWithTheToken() = runBlocking {
    server.enqueue(
        json(
            """
            {"session":{"id":"s1","client_session_id":"local","upload_status":"pending"},
             "upload":{"completed":false,"limits":{"max_chunk_bytes":8388608},"files":{}}}
            """
        )
    )

    val response = api.createSession("""{"format":"taiga-recording","version":1}""")

    assertEquals("s1", response.session.id)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/taiga/sessions", request.path)
    assertEquals("test-token", request.getHeader("Authorization"))
    assertTrue(request.body.readUtf8().contains("\"meta\""))
  }

  @Test
  fun appendSendsRawBytesAtTheServerOffset() = runBlocking {
    server.enqueue(
        json(
            """
            {"file":"hr.log","offset":4,"written":3,"length":7,
             "validated_length":7,"state":"ok"}
            """
        )
    )
    val bytes = byteArrayOf(1, 2, 3)

    val response = api.appendFile("s1", "hr.log", 4, bytes)

    assertEquals(7L, response.length)
    val request = server.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("/api/taiga/sessions/s1/upload/file/hr.log?offset=4", request.path)
    assertEquals("test-token", request.getHeader("Authorization"))
    assertEquals("application/octet-stream", request.getHeader("Content-Type")?.substringBefore(';'))
    assertArrayEquals(bytes, request.body.readByteArray())
  }

  @Test
  fun aConflictIsReportedAsAResumableConflict() {
    server.enqueue(
        MockResponse()
            .setResponseCode(409)
            .setBody("""{"message":"offset mismatch: expected 7, got 4"}""")
    )

    val error =
        runCatching {
              runBlocking { api.appendFile("s1", "hr.log", 4, byteArrayOf(1)) }
            }
            .exceptionOrNull()

    assertTrue(error is TaigaConflictException)
    assertEquals("offset mismatch: expected 7, got 4", error?.message)
  }

  @Test
  fun anExpiredTokenIsReportedAsUnauthorized() {
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"token expired"}"""))

    val error = runCatching { runBlocking { api.getAnalysis("a1") } }.exceptionOrNull()

    assertTrue(error is TaigaUnauthorizedException)
  }

  @Test
  fun analysisParsesMetricsSupportAndSegments() = runBlocking {
    server.enqueue(
        json(
            """
            {"id":"a1","session":"s1","model":"baseline","model_version":"1.0.0",
             "status":"done","contract_version":1,
             "stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":3,
                       "segments":[{"stage":"wake","count":1},{"stage":"light","count":2}],
                       "confidence":[0.9,0.8,0.7],"artifacts":[false,false,false]},
             "metrics":{"total_sleep_s":60.0,"sleep_efficiency":0.85,"hrv_rmssd_ms":null},
             "metric_support":{"total_sleep_s":{"status":"supported"},
                               "sleep_efficiency":{"status":"supported"},
                               "hrv_rmssd_ms":{"status":"insufficient_data",
                                               "reason":"need_at_least_120_rr_intervals"}},
             "quality":{"score":83.4,"weights_version":1,
                        "components":{"usable_epoch_coverage":{"value":0.9,"weight":0.35,"available":true}},
                        "notes":[]},
             "warnings":[]}
            """
        )
    )

    val analysis = api.getAnalysis("a1")

    assertEquals("done", analysis.status)
    assertEquals(3, analysis.stages?.count)
    assertEquals("light", analysis.stages?.segments?.get(1)?.stage)
    assertEquals(0.85, analysis.metrics?.get("sleep_efficiency")?.jsonPrimitive?.double)
    assertEquals(
        "insufficient_data",
        analysis.metric_support?.get("hrv_rmssd_ms")?.status,
    )
    assertEquals(83.4, analysis.quality?.score ?: 0.0, 0.001)
    assertEquals(0.9, analysis.quality?.components?.get("usable_epoch_coverage")?.value ?: 0.0, 0.001)
  }

  @Test
  fun sessionListParsesTheSummaryShape() = runBlocking {
    server.enqueue(
        json(
            """
            {"page":1,"perPage":30,"hasMore":false,"items":[
              {"session":{"id":"s1","client_session_id":"local","device":"H10",
                          "started_at":"2026-10-25 14:00:00.000Z","duration_s":3600,
                          "upload_status":"uploaded"},
               "analyses":[{"id":"a1","session":"s1","model":"baseline","model_version":"1.0.0",
                            "status":"done","quality":{"score":80},
                            "stages":{"epoch_seconds":30,"count":1,
                                      "segments":[{"stage":"light","count":1}]}}],
               "analysis_summary":{"total":1,"done":1,"queued":0,"running":0,"failed":0}}
            ]}
            """
        )
    )

    val list = api.listSessions()

    assertEquals(1, list.items.size)
    assertEquals("local", list.items[0].session.client_session_id)
    assertEquals("done", list.items[0].analyses[0].status)
    assertEquals("light", list.items[0].analyses[0].stages?.segments?.get(0)?.stage)
    assertEquals(80.0, list.items[0].analyses[0].quality?.score ?: 0.0, 0.001)
  }

  @Test
  fun deleteSessionUsesTheCollectionRoute() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))

    api.deleteSession("s1")

    val request = server.takeRequest()
    assertEquals("DELETE", request.method)
    assertEquals("/api/collections/sessions/records/s1", request.path)
    assertEquals("test-token", request.getHeader("Authorization"))
  }

  @Test
  fun algorithmsExposeTheNeedsMetadata() = runBlocking {
    server.enqueue(
        json(
            """
            {"algorithms":[
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

    assertEquals(2, algorithms.algorithms.size)
    assertEquals("wav2sleep", algorithms.algorithms[0].id)
    assertEquals("ecg", algorithms.algorithms[0].needs[0].name)
    assertEquals(100.0, algorithms.algorithms[0].needs[0].min_rate_hz ?: 0.0, 0.001)
  }

  @Test
  fun analysisRequestUsesTheModelAndVersion() = runBlocking {
    server.enqueue(json("""{"analyses":[],"jobs":[]}"""))

    api.requestAnalysis("s1", "baseline", "1.0.0")

    val request = server.takeRequest()
    assertEquals("/api/taiga/sessions/s1/analysis", request.path)
    val body = request.body.readUtf8()
    assertTrue(body.contains("\"models\""))
    assertTrue(body.contains("\"baseline\""))
    assertTrue(body.contains("\"1.0.0\""))
  }

  private fun json(body: String): MockResponse =
      MockResponse()
          .setResponseCode(200)
          .addHeader("Content-Type", "application/json")
          .setBody(body.trimIndent())
}
