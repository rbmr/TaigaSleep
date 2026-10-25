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

  private class FakeClient(
      val lengths: MutableMap<String, Long> = mutableMapOf(),
      var completed: Boolean = false,
  ) : TaigaClient {
    val appends = mutableListOf<Triple<String, Long, ByteArray>>()
    val appendAttempts = mutableListOf<Long>()
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
    ): AppendResponse {
      appendAttempts += offset
      if (conflictOnce) {
        conflictOnce = false
        lengths[file] = 1L
        throw TaigaConflictException(409, "offset mismatch")
      }
      val current = lengths[file] ?: 0L
      check(offset == current) { "offset mismatch in fake: $offset vs $current" }
      lengths[file] = current + bytes.size
      appends += Triple(file, offset, bytes)
      return AppendResponse(
          file = file,
          offset = offset,
          written = bytes.size,
          length = lengths[file]!!,
          validated_length = lengths[file]!!,
          state = "ok",
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
            limits = UploadLimitsDto(max_chunk_bytes = 1024),
            files = lengths.mapValues { UploadFileDto(length = it.value) },
        )
  }
}
