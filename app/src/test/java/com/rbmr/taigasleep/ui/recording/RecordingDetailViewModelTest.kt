package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.RecordingSource
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.sync.RawActionResult
import com.rbmr.taigasleep.sync.RecordingNotOnServerException
import com.rbmr.taigasleep.sync.UploadFailure
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val TEXTS =
    RecordingTexts(
        loadFailed = "could not load",
        loginError = "please log in",
        rawRestoreFailed = "could not restore",
        rawDeleteFailed = "could not delete",
        rawNotUploaded = "not uploaded yet",
    )

private class FakeLibrary(initial: List<RecordingView>) : RecordingSource {
  override val recordings = MutableStateFlow(initial)
  override val remoteStatus = MutableStateFlow<RemoteStatus>(RemoteStatus.Connected("a@b.c", 0))
  val calls = mutableListOf<String>()
  var cached: AnalysisDto? = null
  var fresh: Result<AnalysisDto?> = Result.success(null)
  var algorithms: Result<List<AlgorithmDto>> = Result.success(emptyList())
  var manual: Result<Unit> = Result.success(Unit)

  override fun reload() {
    calls += "reload"
  }

  override fun recording(key: String): RecordingView? =
      recordings.value.firstOrNull { it.key == key }

  override fun cachedResult(key: String, analysisId: String?): AnalysisDto? = cached

  override suspend fun refreshRecording(
      key: String,
      remoteId: String?,
      analysisId: String?,
  ): Result<AnalysisDto?> {
    calls += "refresh:$key:$remoteId:$analysisId"
    return fresh
  }

  override suspend fun compatibleAlgorithms(recording: RecordingView): Result<List<AlgorithmDto>> {
    calls += "algorithms:${recording.key}"
    return algorithms
  }

  override suspend fun requestManual(
      recording: RecordingView,
      algorithmId: String,
  ): Result<Unit> {
    calls += "manual:${recording.key}:$algorithmId"
    return manual
  }

  override fun selectAnalysis(recording: RecordingView, analysisId: String) {
    calls += "select:${recording.key}:$analysisId"
  }
}

private class FakeWork : RecordingWork {
  val calls = mutableListOf<String>()
  var rawSize = 2048L
  var share: Result<Unit> = Result.success(Unit)
  var restore: Result<Unit> = Result.success(Unit)
  var deleteRaw: RawActionResult = RawActionResult.Done
  var retry: Result<Unit> = Result.success(Unit)

  /** What restoring the files does to the phone, such as writing them and restoring the night. */
  var restores: () -> Unit = {}

  override suspend fun refreshRecordings() {
    calls += "refreshRecordings"
  }

  override fun upload(localId: String) {
    calls += "upload:$localId"
  }

  override suspend fun retryAnalysis(key: String, analysisId: String?): Result<Unit> {
    calls += "retry:$key:$analysisId"
    return retry
  }

  override suspend fun restoreRaw(
      localId: String,
      remoteId: String,
      onProgress: (Float) -> Unit,
  ): Result<Unit> {
    calls += "restore:$localId:$remoteId"
    onProgress(1f)
    if (restore.isSuccess) restores()
    return restore
  }

  override suspend fun share(localId: String, onProgress: (Float) -> Unit): Result<Unit> {
    calls += "share:$localId"
    onProgress(1f)
    return share
  }

  override suspend fun deleteRaw(localId: String): RawActionResult {
    calls += "deleteRaw:$localId"
    return deleteRaw
  }

  override fun delete(localId: String?, remoteId: String?) {
    calls += "delete:$localId:$remoteId"
  }

  override fun rawSize(localId: String): Long {
    calls += "rawSize:$localId"
    return rawSize
  }
}

private fun recording(id: String, hasRaw: Boolean = true) =
    Recording(
        id = id,
        dir = File("/tmp/$id"),
        state = RecordingPhase.STOPPED,
        startUtcMs = 1_790_000_000_000,
        endUtcMs = 1_790_003_600_000,
        sizeBytes = 1024,
        sensorName = "Polar H10",
        hasRaw = hasRaw,
    )

private fun recording(
    localId: String? = "local",
    remoteId: String? = "r1",
    status: RecordingStatus = RecordingStatus.READY,
    failure: UploadFailure? = null,
    analysisId: String? = "a1",
    resultDownloaded: Boolean = false,
    hasRaw: Boolean = true,
    analyses: List<Analysis> = listOf(run("a1")),
) = RecordingView(
    key = localId ?: "remote-$remoteId",
    localId = localId,
    remoteId = remoteId,
    startUtcMs = 1_790_000_000_000,
    endUtcMs = 1_790_003_600_000,
    durationMs = 3_600_000,
    device = "Polar H10",
    status = status,
    progress = 0f,
    failure = failure,
    errorDetail = null,
    warning = null,
    analyses = analyses,
    selectedAnalysisId = analysisId,
    backup = BackupState(),
    local = localId?.let { recording(it, hasRaw) },
    remote = remoteId?.let { RecordingDto(id = it, device = "Polar H10") },
    resultDownloaded = resultDownloaded,
)

private fun run(id: String, status: AnalysisStatus = AnalysisStatus.DONE) =
    Analysis(
        id = id,
        algorithm = "stager",
        version = "1",
        status = status,
        segments = listOf(StageSegmentDto("sleep", 4)),
        sleepScore = 80.0,
    )

private fun payload(id: String) =
    AnalysisDto(
        id = id,
        algorithm = "stager",
        version = "1",
        status = "succeeded",
        result = AnalysisResultDto(items = emptyList()),
    )

class RecordingDetailViewModelTest {
  private val library = FakeLibrary(listOf(recording()))
  private val work = FakeWork()

  private fun viewModel(
      localId: String? = "local",
      remoteId: String? = "r1",
      analysisId: String? = "a1",
  ): RecordingDetailViewModel =
      RecordingDetailViewModel(
          key = localId ?: "remote-$remoteId",
          localId = localId,
          remoteId = remoteId,
          analysisId = analysisId,
          library = library,
          work = work,
          texts = TEXTS,
          dispatcher = Dispatchers.Unconfined,
      )

  @Test
  fun `opening the screen reads the night and its result`() {
    library.fresh = Result.success(payload("a1"))
    val model = viewModel()

    assertEquals(RecordingStatus.READY, model.state.value.status)
    assertEquals("a1", model.state.value.result?.id)
    assertFalse(model.state.value.loading)
    assertEquals("refresh:local:r1:a1", library.calls.last { it.startsWith("refresh") })
  }

  @Test
  fun `the state follows the library when a newer night arrives`() {
    val model = viewModel()

    library.recordings.value = listOf(recording(status = RecordingStatus.PROCESSING, analysisId = null))

    assertEquals(RecordingStatus.PROCESSING, model.state.value.status)
    assertEquals(null, model.state.value.selectedAnalysisId)
  }

  @Test
  fun `the cached payload of the newly selected run replaces the shown one`() {
    val model = viewModel()
    library.cached = payload("a2")

    library.recordings.value = listOf(recording(analysisId = "a2", analyses = listOf(run("a1"), run("a2"))))

    assertEquals("a2", model.state.value.result?.id)
  }

  @Test
  fun `a night that stays local still shows its cached result when the server is unreachable`() {
    library.cached = payload("a1")
    library.remoteStatus.value = RemoteStatus.NotConfigured
    val model = viewModel()

    assertEquals("a1", model.state.value.result?.id)
    assertTrue(model.state.value.offlineSaved)
    assertNull(model.state.value.error)
  }

  @Test
  fun `a server-only night without a connection reports that it could not load`() {
    library.remoteStatus.value = RemoteStatus.NotConfigured
    val model = viewModel(localId = null)

    assertEquals("could not load", model.state.value.error)
    assertFalse(model.state.value.offlineSaved)
  }

  @Test
  fun `a network failure keeps the saved result and the connectivity message`() {
    library.cached = payload("a1")
    library.fresh = Result.failure(TaigaNetworkException(IOException("offline")))
    val model = viewModel()

    assertEquals("a1", model.state.value.result?.id)
    assertTrue(model.state.value.offlineSaved)
    assertEquals("network unavailable", model.state.value.error)
  }

  @Test
  fun `a night the server does not have shows its saved result without a connectivity message`() {
    library.cached = payload("a1")
    library.fresh = Result.failure(RecordingNotOnServerException("recording not found"))
    val model = viewModel()

    library.recordings.value =
        listOf(recording(remoteId = null, status = RecordingStatus.LOCAL_ONLY))

    assertEquals("a1", model.state.value.result?.id)
    assertFalse(model.state.value.offlineSaved)
    assertNull(model.state.value.error)
    assertFalse(model.state.value.recording?.onServer ?: true)
    assertEquals(RecordingStatus.LOCAL_ONLY, model.state.value.status)
  }

  @Test
  fun `refreshing asks the library again`() {
    val model = viewModel()
    val before = library.calls.count { it.startsWith("refresh:") }

    model.refresh()

    assertEquals(before + 1, library.calls.count { it.startsWith("refresh:") })
    assertEquals(2, work.calls.count { it == "refreshRecordings" })
  }

  @Test
  fun `a retry of the analysis goes to the work and refreshes on success`() {
    val model = viewModel()
    val before = library.calls.count { it.startsWith("refresh:") }

    model.retryAnalysis()

    assertTrue("retry:local:a1" in work.calls)
    assertEquals(before + 1, library.calls.count { it.startsWith("refresh:") })
  }

  @Test
  fun `a failed retry becomes the action error`() {
    work.retry = Result.failure(IllegalStateException("the server said no"))
    val model = viewModel()

    model.retryAnalysis()

    assertEquals("the server said no", model.state.value.actionError)
  }

  @Test
  fun `selecting an analysis stores it and reads it back`() {
    val model = viewModel()

    model.selectAnalysis("a2")

    assertTrue("select:local:a2" in library.calls)
    assertEquals("refresh:local:r1:a2", library.calls.last { it.startsWith("refresh:") })
  }

  @Test
  fun `requesting an analysis marks the sheet busy and refreshes when the server accepts`() {
    val model = viewModel()
    val before = library.calls.count { it.startsWith("refresh:") }

    model.requestAnalysis("stager-2")

    assertTrue("manual:local:stager-2" in library.calls)
    assertFalse(model.state.value.analysisBusy)
    assertEquals(before + 1, library.calls.count { it.startsWith("refresh:") })
  }

  @Test
  fun `a refused analysis request leaves its message in the sheet`() {
    library.manual = Result.failure(IllegalStateException("no compatible algorithm"))
    val model = viewModel()

    model.requestAnalysis("stager-2")

    assertEquals("no compatible algorithm", model.state.value.algorithmsError)
  }

  @Test
  fun `opening the analysis sheet loads the algorithms`() {
    library.algorithms = Result.success(listOf(AlgorithmDto(id = "stager", version = "2")))
    val model = viewModel()

    model.loadAlgorithms()

    assertTrue("algorithms:local" in library.calls)
    assertEquals(listOf("stager"), model.state.value.algorithms.map { it.id })
    assertFalse(model.state.value.algorithmsLoading)
  }

  @Test
  fun `a rejected token turns the stored failure into a login request`() {
    library.fresh = Result.failure(TaigaUnauthorizedException(401, "expired"))
    val model = viewModel()

    model.refresh()

    assertTrue(model.state.value.loginExpired)
    assertEquals(UploadFailure.LOGIN, model.state.value.failure)
    assertEquals("please log in", model.state.value.errorDetail)
  }

  @Test
  fun `uploading while logged out asks for the login screen`() {
    library.remoteStatus.value = RemoteStatus.LoginNeeded("a@b.c", null)
    val model = viewModel()

    model.upload()

    assertTrue(model.state.value.loginRequested)
    assertFalse("upload:local" in work.calls)

    model.loginShown()

    assertFalse(model.state.value.loginRequested)
  }

  @Test
  fun `uploading while connected queues the night and refreshes`() {
    val model = viewModel()

    model.upload()

    assertTrue("upload:local" in work.calls)
  }

  @Test
  fun `an export of a night with raw data only zips it`() {
    val model = viewModel()

    model.export()

    assertEquals(listOf("share:local"), work.calls.filter { it.startsWith("share:") || it.startsWith("restore:") })
    assertFalse(model.state.value.exportBusy)
    assertNull(model.state.value.exportPhase)
  }

  @Test
  fun `an export of a server-only night restores the raw files first`() {
    library.recordings.value = listOf(recording(hasRaw = false))
    val model = viewModel()

    model.export()

    assertEquals(
        listOf("restore:local:r1", "share:local"),
        work.calls.filter { it.startsWith("share:") || it.startsWith("restore:") },
    )
  }

  @Test
  fun `a failed export closes the details sheet and reports why`() {
    work.share = Result.failure(IllegalStateException("the disk is full"))
    val model = viewModel()
    model.openDetails()

    model.export()

    assertEquals("could not restore: the disk is full", model.state.value.actionError)
    assertFalse(model.state.value.showDetails)
    assertFalse(model.state.value.exportBusy)
  }

  @Test
  fun `a successful export closes the details sheet`() {
    val model = viewModel()
    model.openDetails()

    model.export()

    assertFalse(model.state.value.showDetails)
  }

  @Test
  fun `downloading the raw data restores it and keeps the sheet open`() {
    library.recordings.value = listOf(recording(hasRaw = false))
    val model = viewModel()
    model.openDetails()

    model.downloadRaw()

    assertTrue("restore:local:r1" in work.calls)
    assertTrue(model.state.value.showDetails)
  }

  @Test
  fun `a finished download shows the size of the files it restored`() {
    library.recordings.value = listOf(recording(hasRaw = false))
    work.rawSize = 0
    val model = viewModel()
    model.openDetails()
    assertEquals(0L, model.state.value.rawSizeBytes)

    work.restores = {
      work.rawSize = 229_000
      library.recordings.value = listOf(recording(hasRaw = true))
    }
    model.downloadRaw()

    assertEquals(229_000L, model.state.value.rawSizeBytes)
    assertTrue(model.state.value.showDetails)
  }

  @Test
  fun `deleting the raw data closes the sheet when the night was never uploaded`() {
    work.deleteRaw = RawActionResult.NotUploaded
    val model = viewModel()
    model.openDetails()

    model.deleteRaw()

    assertEquals("not uploaded yet", model.state.value.actionError)
    assertFalse(model.state.value.showDetails)
  }

  @Test
  fun `deleting the raw data refreshes on success`() {
    val model = viewModel()
    val before = library.calls.count { it.startsWith("refresh:") }

    model.deleteRaw()

    assertEquals(before + 1, library.calls.count { it.startsWith("refresh:") })
  }

  @Test
  fun `deleting the night removes both copies and closes the screen`() {
    val model = viewModel()

    model.delete()

    assertTrue("delete:local:r1" in work.calls)
    assertTrue(model.state.value.closeRequested)
  }

  @Test
  fun `the details sheet reads the size of the raw files when it opens`() {
    val model = viewModel()

    model.openDetails()

    assertTrue(model.state.value.showDetails)
    assertEquals(2048L, model.state.value.rawSizeBytes)
    assertTrue("rawSize:local" in work.calls)
  }

  @Test
  fun `a night without a local copy has no raw size`() {
    val model = viewModel(localId = null)

    model.openDetails()

    assertEquals(0L, model.state.value.rawSizeBytes)
  }
}
