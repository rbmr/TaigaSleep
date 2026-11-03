package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AlgorithmListDto
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.AnalysisRequestResponse
import com.rbmr.taigasleep.remote.AppendResponse
import com.rbmr.taigasleep.remote.JobDto
import com.rbmr.taigasleep.remote.SessionDetailDto
import com.rbmr.taigasleep.remote.SessionDto
import com.rbmr.taigasleep.remote.SessionListDto
import com.rbmr.taigasleep.remote.SessionUploadResponse
import com.rbmr.taigasleep.remote.TaigaClient
import com.rbmr.taigasleep.remote.TaigaConflictException
import com.rbmr.taigasleep.remote.UploadFileDto
import com.rbmr.taigasleep.remote.UploadLimitsDto
import com.rbmr.taigasleep.remote.UploadManifestDto
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploadEngineTest {
  private lateinit var dir: File

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("taiga-upload").toFile()
    File(dir, "meta.json").writeText("""{"format":"taiga-recording","version":1}""")
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  @Test
  fun resumesFromTheLengthTheServerAlreadyHas() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10) { it.toByte() })
    val client = FakeClient(lengths = mutableMapOf("hr.log" to 4L))
    var progress = 0f

    val outcome =
        UploadEngine(client, dir, onProgress = { progress = it }).run()

    assertEquals("remote", outcome.remoteId)
    assertEquals("analysis", outcome.analysisId)
    assertEquals("job", outcome.jobId)
    assertEquals(listOf(4L to 6), client.appends.map { it.second to it.third.size })
    assertArrayEquals(ByteArray(6) { (it + 4).toByte() }, client.appends.single().third)
    assertEquals(1f, progress)
    assertTrue(client.completed)
    assertEquals("baseline", client.requestedModel)
    assertEquals("1.1.0", client.requestedVersion)
  }

  @Test
  fun aConflictIsRecoveredByReReadingTheManifest() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10) { it.toByte() })
    val client = FakeClient(lengths = mutableMapOf("hr.log" to 0L))
    client.conflictOnce = true

    UploadEngine(client, dir).run()

    assertEquals(listOf(0L, 1L), client.appendAttempts)
    assertEquals(1L, client.appends.single().second)
    assertEquals(9, client.appends.single().third.size)
    assertTrue(client.completed)
  }

  @Test
  fun anAlreadyCompletedUploadOnlyRequestsTheAnalysis() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient(lengths = mutableMapOf("hr.log" to 10L), completed = true)

    UploadEngine(client, dir).run()

    assertTrue(client.appends.isEmpty())
    assertTrue(client.analysisRequested)
  }

  @Test
  fun aRecordingWithoutStreamFilesFailsBeforeTouchingTheServer() = runBlocking {
    val client = FakeClient()
    try {
      UploadEngine(client, dir).run()
      throw AssertionError("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("stream files"))
    }
  }

  @Test
  fun compressedUploadSendsOneMemberPerChunkAndResumes() = runBlocking {
    val data = ByteArray(3 * 1024 * 1024 + 123) { ((it * 31) % 251).toByte() }
    File(dir, "hr.log").writeBytes(data)
    val cache = Files.createTempDirectory("taiga-cache").toFile()

    // The first attempt loses the network after the first chunk was accepted.
    val first =
        FakeClient(
            accepted = listOf("identity", "gzip"),
            failAfterAppends = 1,
        )
    try {
      UploadEngine(first, dir, compress = true, cacheDir = cache).run()
      throw AssertionError("expected TaigaNetworkException")
    } catch (_: com.rbmr.taigasleep.remote.TaigaNetworkException) {
      // expected
    }
    assertEquals(1, first.appends.size)
    assertTrue(first.encodings.all { it == "gzip" })
    val accepted = first.appends.single().third

    // The next attempt starts at the server's compressed offset.
    val second =
        FakeClient(
            lengths = mutableMapOf("hr.log" to accepted.size.toLong()),
            encoding = "gzip",
            accepted = listOf("identity", "gzip"),
        )
    UploadEngine(second, dir, compress = true, cacheDir = cache).run()

    assertEquals(1, second.appends.size)
    assertEquals(accepted.size.toLong(), second.appends.single().second)
    assertEquals("gzip", second.encodings.single())
    val combined = accepted + second.appends.single().third
    assertArrayEquals(data, gunzip(combined))
    assertTrue(!cache.exists())
  }

  @Test
  fun aCompressedCacheThatCannotExplainTheServerFailsClearly() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(5000))
    val cache = Files.createTempDirectory("taiga-cache").toFile()
    val client = FakeClient(lengths = mutableMapOf("hr.log" to 77L), encoding = "gzip")

    try {
      UploadEngine(client, dir, compress = true, cacheDir = cache).run()
      throw AssertionError("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("cache"))
    }
  }

  @Test
  fun gzipMembersAreDeterministicAndValid() {
    val data = ByteArray(10_000) { (it % 97).toByte() }
    val first = gzipMember(data)
    val second = gzipMember(data)

    assertArrayEquals(first, second)
    assertEquals(0x1f, first[0].toInt() and 0xff)
    assertEquals(0x8b, first[1].toInt() and 0xff)
    assertArrayEquals(data, gunzip(first))
  }

  private fun gunzip(bytes: ByteArray): ByteArray =
      GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

  private class FakeClient(
      val lengths: MutableMap<String, Long> = mutableMapOf(),
      var completed: Boolean = false,
      var encoding: String = "",
      val accepted: List<String> = emptyList(),
      var failAfterAppends: Int = -1,
  ) : TaigaClient {
    val appends = mutableListOf<Triple<String, Long, ByteArray>>()
    val appendAttempts = mutableListOf<Long>()
    val encodings = mutableListOf<String?>()
    var conflictOnce = false
    var analysisRequested = false
    var requestedModel = ""
    var requestedVersion = ""

    override suspend fun createSession(metaJson: String): SessionUploadResponse =
        SessionUploadResponse(session = session(), upload = manifest())

    override suspend fun getUpload(sessionId: String): SessionUploadResponse =
        SessionUploadResponse(session = session(sessionId), upload = manifest())

    override suspend fun appendFile(
        sessionId: String,
        file: String,
        offset: Long,
        bytes: ByteArray,
        encoding: String?,
    ): AppendResponse {
      appendAttempts += offset
      encodings += encoding
      if (conflictOnce) {
        conflictOnce = false
        lengths[file] = 1L
        throw TaigaConflictException(409, "offset mismatch")
      }
      val current = lengths[file] ?: 0L
      check(offset == current) { "offset mismatch in fake: $offset vs $current" }
      if (encoding == "gzip") this.encoding = "gzip"
      if (failAfterAppends == 0) {
        failAfterAppends = -1
        throw com.rbmr.taigasleep.remote.TaigaNetworkException(java.io.IOException("offline"))
      }
      if (failAfterAppends > 0) failAfterAppends--
      lengths[file] = current + bytes.size
      appends += Triple(file, offset, bytes)
      return AppendResponse(
          file = file,
          offset = offset,
          written = bytes.size,
          length = lengths[file]!!,
          validated_length = lengths[file]!!,
          state = "ok",
          encoding = encoding.orEmpty(),
      )
    }

    override suspend fun completeUpload(sessionId: String): SessionUploadResponse {
      completed = true
      return SessionUploadResponse(session = session(sessionId), upload = manifest())
    }

    override suspend fun requestAnalysis(
        sessionId: String,
        model: String,
        version: String,
    ): AnalysisRequestResponse {
      analysisRequested = true
      requestedModel = model
      requestedVersion = version
      return AnalysisRequestResponse(
          analyses = listOf(AnalysisDto(id = "analysis", session = sessionId, status = "queued")),
          jobs = listOf(JobDto(id = "job", session = sessionId, status = "queued")),
      )
    }

    override suspend fun getAlgorithms(): AlgorithmListDto = AlgorithmListDto()

    override suspend fun deleteSession(sessionId: String) = Unit

    override suspend fun getAnalysis(analysisId: String): AnalysisDto =
        throw UnsupportedOperationException()

    override suspend fun getSession(sessionId: String): SessionDetailDto =
        throw UnsupportedOperationException()

    override suspend fun listSessions(perPage: Int): SessionListDto =
        throw UnsupportedOperationException()

    override suspend fun retryJob(jobId: String): com.rbmr.taigasleep.remote.RetryJobResponse =
        throw UnsupportedOperationException()

    private fun session(id: String = "remote") =
        SessionDto(
            id = id,
            client_session_id = "local",
            upload_status = if (completed) "uploaded" else "pending",
        )

    private fun manifest() =
        UploadManifestDto(
            completed = completed,
            encoding = encoding,
            accepted_encodings = accepted,
            limits = UploadLimitsDto(
                max_chunk_bytes = 4L * 1024 * 1024,
                max_uncompressed_chunk_bytes = 4L * 1024 * 1024,
            ),
            files = lengths.mapValues { UploadFileDto(length = it.value) },
        )
  }
}
