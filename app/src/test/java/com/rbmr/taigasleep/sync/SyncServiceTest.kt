package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequences [SyncService] owns, checked against a fake environment: what a finished recording
 * leads to, what a delete does to a running upload, and what a missing login or an unreachable
 * server changes.
 */
class SyncServiceTest {
  /**
   * The service's steps that a caller does not wait for run on its scope; here they run at once, so
   * what a call leads to is asserted right after it.
   */
  private fun service(environment: SyncEnvironment) =
      SyncService(environment, CoroutineScope(Dispatchers.Unconfined))

  @Test
  fun `a finished recording is marked local and then caught up`() {
    val environment = FakeEnvironment()

    service(environment).onRecordingFinished("r1")

    assertEquals(listOf("markLocal r1", "catchUp RECORDING_SAVED r1"), environment.calls)
  }

  @Test
  fun `a recording that already has sync state keeps it and is caught up`() {
    val environment = FakeEnvironment()
    environment.state["r1"] = UploadRecord(id = "r1", phase = UploadPhase.QUEUED)

    service(environment).onRecordingFinished("r1")

    assertEquals(listOf("catchUp RECORDING_SAVED r1"), environment.calls)
  }

  @Test
  fun `a recording that got the rest of its night from the tracker is uploaded as a new upload`() {
    val environment = FakeEnvironment()
    // An earlier attempt met a finished upload, or failed: neither says anything about the samples
    // that just arrived, so the state starts again.
    environment.state["r1"] =
        UploadRecord(
            id = "r1",
            onServer = true,
            phase = UploadPhase.DONE,
            progress = 1f,
            uploadedAtMs = 1_000,
            confirmedOnServer = true,
        )

    service(environment).onFetchCompleted("r1")

    assertEquals(
        listOf("cancelUploads r1", "markLocal r1", "catchUp RECORDING_SAVED r1"),
        environment.calls,
    )
    assertEquals(UploadPhase.LOCAL, environment.state.getValue("r1").phase)
    assertFalse(environment.state.getValue("r1").onServer)
  }

  @Test
  fun `the start schedules the safety net, watches the triggers and catches up`() {
    val environment = FakeEnvironment()

    service(environment).start()

    assertEquals(
        listOf("schedulePeriodicCatchUp", "watchTriggers", "catchUp APP_START null"),
        environment.calls,
    )
  }

  @Test
  fun `the worker catch-up passes its reason and recording on`() = runBlocking {
    val environment = FakeEnvironment()

    service(environment).catchUp(CatchUpReason.UPLOAD_FINISHED, "r1")

    assertEquals(listOf("catchUp UPLOAD_FINISHED r1"), environment.calls)
  }

  @Test
  fun `deleting a night that is uploading stops the queue before the night goes`() {
    val environment = FakeEnvironment()
    environment.state["r1"] = UploadRecord(id = "r1", phase = UploadPhase.UPLOADING, progress = 0.4f)

    service(environment).delete("r1", "r1")

    assertEquals(listOf("cancelUploads r1", "deleteRecording r1 r1"), environment.calls)
  }

  @Test
  fun `deleting a night that is only on the server cancels no upload`() {
    val environment = FakeEnvironment()

    service(environment).delete(null, "server-1")

    assertEquals(listOf("deleteRecording null server-1"), environment.calls)
  }

  @Test
  fun `deleting nothing does nothing`() {
    val environment = FakeEnvironment()

    service(environment).delete(null, null)

    assertEquals(listOf("deleteRecording null null"), environment.calls)
  }

  @Test
  fun `an explicit request is remembered before the night is read back`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.started = listOf(StartedAnalysis("a-baseline", "queued"))

    val result = service(environment).requestAnalysis("r1", "baseline")

    assertTrue(result.isSuccess)
    assertEquals(
        listOf("startManual r1 baseline", "markProcessing r1", "markManualAnalysis r1 a-baseline"),
        environment.calls,
    )
  }

  @Test
  fun `requesting an analysis without a login asks for nothing`() = runBlocking {
    val environment = FakeEnvironment()

    val result = service(environment).requestAnalysis("r1", "baseline")

    assertEquals("login required", result.exceptionOrNull()?.message)
    assertEquals(emptyList<String>(), environment.calls)
  }

  @Test
  fun `an unreachable server leaves the night unmarked`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.requestFailure = TaigaNetworkException(IOException("offline"))

    val result = service(environment).requestAnalysis("r1", "baseline")

    assertTrue(result.exceptionOrNull() is TaigaNetworkException)
    assertEquals(listOf("startManual r1 baseline"), environment.calls)
  }

  @Test
  fun `a refresh without a login does not call the server`() = runBlocking {
    val environment = FakeEnvironment()

    val result = service(environment).refresh("remote-r1", listOf("a1"))

    assertEquals("login required", result.exceptionOrNull()?.message)
    assertEquals(emptyList<String>(), environment.calls)
  }

  @Test
  fun `a rejected token logs out once`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaUnauthorizedException(401, "the session expired")

    val result = service(environment).refresh("r1")

    assertTrue(result.isFailure)
    assertEquals(listOf("refresh r1", "logout"), environment.calls)
  }

  @Test
  fun `an interrupted delete queue is restored before the uploads`() = runBlocking {
    val environment = FakeEnvironment()

    service(environment).reconcileAll()

    assertEquals(listOf("resumePendingDeletes", "catchUp APP_RESUME null"), environment.calls)
  }

  @Test
  fun `a refresh that answers 404 makes a night with a local copy local only`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaHttpException(404, "recording not found")
    environment.state["r1"] = UploadRecord(id = "r1", onServer = true, phase = UploadPhase.DONE)

    val result = service(environment).refresh("r1")

    assertTrue(result.exceptionOrNull() is RecordingNotOnServerException)
    assertEquals(listOf("refresh r1", "markLocal r1"), environment.calls)
    assertFalse(environment.state.getValue("r1").onServer)
    assertEquals(UploadPhase.LOCAL, environment.state.getValue("r1").phase)
  }

  @Test
  fun `a refresh that answers 404 for a night only on the server marks nothing local`() = runBlocking {
    val environment = FakeEnvironment().apply { files = false }
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaHttpException(404, "recording not found")

    val result = service(environment).refresh("remote-r1")

    assertTrue(result.exceptionOrNull() is RecordingNotOnServerException)
    assertEquals(listOf("refresh remote-r1"), environment.calls)
  }

  @Test
  fun `a refresh that fails with a network error is not a missing night`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaNetworkException(IOException("offline"))

    val result = service(environment).refresh("r1")

    assertTrue(result.exceptionOrNull() is TaigaNetworkException)
    assertEquals(listOf("refresh r1"), environment.calls)
  }

  @Test
  fun `a refresh saves the server summary and the run it shows`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.shown = AnalysisDto(id = "a2", recording = "r1")

    service(environment).refresh("r1")

    assertEquals(listOf("$SESSION_ACCOUNT r1"), environment.saved)
    assertEquals(listOf("r1 a2 $SESSION_ACCOUNT"), environment.selections)
  }

  @Test
  fun `a refresh whose account changed while it ran saves nothing`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.accountOnRefresh = OTHER_ACCOUNT

    service(environment).refresh("r1")

    assertEquals(emptyList<String>(), environment.saved)
    assertEquals(emptyList<String>(), environment.selections)
  }

  @Test
  fun `a refresh that answers 404 forgets the server row`() = runBlocking {
    val environment = FakeEnvironment().apply { files = false }
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaHttpException(404, "recording not found")

    service(environment).refresh("remote-r1")

    assertEquals(listOf("r1"), environment.forgotten)
  }

  @Test
  fun `selecting a run stores the selection`() {
    val environment = FakeEnvironment()

    service(environment).selectAnalysis("r1", "a2")

    assertEquals(listOf("r1 a2 -"), environment.selections)
  }

  @Test
  fun `a retry keeps the analysis the screen is showing`() = runBlocking {
    val environment = FakeEnvironment()

    service(environment).retryAnalysis("r1", "a1")

    assertEquals(listOf("retry r1 a1"), environment.calls)
  }

  @Test
  fun `the analysis list comes from the logged-in server`() = runBlocking {
    val environment = FakeEnvironment()

    assertEquals("login required", service(environment).installedAlgorithms().exceptionOrNull()?.message)

    environment.client = StubTaigaClient()
    assertTrue(service(environment).installedAlgorithms().isSuccess)
    assertEquals(listOf("installedAlgorithms"), environment.calls)
  }

  @Test
  fun `a recording gone from the server is local again on the phone`() = runBlocking {
    val environment = FakeEnvironment()
    environment.state["r1"] = UploadRecord(id = "r1", onServer = true, phase = UploadPhase.DONE)

    service(environment).recordingGone("r1")

    assertEquals(listOf("markLocal r1"), environment.calls)
    assertFalse(environment.state.getValue("r1").onServer)
  }

  @Test
  fun `a recording gone from the server fails the night waiting for an analysis without a local copy`() =
      runBlocking {
        val environment = FakeEnvironment().apply { files = false }
        environment.state["remote-r1"] =
            UploadRecord(id = "remote-r1", onServer = true, phase = UploadPhase.PROCESSING)

        service(environment).recordingGone("remote-r1", "recording not found")

        assertEquals(listOf("markAnalysisFailed remote-r1 recording not found"), environment.calls)
      }

  private companion object {
    const val SESSION_ACCOUNT = "https://sleep.example|u1"
    const val OTHER_ACCOUNT = "https://sleep.example|u2"
  }

  private class FakeEnvironment : SyncEnvironment {
    val calls = mutableListOf<String>()
    val state = mutableMapOf<String, UploadRecord>()
    private val stateFlow = MutableStateFlow<Map<String, UploadRecord>>(emptyMap())

    // What the service stored, as the assertions read it: "account recording", "key run account".
    val saved = mutableListOf<String>()
    val selections = mutableListOf<String>()
    val forgotten = mutableListOf<String>()
    private val selected = mutableMapOf<String, String?>()

    override val records: StateFlow<Map<String, UploadRecord>> = stateFlow

    var files = true
    var client: TaigaClient? = null
    var started = listOf(StartedAnalysis("a1", "queued"))
    var requestFailure: Exception? = null
    var refreshFailure: Exception? = null
    var shown: AnalysisDto? = null

    /** The account a refresh finds active when it answers, as a login in between would leave it. */
    var accountOnRefresh: String? = null

    var activeAccount: String? = SESSION_ACCOUNT

    override suspend fun record(id: String, account: String?): UploadRecord? = state[id]

    override suspend fun markLocal(id: String, account: String?) {
      calls += "markLocal $id"
      state[id] = UploadRecord(id = id, phase = UploadPhase.LOCAL)
    }

    override suspend fun markProcessing(id: String, account: String?) {
      calls += "markProcessing $id"
      state[id] = (state[id] ?: UploadRecord(id = id)).copy(phase = UploadPhase.PROCESSING)
    }

    override suspend fun markAnalysisFailed(id: String, detail: String?, account: String?) {
      calls += "markAnalysisFailed $id $detail"
      state[id] = (state[id] ?: UploadRecord(id = id)).copy(phase = UploadPhase.FAILED)
    }

    override suspend fun markManualAnalysis(id: String, analysisId: String, account: String?) {
      calls += "markManualAnalysis $id $analysisId"
    }

    override val account: String?
      get() = activeAccount

    override fun session(): RemoteSession? {
      val api = client ?: return null
      return activeAccount?.let { RemoteSession(it, api) }
    }

    override fun logout() {
      calls += "logout"
    }

    override fun hasRecording(id: String): Boolean = files

    override suspend fun enqueueManual(id: String) {
      calls += "enqueueManual $id"
    }

    override fun cancelUploads(id: String) {
      calls += "cancelUploads $id"
    }

    override fun enqueueDelete(remoteId: String) {
      calls += "enqueueDelete $remoteId"
    }

    override suspend fun resumePendingDeletes() {
      calls += "resumePendingDeletes"
    }

    override suspend fun catchUp(reason: CatchUpReason, only: String?) {
      calls += "catchUp $reason $only"
    }

    override fun schedulePeriodicCatchUp() {
      calls += "schedulePeriodicCatchUp"
    }

    override fun watchTriggers(scope: CoroutineScope) {
      calls += "watchTriggers"
    }

    override suspend fun deleteRecording(localId: String?, remoteId: String?) {
      calls += "deleteRecording $localId $remoteId"
    }

    override suspend fun enforceRawRetention() {
      calls += "enforceRawRetention"
    }

    override suspend fun deleteRaw(id: String): RawActionResult {
      calls += "deleteRaw $id"
      return RawActionResult.Done
    }

    override suspend fun restoreRaw(id: String, onProgress: (Float) -> Unit): Result<Unit> {
      calls += "restoreRaw $id"
      return Result.success(Unit)
    }

    override suspend fun startManual(
        session: RemoteSession,
        id: String,
        algorithm: String,
    ): List<StartedAnalysis> {
      calls += "startManual ${recordingIdOf(id)} $algorithm"
      requestFailure?.let { throw it }
      markProcessing(id, session.account)
      return started
    }

    override suspend fun startPreferred(session: RemoteSession, id: String): StartedAnalysis {
      calls += "startPreferred ${recordingIdOf(id)}"
      requestFailure?.let { throw it }
      markProcessing(id, session.account)
      return started.first()
    }

    override suspend fun installedAlgorithms(api: TaigaClient): Result<List<AlgorithmDto>> {
      calls += "installedAlgorithms"
      return Result.success(emptyList())
    }

    override suspend fun retry(id: String, analysisId: String?): Result<Unit> {
      calls += "retry $id $analysisId"
      return Result.success(Unit)
    }

    override suspend fun refresh(
        api: TaigaClient,
        account: String,
        id: String,
        recordingId: String,
        preferred: List<String?>,
    ): RecordingSnapshot {
      calls += "refresh $id"
      refreshFailure?.let { throw it }
      accountOnRefresh?.let { activeAccount = it }
      return RecordingSnapshot(RecordingDto(id = recordingId), emptyList(), shown)
    }

    override fun observeRows(account: String, localIds: List<String>): Flow<List<LibraryEntry>> =
        flowOf(emptyList())

    override suspend fun loadRows(account: String, localIds: List<String>): List<LibraryEntry> =
        emptyList()

    override suspend fun cachedResult(
        key: String,
        analysisId: String?,
        account: String?,
    ): AnalysisDto? = null

    override val pendingDeletes: StateFlow<Map<String, PendingDelete>> = MutableStateFlow(emptyMap())

    override suspend fun uploadedElsewhere(localId: String): Boolean = false

    override suspend fun selectedAnalysis(key: String, account: String?): String? = selected[key]

    override suspend fun selectAnalysis(key: String, analysisId: String?, account: String?) {
      selections += "$key ${analysisId ?: "-"} ${account ?: "-"}"
      selected[key] = analysisId
    }

    override suspend fun saveList(account: String, list: RecordingSummaryListDto) {
      calls += "saveList $account"
    }

    override suspend fun saveSummary(account: String, snapshot: RecordingSnapshot) {
      saved += "$account ${snapshot.recording.id}"
    }

    override suspend fun hiddenRecordings(): Set<String> = emptySet()

    override suspend fun isGone(recordingId: String): Boolean = false

    override suspend fun forget(recordingId: String) {
      forgotten += recordingId
    }
  }
}
