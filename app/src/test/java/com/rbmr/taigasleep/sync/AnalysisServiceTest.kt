package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AlgorithmListDto
import com.rbmr.taigasleep.backend.AlgorithmNeedDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.recording.store.RecordingConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisServiceTest {
  private val ecg =
      RecordingConfig(
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
    assertEquals("queued", started?.status)
    assertEquals(listOf("s1"), client.recordings)
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

  private class FakeClient(private val algorithms: List<AlgorithmDto>) : StubTaigaClient() {
    val requests = mutableListOf<StagerChoice>()
    val recordings = mutableListOf<String>()
    var algorithmCalls = 0

    override suspend fun getAlgorithms(): AlgorithmListDto {
      algorithmCalls++
      return AlgorithmListDto(algorithms)
    }

    override suspend fun requestAnalysis(
        recordingId: String,
        algorithm: String,
        version: String,
        params: JsonObject?,
    ): AnalysisDto {
      requests += StagerChoice(algorithm, version)
      recordings += recordingId
      return AnalysisDto(
          id = "a-$algorithm",
          recording = recordingId,
          algorithm = algorithm,
          version = version,
          status = "queued",
      )
    }
  }
}
