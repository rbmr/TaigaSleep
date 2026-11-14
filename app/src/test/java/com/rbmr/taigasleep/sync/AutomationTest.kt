package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AlgorithmNeedDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOUR = 60 * 60 * 1000L

// 2026-11-14 12:00:00 UTC
private const val NOW = 1794657600000L

private val ECG = RecordingConfig(hr = true, ecg = true, acc = false)
private val HR_ONLY = RecordingConfig(hr = true, ecg = false, acc = false)

private val WAV2SLEEP =
    AlgorithmDto(
        id = "wav2sleep",
        version = "1.1.0",
        needs = listOf(AlgorithmNeedDto("ecg", required = true, min_rate_hz = 100.0)),
    )
private val SLEEP_ECG_ALGORITHM =
    AlgorithmDto(
        id = "sleep-ecg",
        version = "1.0.0",
        needs = listOf(AlgorithmNeedDto("hr", required = true)),
    )
private val BASELINE =
    AlgorithmDto(
        id = "baseline",
        version = "1.2.0",
        needs = listOf(AlgorithmNeedDto("hr", required = true)),
    )
private val INSTALLED = listOf(WAV2SLEEP, SLEEP_ECG_ALGORITHM, BASELINE)

private fun night(
    id: String = "n1",
    state: RecordingPhase = RecordingPhase.STOPPED,
    live: Boolean = false,
    record: UploadRecord? = null,
) = UploadNight(id, state, live, record)

private fun record(
    phase: UploadPhase,
    failure: UploadFailure? = null,
    updatedAtMs: Long = NOW,
) = UploadRecord(id = "n1", phase = phase, failure = failure, updatedAtMs = updatedAtMs)

private fun run(
    status: String,
    algorithm: String = "wav2sleep",
    error: String = "",
    hoursAgo: Long = 1,
    created: String = "2026-11-14 10:00:00.000Z",
): AnalysisDto {
  val at = java.time.Instant.ofEpochMilli(NOW - hoursAgo * HOUR).toString()
  return AnalysisDto(
      id = "a-$algorithm",
      algorithm = algorithm,
      version = "1.0.0",
      status = status,
      error = error,
      created = created,
      updated = at,
      finished_at = at,
  )
}

class AutomationUploadDecisionTest {
  private val on = AutoUploadSettings(enabled = true)

  private fun due(vararg nights: UploadNight, settings: AutoUploadSettings = on) =
      uploadsDue(nights.toList(), settings, NOW)

  @Test
  fun `a finished recording that was never queued is uploaded`() {
    assertEquals(listOf("n1"), due(night(record = null)))
    assertEquals(listOf("n1"), due(night(record = record(UploadPhase.LOCAL))))
  }

  @Test
  fun `a night that waits or stopped halfway is queued again, which keeps work that exists`() {
    assertEquals(listOf("n1"), due(night(record = record(UploadPhase.QUEUED))))
    assertEquals(listOf("n1"), due(night(record = record(UploadPhase.UPLOADING))))
  }

  @Test
  fun `a night the account has is not uploaded again`() {
    for (phase in listOf(UploadPhase.UPLOADED, UploadPhase.PROCESSING, UploadPhase.DONE)) {
      assertTrue(due(night(record = record(phase))).isEmpty())
    }
  }

  @Test
  fun `nothing is uploaded while automatic upload is off`() {
    assertTrue(due(night(), settings = AutoUploadSettings(enabled = false)).isEmpty())
  }

  @Test
  fun `a recording that is recording or still on the tracker is left alone`() {
    assertTrue(due(night(state = RecordingPhase.RECORDING, live = true)).isEmpty())
    assertTrue(due(night(state = RecordingPhase.FETCH_PENDING)).isEmpty())
  }

  @Test
  fun `a recording a crash left open is uploaded, which closes it`() {
    assertEquals(listOf("n1"), due(night(state = RecordingPhase.RECORDING, live = false)))
  }

  @Test
  fun `a failure that waiting can fix is retried`() {
    for (failure in
        listOf(
            UploadFailure.NETWORK,
            UploadFailure.LOGIN,
            UploadFailure.RECORDING,
            UploadFailure.VERSION,
        )) {
      assertEquals(listOf("n1"), due(night(record = record(UploadPhase.FAILED, failure))))
    }
  }

  @Test
  fun `a server failure is retried after its cooldown`() {
    val fresh = record(UploadPhase.FAILED, UploadFailure.SERVER, updatedAtMs = NOW - HOUR)
    val old = record(UploadPhase.FAILED, UploadFailure.SERVER, updatedAtMs = NOW - 7 * HOUR)

    assertTrue(due(night(record = fresh)).isEmpty())
    assertEquals(listOf("n1"), due(night(record = old)))
  }

  @Test
  fun `a failure of the phone itself is left to the user`() {
    assertTrue(due(night(record = record(UploadPhase.FAILED, UploadFailure.STORAGE))).isEmpty())
  }
}

class AnalysisDecisionTest {
  private val auto = AnalysisSettings(auto = true, preferenceOrder = listOf("wav2sleep", "sleep-ecg", "baseline"))

  private fun action(
      runs: List<AnalysisDto>,
      config: RecordingConfig = ECG,
      installed: List<AlgorithmDto> = INSTALLED,
      settings: AnalysisSettings = auto,
      retries: Int = 0,
  ) = analysisAction(runs, config, installed, settings, { retries }, NOW)

  @Test
  fun `an uploaded night with no run gets the first method of the preferred order`() {
    assertEquals(AnalysisAction.Start(StagerChoice("wav2sleep", "1.1.0")), action(emptyList()))
  }

  @Test
  fun `a method the recording cannot feed is skipped for the next compatible one`() {
    assertEquals(
        AnalysisAction.Start(StagerChoice("sleep-ecg", "1.0.0")),
        action(emptyList(), config = HR_ONLY),
    )
  }

  @Test
  fun `a method that is not installed is skipped`() {
    assertEquals(
        AnalysisAction.Start(StagerChoice("sleep-ecg", "1.0.0")),
        action(emptyList(), installed = listOf(SLEEP_ECG_ALGORITHM, BASELINE)),
    )
  }

  @Test
  fun `nothing is requested blind when the server reports no method or none fits`() {
    assertEquals(AnalysisAction.None, action(emptyList(), installed = emptyList()))
    assertEquals(
        AnalysisAction.None,
        action(emptyList(), config = HR_ONLY, installed = listOf(WAV2SLEEP)),
    )
  }

  @Test
  fun `a night that is analysing or analysed is left alone`() {
    for (status in listOf("queued", "running", "succeeded")) {
      assertEquals(AnalysisAction.None, action(listOf(run(status))))
    }
    // One run that is on its way is enough, even next to a failed one.
    assertEquals(
        AnalysisAction.None,
        action(listOf(run("failed", "wav2sleep", error = "job timed out after 600s"), run("queued", "baseline"))),
    )
  }

  @Test
  fun `nothing is requested while automatic analysis is off`() {
    assertEquals(AnalysisAction.None, action(emptyList(), settings = auto.copy(auto = false)))
  }

  @Test
  fun `a run that failed for a lasting reason is not asked for again`() {
    assertEquals(
        AnalysisAction.None,
        action(listOf(run("failed", error = "missing_stream: recording has no ecg stream"))),
    )
  }

  @Test
  fun `a run that failed for a passing reason is asked for again after its cooldown`() {
    val failed = run("failed", error = "job timed out after 600s", hoursAgo = 7)
    assertEquals(AnalysisAction.Retry(failed), action(listOf(failed)))
    val expired = failed.copy(error = "lease expired too many times")
    assertEquals(AnalysisAction.Retry(expired), action(listOf(expired)))
  }

  @Test
  fun `a passing failure waits for its cooldown and for its retries`() {
    val recent = run("failed", error = "job timed out after 600s", hoursAgo = 1)
    val old = run("failed", error = "job timed out after 600s", hoursAgo = 7)

    assertEquals(AnalysisAction.None, action(listOf(recent)))
    assertEquals(AnalysisAction.None, action(listOf(old), retries = ANALYSIS_AUTO_RETRIES))
  }
}

class AnalysisCandidateTest {
  private val auto = AnalysisSettings(auto = true)

  private fun candidate(
      record: UploadRecord? = null,
      complete: Boolean = true,
      cached: List<AnalysisDto> = emptyList(),
      config: RecordingConfig? = ECG,
  ) = mayNeedAnalysis(AnalysisNight("n1", "n1", record, config, complete, cached), INSTALLED, auto, NOW)

  @Test
  fun `a night that was just uploaded is checked`() {
    assertTrue(candidate(record = record(UploadPhase.UPLOADED), complete = false))
  }

  @Test
  fun `a night the server has without a run is checked, one with a run is not`() {
    assertTrue(candidate())
    assertTrue(!candidate(cached = listOf(run("succeeded"))))
    assertTrue(!candidate(record = record(UploadPhase.PROCESSING)))
    assertTrue(!candidate(record = record(UploadPhase.DONE)))
  }

  @Test
  fun `a night whose streams are unknown or that is not on the server is not checked`() {
    assertTrue(!candidate(config = null, record = record(UploadPhase.UPLOADED)))
    assertTrue(!candidate(complete = false))
  }
}

class AutomationTest {
  private class FakeEnvironment : AutomationEnvironment {
    override var account: String? = "acc"
    override var autoUpload = AutoUploadSettings(enabled = true)
    override var analysis = AnalysisSettings(auto = true, preferenceOrder = listOf("wav2sleep", "sleep-ecg", "baseline"))
    var nights = emptyList<UploadNight>()
    var analysisNights = emptyList<AnalysisNight>()
    var installed: List<AlgorithmDto>? = INSTALLED
    var runs = mutableMapOf<String, List<AnalysisDto>>()
    var refuse: Exception? = null
    val calls = mutableListOf<String>()

    override fun nowMs() = NOW

    override suspend fun uploadNights(only: String?) = nights.filter { only == null || it.id == only }

    override suspend fun queueUpload(id: String, replace: Boolean) {
      calls += "queueUpload $id replace=$replace"
    }

    override suspend fun stopAutoUploads() {
      calls += "stopAutoUploads"
    }

    override suspend fun analysisNights(only: String?) =
        analysisNights.filter { only == null || it.key == only }

    override fun session(): RemoteSession? =
        account?.let { RemoteSession(it, StubTaigaClient()) }

    override suspend fun installed(session: RemoteSession): List<AlgorithmDto>? {
      calls += "installed"
      return installed
    }

    override suspend fun runs(session: RemoteSession, recordingId: String): List<AnalysisDto>? {
      calls += "runs $recordingId"
      return runs[recordingId]
    }

    override suspend fun requestAnalysis(
        session: RemoteSession,
        night: AnalysisNight,
        choice: StagerChoice,
        retry: AnalysisDto?,
    ) {
      refuse?.let { throw it }
      calls += "request ${night.recordingId} ${choice.id} ${choice.version}${if (retry != null) " retry" else ""}"
    }

    override fun sessionRejected(session: RemoteSession) {
      calls += "sessionRejected"
    }

    override fun retriesUsed(recordingId: String, analysis: AnalysisDto) = 0
  }

  private fun uploaded(id: String, runs: List<AnalysisDto> = emptyList()) =
      AnalysisNight(id, id, record(UploadPhase.UPLOADED), ECG, true, runs)

  @Test
  fun `every recording that is on the phone and not uploaded is queued with unique work`() = runBlocking {
    val env = FakeEnvironment()
    env.nights =
        listOf(
            night("a"),
            night("b", record = record(UploadPhase.DONE)),
            night("c", state = RecordingPhase.RECORDING, live = true),
            night("d", record = record(UploadPhase.FAILED, UploadFailure.NETWORK)),
        )

    Automation(env).run(CatchUpReason.APP_START)

    assertEquals(
        listOf("queueUpload a replace=false", "queueUpload d replace=false"),
        env.calls.filter { it.startsWith("queueUpload") },
    )
  }

  @Test
  fun `a recording that finishes only checks that recording`() = runBlocking {
    val env = FakeEnvironment()
    env.nights = listOf(night("a"), night("b"))

    Automation(env).run(CatchUpReason.RECORDING_SAVED, only = "b")

    assertEquals(listOf("queueUpload b replace=false"), env.calls)
  }

  @Test
  fun `with automatic upload off nothing is queued and a switch off stops the automatic uploads`() =
      runBlocking {
        val env = FakeEnvironment()
        env.autoUpload = AutoUploadSettings(enabled = false)
        env.nights = listOf(night("a"))

        Automation(env).run(CatchUpReason.PERIODIC)
        assertTrue(env.calls.isEmpty())

        Automation(env).run(CatchUpReason.SETTING_CHANGED)
        assertEquals(listOf("stopAutoUploads"), env.calls)
      }

  @Test
  fun `changed conditions queue the due uploads again`() = runBlocking {
    val env = FakeEnvironment()
    env.nights = listOf(night("a", record = record(UploadPhase.QUEUED)))

    Automation(env).run(CatchUpReason.UPLOAD_CONDITIONS_CHANGED)

    assertEquals(listOf("queueUpload a replace=true"), env.calls)
  }

  @Test
  fun `nothing runs without an account`() = runBlocking {
    val env = FakeEnvironment().apply { account = null }
    env.nights = listOf(night("a"))

    Automation(env).run(CatchUpReason.APP_START)

    assertTrue(env.calls.isEmpty())
  }

  @Test
  fun `an uploaded night with no analysis gets the first compatible method after the server was asked`() =
      runBlocking {
        val env = FakeEnvironment()
        env.analysisNights = listOf(uploaded("a"))
        env.runs["a"] = emptyList()

        Automation(env).run(CatchUpReason.UPLOAD_FINISHED, only = "a")

        assertEquals(listOf("installed", "runs a", "request a wav2sleep 1.1.0"), env.calls)
      }

  @Test
  fun `a night without ecg uses the next compatible method`() = runBlocking {
    val env = FakeEnvironment()
    env.analysisNights = listOf(uploaded("a").copy(config = HR_ONLY))
    env.runs["a"] = emptyList()

    Automation(env).run(CatchUpReason.APP_START)

    assertEquals("request a sleep-ecg 1.0.0", env.calls.last())
  }

  @Test
  fun `the same analysis is not requested twice when the server already has one`() = runBlocking {
    val env = FakeEnvironment()
    env.analysisNights = listOf(uploaded("a"))
    // The saved state is behind: the server already queued a run.
    env.runs["a"] = listOf(run("queued"))

    Automation(env).run(CatchUpReason.PERIODIC)
    Automation(env).run(CatchUpReason.PERIODIC)

    assertTrue(env.calls.none { it.startsWith("request") })
  }

  @Test
  fun `nights that are analysed, analysing or whose streams are unknown cost no request`() =
      runBlocking {
        val env = FakeEnvironment()
        env.analysisNights =
            listOf(
                AnalysisNight("done", "done", record(UploadPhase.DONE), ECG, true, listOf(run("succeeded"))),
                AnalysisNight("busy", "busy", record(UploadPhase.PROCESSING), ECG, true, listOf(run("queued"))),
                uploaded("blind").copy(config = null),
            )

        Automation(env).run(CatchUpReason.PERIODIC)

        assertTrue(env.calls.none { it.startsWith("runs") || it.startsWith("request") })
      }

  @Test
  fun `automatic analysis off requests nothing and does not talk to the server`() = runBlocking {
    val env = FakeEnvironment()
    env.analysis = env.analysis.copy(auto = false)
    env.analysisNights = listOf(uploaded("a"))

    Automation(env).run(CatchUpReason.SETTING_CHANGED)

    assertTrue(env.calls.isEmpty())
  }

  @Test
  fun `a failed analysis is retried only when the failure was a passing one`() = runBlocking {
    val env = FakeEnvironment()
    env.analysisNights =
        listOf(uploaded("lasting"), uploaded("passing"))
    env.runs["lasting"] = listOf(run("failed", error = "bad_ecg: ecg is unusable"))
    env.runs["passing"] = listOf(run("failed", error = "job timed out after 600s", hoursAgo = 8))

    Automation(env).run(CatchUpReason.PERIODIC)

    assertEquals(
        listOf("request passing wav2sleep 1.0.0 retry"),
        env.calls.filter { it.startsWith("request") },
    )
  }

  @Test
  fun `a refused request does not stop the other nights`() = runBlocking {
    val env = FakeEnvironment()
    env.analysisNights = listOf(uploaded("a"), uploaded("b"))
    env.runs["a"] = emptyList()
    env.runs["b"] = emptyList()
    env.refuse = TaigaHttpException(422, "no worker offers the algorithm")

    Automation(env).run(CatchUpReason.PERIODIC)

    assertEquals(listOf("installed", "runs a", "runs b"), env.calls)
  }

  @Test
  fun `a rejected token ends the catch-up`() = runBlocking {
    val env = FakeEnvironment()
    env.analysisNights = listOf(uploaded("a"), uploaded("b"))
    env.runs["a"] = emptyList()
    env.refuse = com.rbmr.taigasleep.backend.TaigaUnauthorizedException(401, "expired")

    Automation(env).run(CatchUpReason.PERIODIC)

    assertEquals(listOf("installed", "runs a", "sessionRejected"), env.calls)
  }

  @Test
  fun `an unanswered server leaves the saved state for the next catch-up`() = runBlocking {
    val env = FakeEnvironment()
    env.analysisNights = listOf(uploaded("a"))
    env.installed = null

    Automation(env).run(CatchUpReason.PERIODIC)

    assertEquals(listOf("installed"), env.calls)
  }

  // The triggers that come from state.

  private fun watched(block: (Watched) -> Unit): List<CatchUpReason> {
    val w = Watched()
    val seen = mutableListOf<CatchUpReason>()
    Automation(FakeEnvironment())
        .watch(
            scope = CoroutineScope(Dispatchers.Unconfined),
            uploadSettings = w.upload,
            analysisAuto = w.analysis,
            accounts = w.account,
            connected = w.connected,
            onTrigger = { seen += it },
        )
    block(w)
    return seen
  }

  private class Watched {
    val upload = MutableStateFlow(AutoUploadSettings(enabled = false))
    val analysis = MutableStateFlow(false)
    val account = MutableStateFlow<String?>("acc")
    val connected = MutableStateFlow(false)
  }

  @Test
  fun `the state at start is not a change`() {
    assertTrue(watched { }.isEmpty())
  }

  @Test
  fun `switching automatic upload or analysis triggers a catch-up`() {
    val seen =
        watched {
          it.upload.value = AutoUploadSettings(enabled = true)
          it.analysis.value = true
        }

    assertEquals(listOf(CatchUpReason.SETTING_CHANGED, CatchUpReason.SETTING_CHANGED), seen)
  }

  @Test
  fun `changing wifi only or charging only triggers the conditions catch-up`() {
    val seen =
        watched {
          it.upload.value = AutoUploadSettings(enabled = false, wifiOnly = false)
        }

    assertEquals(listOf(CatchUpReason.UPLOAD_CONDITIONS_CHANGED), seen)
  }

  @Test
  fun `the server coming back and a new account trigger a catch-up, a logout does not`() {
    val seen =
        watched {
          it.connected.value = true
          it.connected.value = false
          it.account.value = null
          it.account.value = "other"
          it.connected.value = true
        }

    assertEquals(
        listOf(
            CatchUpReason.SERVER_CONNECTED,
            CatchUpReason.ACCOUNT_CHANGED,
            CatchUpReason.SERVER_CONNECTED,
        ),
        seen,
    )
  }

  @Test
  fun `catch-ups that follow each other are idempotent`() = runBlocking {
    val env = FakeEnvironment()
    env.nights = listOf(night("a"))
    val automation = Automation(env)

    automation.run(CatchUpReason.APP_RESUME)
    automation.run(CatchUpReason.APP_RESUME)

    // The unique work per recording makes the second queueing a no-op; the catch-up itself repeats.
    assertEquals(2, env.calls.count { it == "queueUpload a replace=false" })
  }
}
