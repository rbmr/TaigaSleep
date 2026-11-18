package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AlgorithmListDto
import com.rbmr.taigasleep.backend.AlgorithmNeedDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.profile.Sex
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one way every path asks for an analysis: what it reads, what it sends and what it leaves. */
class AnalysisRequesterTest {
  @Test
  fun `a recording only on the server is asked with the streams the server reports`() = runBlocking {
    val client = FakeClient(listOf(wav2sleep(), sleepEcg()))
    val environment = FakeEnvironment(server = meta(ecg = true).toJson())

    AnalysisRequester(environment).startManual(session(client), "remote-r1", listOf("wav2sleep"))

    assertEquals(listOf(StagerChoice("wav2sleep", "1.1.0")), client.requests)
  }

  @Test
  fun `a failed algorithm list read fails the request instead of asking for baseline`() = runBlocking {
    val client = FakeClient(emptyList(), failure = TaigaNetworkException(IOException("offline")))
    val environment = FakeEnvironment(local = meta(ecg = false))

    val thrown =
        runCatching {
              AnalysisRequester(environment)
                  .startPreferred(session(client), "r1", AnalysisSettings())
            }
            .exceptionOrNull()

    assertTrue("the request reported ${thrown?.javaClass?.name}", thrown is TaigaNetworkException)
    assertTrue(client.requests.isEmpty())
    assertTrue(environment.marked.isEmpty())
  }

  @Test
  fun `a list that was read without a compatible method still asks for baseline`() = runBlocking {
    val client = FakeClient(emptyList())
    val environment = FakeEnvironment(local = meta(ecg = false))

    AnalysisRequester(environment).startPreferred(session(client), "r1", AnalysisSettings())

    assertEquals(listOf(StagerChoice("baseline", "1.2.0")), client.requests)
  }

  @Test
  fun `every path asks with the same parameters for the same recording`() = runBlocking {
    val client = FakeClient(listOf(sleepEcg()))
    val environment =
        FakeEnvironment(local = meta(ecg = true)).apply {
          personal =
              PersonalData(
                  dateOfBirth = LocalDate.of(1990, 6, 15),
                  sex = Sex.FEMALE,
              )
        }
    val requester = AnalysisRequester(environment)
    val session = session(client)
    val settings = AnalysisSettings(preferenceOrder = listOf("sleep-ecg"))
    val choice = StagerChoice("sleep-ecg", "1.0.0")

    requester.startManual(session, "r1", listOf("sleep-ecg"))
    requester.startPreferred(session, "r1", settings)
    // The automatic catch-up, whether it runs after an upload or for another trigger.
    requester.startChoice(session, "r1", choice)
    val params = client.sentParams.first()
    requester.retry(session, "r1", failedRun(params), automatic = false)

    assertEquals(4, client.sentParams.size)
    assertEquals(1, client.sentParams.distinct().size)
    assertEquals(36, client.sentParams.first()?.get("age")?.jsonPrimitive?.int)
    assertEquals(listOf(StagerChoice("sleep-ecg", "1.0.0")), client.requests.distinct())
  }

  @Test
  fun `an automatic retry is counted and a retry the user starts is not`() = runBlocking {
    val client = FakeClient(listOf(sleepEcg()))
    val environment = FakeEnvironment(local = meta(ecg = false))
    val requester = AnalysisRequester(environment)
    val failed = failedRun(params = null)

    requester.retry(session(client), "r1", failed, automatic = true)
    requester.retry(session(client), "r1", failed, automatic = false)

    assertEquals(listOf("r1"), environment.counted)
    assertEquals(listOf("r1", "r1"), environment.marked)
  }

  @Test
  fun `a request reads meta json once`() = runBlocking {
    val client = FakeClient(listOf(sleepEcg()))
    val environment = FakeEnvironment(local = meta(ecg = false))

    AnalysisRequester(environment).startPreferred(session(client), "r1", AnalysisSettings())

    assertEquals(1, environment.localReads)
  }

  private fun session(client: TaigaClient) = RemoteSession("acc", client)

  private fun failedRun(params: JsonObject?) =
      AnalysisDto(
          id = "a-old",
          recording = "r1",
          algorithm = "sleep-ecg",
          version = "1.0.0",
          status = "failed",
          params = params,
      )

  private fun meta(ecg: Boolean) =
      RecordingMeta(
          recordingId = "r1",
          state = RecordingPhase.STOPPED,
          startUtcMs = 1792798200000,
          endUtcMs = null,
          timezone = "Europe/Berlin",
          appVersion = "0.7.0",
          appVersionCode = 7000,
          phoneManufacturer = "Test",
          phoneModel = "Test",
          android = "16",
          sdk = 36,
          sensorKind = "polar-h10",
          sensorId = "sensor",
          sensorName = "Polar H10",
          sensorFirmware = null,
          sensorBatteryAtStart = null,
          config = RecordingConfig(hr = true, ecg = ecg, acc = false, ecgRateHz = 130),
      )

  private fun wav2sleep() =
      AlgorithmDto(
          id = "wav2sleep",
          version = "1.1.0",
          name = "wav2sleep ECG",
          needs = listOf(AlgorithmNeedDto("ecg", required = true, min_rate_hz = 100.0)),
      )

  private fun sleepEcg() =
      AlgorithmDto(
          id = "sleep-ecg",
          version = "1.0.0",
          name = "SleepECG",
          needs = listOf(AlgorithmNeedDto("hr", required = true)),
      )

  private class FakeEnvironment(
      private val local: RecordingMeta? = null,
      private val server: JsonObject? = null,
  ) : AnalysisRequestEnvironment {
    var personal = PersonalData()
    var localReads = 0
    val marked = mutableListOf<String>()
    val counted = mutableListOf<String>()

    override fun localMeta(key: String): RecordingMeta? {
      localReads++
      return local
    }

    override suspend fun serverMeta(api: TaigaClient, recordingId: String): JsonObject? = server

    override suspend fun serverStart(recordingId: String): String? = null

    override fun personalData(): PersonalData = personal

    override suspend fun markProcessing(key: String, account: String) {
      marked += key
    }

    override fun retriesUsed(recordingId: String, analysis: AnalysisDto): Int = 0

    override fun countRetry(recordingId: String, analysis: AnalysisDto) {
      counted += recordingId
    }
  }

  private class FakeClient(
      private val algorithms: List<AlgorithmDto>,
      private val failure: Exception? = null,
  ) : StubTaigaClient() {
    val requests = mutableListOf<StagerChoice>()
    val sentParams = mutableListOf<JsonObject?>()

    override suspend fun getAlgorithms(): AlgorithmListDto {
      failure?.let { throw it }
      return AlgorithmListDto(algorithms)
    }

    override suspend fun requestAnalysis(
        recordingId: String,
        algorithm: String,
        version: String,
        params: JsonObject?,
    ): AnalysisDto {
      requests += StagerChoice(algorithm, version)
      sentParams += params
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
