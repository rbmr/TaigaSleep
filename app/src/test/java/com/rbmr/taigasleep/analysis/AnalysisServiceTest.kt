package com.rbmr.taigasleep.analysis

import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.remote.AlgorithmListDto
import com.rbmr.taigasleep.remote.AlgorithmNeedDto
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.AnalysisRequestResponse
import com.rbmr.taigasleep.remote.AppendResponse
import com.rbmr.taigasleep.remote.JobDto
import com.rbmr.taigasleep.remote.RetryJobResponse
import com.rbmr.taigasleep.remote.SessionDetailDto
import com.rbmr.taigasleep.remote.SessionDto
import com.rbmr.taigasleep.remote.SessionListDto
import com.rbmr.taigasleep.remote.SessionUploadResponse
import com.rbmr.taigasleep.remote.TaigaClient
import com.rbmr.taigasleep.storage.SessionConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisServiceTest {
  private val ecg =
      SessionConfig(
          hr = true,
          ecg = true,
          acc = true,
          ecgRateHz = 130,
          accRateHz = 50,
      )

  @Test
  fun automaticAnalysisRequestsThePreferredCompatibleStager() = runBlocking {
    val client = FakeClient(listOf(wav2sleep(), sleepEcg()))

    val started = AnalysisService.startAuto(client, "s1", ecg, AnalysisSettings())

    assertEquals(listOf(StagerChoice("wav2sleep", "1.1.0")), client.requests)
    assertEquals("a-wav2sleep", started?.analysisId)
    assertEquals("j-wav2sleep", started?.jobId)
    assertEquals(1, client.algorithmCalls)
  }

  @Test
  fun automaticAnalysisOffDoesNotTalkToTheServer() = runBlocking {
    val client = FakeClient(listOf(wav2sleep()))

    val started =
        AnalysisService.startAuto(client, "s1", ecg, AnalysisSettings(auto = false))

    assertNull(started)
    assertTrue(client.requests.isEmpty())
    assertEquals(0, client.algorithmCalls)
  }

  @Test
  fun manualRequestAsksForSeveralCompatibleStagers() = runBlocking {
    val client = FakeClient(listOf(wav2sleep(), sleepEcg()))

    val started =
        AnalysisService.startManual(
            client,
            "s1",
            ecg,
            ids = listOf("wav2sleep", "sleep-ecg"),
        )

    assertEquals(
        listOf(StagerChoice("wav2sleep", "1.1.0"), StagerChoice("sleep-ecg", "1.0.0")),
        client.requests,
    )
    assertEquals(listOf("a-wav2sleep", "a-sleep-ecg"), started.map { it.analysisId })
  }

  @Test
  fun manualRetryRequestsEvenWhenAutomaticAnalysisIsOff() = runBlocking {
    val client = FakeClient(listOf(wav2sleep(), sleepEcg()))

    val started =
        AnalysisService.startPreferred(
            client,
            "s1",
            ecg,
            AnalysisSettings(auto = false),
        )

    assertEquals(listOf(StagerChoice("wav2sleep", "1.1.0")), client.requests)
    assertEquals("a-wav2sleep", started.analysisId)
  }

  @Test
  fun anUnknownAlgorithmIsSkippedInAManualRequest() = runBlocking {
    val client = FakeClient(listOf(sleepEcg()))

    val started = AnalysisService.startManual(client, "s1", ecg, ids = listOf("missing", "sleep-ecg"))

    assertEquals(listOf(StagerChoice("sleep-ecg", "1.0.0")), client.requests)
    assertEquals(1, started.size)
  }

  private fun wav2sleep() =
      AlgorithmDto(
          id = "wav2sleep",
          version = "1.1.0",
          name = "wav2sleep ECG",
          needs = listOf(AlgorithmNeedDto("ecg", required = true, min_rate_hz = 50.0)),
      )

  private fun sleepEcg() =
      AlgorithmDto(
          id = "sleep-ecg",
          version = "1.0.0",
          name = "SleepECG",
          needs = listOf(AlgorithmNeedDto("hr", required = true)),
      )

  private class FakeClient(private val algorithms: List<AlgorithmDto>) : TaigaClient {
    val requests = mutableListOf<StagerChoice>()
    var algorithmCalls = 0

    override suspend fun getAlgorithms(): AlgorithmListDto {
      algorithmCalls++
      return AlgorithmListDto(algorithms)
    }

    override suspend fun requestAnalysis(
        sessionId: String,
        model: String,
        version: String,
    ): AnalysisRequestResponse {
      requests += StagerChoice(model, version)
      return AnalysisRequestResponse(
          analyses =
              listOf(
                  AnalysisDto(
                      id = "a-$model",
                      session = sessionId,
                      model = model,
                      model_version = version,
                      status = "queued",
                  )
              ),
          jobs = listOf(JobDto(id = "j-$model", session = sessionId, status = "queued")),
      )
    }

    override suspend fun createSession(metaJson: String): SessionUploadResponse =
        throw UnsupportedOperationException()

    override suspend fun getUpload(sessionId: String): SessionUploadResponse =
        throw UnsupportedOperationException()

    override suspend fun appendFile(
        sessionId: String,
        file: String,
        offset: Long,
        bytes: ByteArray,
        encoding: String?,
    ): AppendResponse = throw UnsupportedOperationException()

    override suspend fun completeUpload(sessionId: String): SessionUploadResponse =
        throw UnsupportedOperationException()

    override suspend fun deleteSession(sessionId: String) = Unit

    override suspend fun getAnalysis(analysisId: String): AnalysisDto =
        throw UnsupportedOperationException()

    override suspend fun getSession(sessionId: String): SessionDetailDto =
        throw UnsupportedOperationException()

    override suspend fun downloadSessionFile(sessionId: String, file: String, target: File) =
        throw UnsupportedOperationException()

    override suspend fun listSessions(perPage: Int): SessionListDto =
        throw UnsupportedOperationException()

    override suspend fun retryJob(jobId: String): RetryJobResponse =
        throw UnsupportedOperationException()
  }
}
