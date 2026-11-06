package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.recording.store.RecordingConfig
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
  @Test
  fun `a finished recording is marked local and then queued`() {
    val environment = FakeEnvironment()

    SyncService(environment).onRecordingFinished("r1")

    assertEquals(listOf("markLocal r1", "enqueueAuto r1"), environment.calls)
  }

  @Test
  fun `a finished recording with automatic upload off is only marked local`() {
    val environment = FakeEnvironment().apply { autoUpload = AutoUploadSettings(enabled = false) }

    SyncService(environment).onRecordingFinished("r1")

    assertEquals(listOf("markLocal r1"), environment.calls)
  }

  @Test
  fun `a finished recording without a login is not queued`() {
    val environment = FakeEnvironment().apply { hasSession = false }

    SyncService(environment).onRecordingFinished("r1")

    assertEquals(listOf("markLocal r1"), environment.calls)
  }

  @Test
  fun `a finished recording whose files are gone is not queued`() {
    val environment = FakeEnvironment().apply { files = false }

    SyncService(environment).onRecordingFinished("r1")

    assertEquals(listOf("markLocal r1"), environment.calls)
  }

  @Test
  fun `a recording that already has sync state keeps it and is queued`() {
    val environment = FakeEnvironment()
    environment.state["r1"] = UploadRecord(id = "r1", phase = UploadPhase.QUEUED)

    SyncService(environment).onRecordingFinished("r1")

    assertEquals(listOf("enqueueAuto r1"), environment.calls)
  }

  @Test
  fun `deleting a night that is uploading stops the queue before the night goes`() {
    val environment = FakeEnvironment()
    environment.state["r1"] = UploadRecord(id = "r1", phase = UploadPhase.UPLOADING, progress = 0.4f)

    SyncService(environment).delete("r1", "r1")

    assertEquals(listOf("cancelUploads r1", "deleteRecording r1 r1"), environment.calls)
  }

  @Test
  fun `deleting a night that is only on the server cancels no upload`() {
    val environment = FakeEnvironment()

    SyncService(environment).delete(null, "server-1")

    assertEquals(listOf("deleteRecording null server-1"), environment.calls)
  }

  @Test
  fun `deleting nothing does nothing`() {
    val environment = FakeEnvironment()

    SyncService(environment).delete(null, null)

    assertEquals(listOf("deleteRecording null null"), environment.calls)
  }

  @Test
  fun `an explicit request is remembered before the night is read back`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.started = listOf(StartedAnalysis("a-baseline", "queued"))

    val result = SyncService(environment).requestAnalysis("r1", "baseline")

    assertTrue(result.isSuccess)
    assertEquals(
        listOf("startManual r1 baseline", "markManualAnalysis r1 a-baseline", "markProcessing r1"),
        environment.calls,
    )
  }

  @Test
  fun `requesting an analysis without a login asks for nothing`() = runBlocking {
    val environment = FakeEnvironment()

    val result = SyncService(environment).requestAnalysis("r1", "baseline")

    assertEquals("login required", result.exceptionOrNull()?.message)
    assertEquals(emptyList<String>(), environment.calls)
  }

  @Test
  fun `an unreachable server leaves the night unmarked`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.requestFailure = TaigaNetworkException(IOException("offline"))

    val result = SyncService(environment).requestAnalysis("r1", "baseline")

    assertTrue(result.exceptionOrNull() is TaigaNetworkException)
    assertEquals(listOf("startManual r1 baseline"), environment.calls)
  }

  @Test
  fun `a refresh without a login does not call the server`() = runBlocking {
    val environment = FakeEnvironment()

    val result = SyncService(environment).refresh("remote-r1", listOf("a1"))

    assertEquals("login required", result.exceptionOrNull()?.message)
    assertEquals(emptyList<String>(), environment.calls)
  }

  @Test
  fun `a rejected token logs out once`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaUnauthorizedException(401, "the session expired")

    val result = SyncService(environment).refresh("r1")

    assertTrue(result.isFailure)
    assertEquals(listOf("refresh r1", "logout"), environment.calls)
  }

  @Test
  fun `an interrupted delete queue is restored before the uploads`() = runBlocking {
    val environment = FakeEnvironment()

    SyncService(environment).reconcileAll()

    assertEquals(listOf("resumePendingDeletes", "reconcileUploads"), environment.calls)
  }

  @Test
  fun `a refresh that answers 404 makes a night with a local copy local only`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaHttpException(404, "recording not found")
    environment.state["r1"] = UploadRecord(id = "r1", onServer = true, phase = UploadPhase.DONE)

    val result = SyncService(environment).refresh("r1")

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

    val result = SyncService(environment).refresh("remote-r1")

    assertTrue(result.exceptionOrNull() is RecordingNotOnServerException)
    assertEquals(listOf("refresh remote-r1"), environment.calls)
  }

  @Test
  fun `a refresh that fails with a network error is not a missing night`() = runBlocking {
    val environment = FakeEnvironment()
    environment.client = StubTaigaClient()
    environment.refreshFailure = TaigaNetworkException(IOException("offline"))

    val result = SyncService(environment).refresh("r1")

    assertTrue(result.exceptionOrNull() is TaigaNetworkException)
    assertEquals(listOf("refresh r1"), environment.calls)
  }

  @Test
  fun `a retry keeps the analysis the screen is showing`() = runBlocking {
    val environment = FakeEnvironment()

    SyncService(environment).retryAnalysis("r1", "a1")

    assertEquals(listOf("retry r1 a1"), environment.calls)
  }

  @Test
  fun `the analysis list comes from the logged-in server`() = runBlocking {
    val environment = FakeEnvironment()

    assertEquals("login required", SyncService(environment).installedAlgorithms().exceptionOrNull()?.message)

    environment.client = StubTaigaClient()
    assertTrue(SyncService(environment).installedAlgorithms().isSuccess)
    assertEquals(listOf("installedAlgorithms"), environment.calls)
  }

  @Test
  fun `a recording gone from the server is local again on the phone`() {
    val environment = FakeEnvironment()
    environment.state["r1"] = UploadRecord(id = "r1", onServer = true, phase = UploadPhase.DONE)

    SyncService(environment).recordingGone("r1")

    assertEquals(listOf("markLocal r1"), environment.calls)
    assertFalse(environment.state.getValue("r1").onServer)
  }

  @Test
  fun `a recording gone from the server fails the night waiting for an analysis without a local copy`() {
    val environment = FakeEnvironment().apply { files = false }
    environment.state["remote-r1"] =
        UploadRecord(id = "remote-r1", onServer = true, phase = UploadPhase.PROCESSING)

    SyncService(environment).recordingGone("remote-r1", "recording not found")

    assertEquals(listOf("markAnalysisFailed remote-r1 recording not found"), environment.calls)
  }

  private class FakeEnvironment : SyncEnvironment {
    val calls = mutableListOf<String>()
    val state = mutableMapOf<String, UploadRecord>()
    private val stateFlow = MutableStateFlow<Map<String, UploadRecord>>(emptyMap())

    override val records: StateFlow<Map<String, UploadRecord>> = stateFlow

    override var autoUpload = AutoUploadSettings()
    override var hasSession = true
    var files = true
    var client: TaigaClient? = null
    var streams = RecordingConfig(hr = true, ecg = false, acc = false)
    var started = listOf(StartedAnalysis("a1", "queued"))
    var requestFailure: Exception? = null
    var refreshFailure: Exception? = null

    override fun record(id: String): UploadRecord? = state[id]

    override fun markLocal(id: String) {
      calls += "markLocal $id"
      state[id] = UploadRecord(id = id, phase = UploadPhase.LOCAL)
    }

    override fun markProcessing(id: String) {
      calls += "markProcessing $id"
      state[id] = (state[id] ?: UploadRecord(id = id)).copy(phase = UploadPhase.PROCESSING)
    }

    override fun markAnalysisFailed(id: String, detail: String?) {
      calls += "markAnalysisFailed $id $detail"
      state[id] = (state[id] ?: UploadRecord(id = id)).copy(phase = UploadPhase.FAILED)
    }

    override fun markManualAnalysis(id: String, analysisId: String) {
      calls += "markManualAnalysis $id $analysisId"
    }

    override fun api(): TaigaClient? = client

    override fun logout() {
      calls += "logout"
    }

    override fun hasRecording(id: String): Boolean = files

    override fun config(id: String): RecordingConfig = streams

    override fun enqueueAuto(id: String) {
      calls += "enqueueAuto $id"
    }

    override fun enqueueManual(id: String) {
      calls += "enqueueManual $id"
    }

    override fun cancelUploads(id: String) {
      calls += "cancelUploads $id"
    }

    override fun enqueueDelete(remoteId: String) {
      calls += "enqueueDelete $remoteId"
    }

    override fun resumePendingDeletes() {
      calls += "resumePendingDeletes"
    }

    override suspend fun reconcileUploads() {
      calls += "reconcileUploads"
    }

    override fun rescheduleAll() {
      calls += "rescheduleAll"
    }

    override fun cancelLegacyPolls() {
      calls += "cancelLegacyPolls"
    }

    override fun deleteRecording(localId: String?, remoteId: String?) {
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
        api: TaigaClient,
        recordingId: String,
        config: RecordingConfig,
        algorithm: String,
    ): List<StartedAnalysis> {
      calls += "startManual $recordingId $algorithm"
      requestFailure?.let { throw it }
      return started
    }

    override suspend fun startPreferred(
        api: TaigaClient,
        recordingId: String,
        config: RecordingConfig,
    ): StartedAnalysis {
      calls += "startPreferred $recordingId"
      requestFailure?.let { throw it }
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
        id: String,
        recordingId: String,
        preferred: List<String?>,
    ): RecordingSnapshot {
      calls += "refresh $id"
      refreshFailure?.let { throw it }
      return RecordingSnapshot(RecordingDto(id = recordingId), emptyList(), null)
    }
  }
}
