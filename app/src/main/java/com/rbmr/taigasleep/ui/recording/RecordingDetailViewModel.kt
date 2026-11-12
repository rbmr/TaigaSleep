package com.rbmr.taigasleep.ui.recording

import androidx.lifecycle.ViewModel
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.library.RecordingSource
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.recording.ExportContents
import com.rbmr.taigasleep.recording.ExportProgress
import com.rbmr.taigasleep.recording.ExportResult
import com.rbmr.taigasleep.recording.LARGE_EXPORT_BYTES
import com.rbmr.taigasleep.recording.analysisExportName
import com.rbmr.taigasleep.sync.RawActionResult
import com.rbmr.taigasleep.sync.RecordingNotOnServerException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The server and the account an upload currently goes to, for the confirmation that names them. */
data class UploadTarget(val server: String, val account: String)

/**
 * The screen's state holder. It follows the recording in the library, loads the cached result and the
 * server's copy, and runs the actions of the screen. Nothing here draws.
 */
class RecordingDetailViewModel(
    private val key: String,
    private val localId: String?,
    private val remoteId: String?,
    private val analysisId: String?,
    private val library: RecordingSource,
    private val work: RecordingWork,
    private val texts: RecordingTexts,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Reads the payload this phone holds, away from the main thread. */
    private val resultDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val known = recordingIn(library.recordings.value)
  private val _state =
      MutableStateFlow(
          RecordingDetailUiState(
              recording = known,
              connected = library.remoteStatus.value is RemoteStatus.Connected,
              loginError = texts.loginError,
              exportContents = ExportContents(raw = known?.hasRaw == true),
          )
      )
  val state: StateFlow<RecordingDetailUiState> = _state.asStateFlow()

  private var loadJob: Job? = null
  private var preferred: String? = null
  private var lastStatus: RemoteStatus? = null
  private var lastSelected: String? = null
  private var lastDownloaded = false
  private var lastHasRaw: Boolean? = null
  private var observedRecording = false
  /** The user changed what to export, so the default is no longer applied to a recording that arrives. */
  private var contentsChosen = false

  init {
    // The result this phone already has is read at once, so the night opens with what is saved
    // instead of waiting for the server.
    scope.launch { readCachedResult() }
    scope.launch {
      library.recordings.collect { list ->
        val recording = recordingIn(list)
        _state.update {
          it.copy(
              recording = recording,
              // The default follows the night until the user picks: the parts it has, and no result.
              exportContents =
                  if (contentsChosen || recording == null) it.exportContents
                  else ExportContents(raw = recording.hasRaw),
          )
        }
        if (recording != null) {
          followSelectedResult(recording)
          // The raw files appear and go as the retention policy runs, so their facts follow them.
          if (recording.hasRaw != lastHasRaw) {
            lastHasRaw = recording.hasRaw
            readLocalFacts()
          }
        }
      }
    }
    scope.launch {
      library.remoteStatus.collect { status ->
        val changed = status != lastStatus
        lastStatus = status
        _state.update { it.copy(connected = status is RemoteStatus.Connected) }
        if (changed) load()
      }
    }
  }

  /** The screen was shown: read the recording, its result and what the phone holds now. */
  fun refresh() {
    readLocalFacts()
    load()
  }

  fun upload() {
    val id = _state.value.local?.id ?: localId ?: return
    if (!_state.value.connected) {
      _state.update { it.copy(loginRequested = true) }
      return
    }
    scope.launch {
      // A recording another account already has is uploaded here only after the user agrees, and
      // the confirmation names the server and the account it goes to.
      val target = work.uploadTarget()
      if (target != null && work.uploadedElsewhere(id)) {
        _state.update { it.copy(uploadConfirmation = target) }
        return@launch
      }
      startUpload(id)
    }
  }

  /** The user agreed to upload a recording another account already has. */
  fun confirmUploadElsewhere() {
    _state.update { it.copy(uploadConfirmation = null) }
    val id = _state.value.local?.id ?: localId ?: return
    startUpload(id)
  }

  fun dismissUploadConfirmation() {
    _state.update { it.copy(uploadConfirmation = null) }
  }

  private fun startUpload(id: String) {
    _state.update { it.copy(actionError = null) }
    work.upload(id)
    load()
  }

  fun retryAnalysis() {
    val recording = _state.value.recording ?: return
    val server = recording.remoteId ?: return
    val key = recording.local?.id ?: "remote-$server"
    _state.update { it.copy(actionError = null) }
    scope.launch {
      work
          .retryAnalysis(key, recording.analysisId)
          .onSuccess { load() }
          .onFailure { failure -> _state.update { it.copy(actionError = failure.message) } }
    }
  }

  /** Remember which finished run the user picked, and read it back from the server. */
  fun selectAnalysis(analysis: String) {
    val recording = _state.value.recording ?: return
    library.selectAnalysis(recording, analysis)
    preferred = analysis
    load()
  }

  /** Ask the server for one more run of an algorithm. */
  fun requestAnalysis(algorithmId: String) {
    val recording = _state.value.recording ?: return
    _state.update { it.copy(analysisBusy = true) }
    scope.launch {
      library
          .requestManual(recording, algorithmId)
          .onSuccess { load() }
          .onFailure { failure ->
            _state.update { it.copy(algorithmsError = failure.message) }
          }
      _state.update { it.copy(analysisBusy = false) }
    }
  }

  /** The analysis sheet was opened: ask which algorithms this recording can feed. */
  fun loadAlgorithms() {
    val recording = _state.value.recording ?: return
    _state.update { it.copy(algorithmsLoading = true, algorithmsError = null) }
    scope.launch {
      library
          .compatibleAlgorithms(recording)
          .onSuccess { algorithms -> _state.update { it.copy(algorithms = algorithms) } }
          .onFailure { failure ->
            _state.update { it.copy(algorithmsError = failure.message) }
          }
      _state.update { it.copy(algorithmsLoading = false) }
    }
  }

  /** What the user chose to export. Nothing is prepared until the export is started. */
  fun setExportRaw(raw: Boolean) = chooseContents(_state.value.exportContents.copy(raw = raw))

  fun setExportMetadata(metadata: Boolean) =
      chooseContents(_state.value.exportContents.copy(metadata = metadata))

  fun setExportResults(results: Boolean) =
      chooseContents(_state.value.exportContents.copy(results = results))

  private fun chooseContents(contents: ExportContents) {
    contentsChosen = true
    _state.update { it.copy(exportContents = contents) }
  }

  /**
   * Prepare the chosen contents, restoring the raw files from the server first when they are gone,
   * and offer the archive to the share sheet. A metadata-only or results-only export stays local.
   */
  fun export() {
    val id = _state.value.local?.id ?: localId ?: return
    if (_state.value.exportBusy) return
    val contents = _state.value.exportContents
    _state.update {
      it.copy(
          actionError = null,
          exportBusy = true,
          exportPhase = null,
          exportProgress = null,
          exportDoneBytes = 0,
          exportTotalBytes = 0,
      )
    }
    scope.launch {
      val shared =
          when {
            !_state.value.canExport ->
                Result.failure(IllegalStateException("no copy of this recording is available"))
            _state.value.exportNeedsRestore ->
                restoreThenShare(id, _state.value.remoteId, contents)
            else -> share(id, contents)
          }
      finish(shared)
    }
  }

  /** Restore the raw files of this recording from the server, without exporting anything. */
  fun downloadRaw() {
    val id = _state.value.local?.id ?: localId ?: return
    val server = _state.value.remoteId ?: return
    if (_state.value.exportBusy) return
    _state.update {
      it.copy(actionError = null, exportBusy = true, exportPhase = null, exportProgress = null)
    }
    scope.launch {
      _state.update { it.copy(exportPhase = ExportPhase.DOWNLOADING, exportProgress = 0f) }
      finish(work.restoreRaw(id, server) { progress(it) })
    }
  }

  /** Remove the raw files of this recording from the phone; the metadata and the server copy stay. */
  fun deleteRaw() {
    val id = _state.value.local?.id ?: localId ?: return
    _state.update { it.copy(actionError = null) }
    scope.launch {
      when (val result = work.deleteRaw(id)) {
        RawActionResult.Done,
        RawActionResult.NothingToDelete -> load()
        RawActionResult.NotUploaded,
        RawActionResult.Recording -> {
          _state.update { it.copy(actionError = texts.rawNotUploaded) }
        }
        is RawActionResult.Failed -> {
          _state.update { it.copy(actionError = texts.rawDeleteFailed) }
        }
      }
    }
  }

  /** Delete the recording everywhere it exists, then leave the screen. */
  fun delete() {
    work.delete(_state.value.local?.id ?: localId, _state.value.remoteId ?: remoteId)
    _state.update { it.copy(closeRequested = true) }
  }

  /** The login screen was shown for a request this screen asked for. */
  fun loginShown() {
    _state.update { it.copy(loginRequested = false) }
  }

  override fun onCleared() {
    scope.cancel()
  }

  /** Reads the payload of the shown analysis that is already on this phone, without any request. */
  private suspend fun readCachedResult() {
    val selected = _state.value.selectedAnalysisId ?: analysisId
    val cached = withContext(resultDispatcher) { library.cachedResult(key, selected) }
    _state.update { it.copy(result = it.result ?: cached, loading = false) }
  }

  /** Re-read the recording and its result: the library, then the server when the account allows it. */
  private fun load() {
    loadJob?.cancel()
    loadJob =
        scope.launch {
          work.refreshRecordings()
          val current = library.recording(key) ?: _state.value.recording
          val cached =
              current?.let {
                withContext(resultDispatcher) { library.cachedResult(key, it.analysisId) }
              }
          _state.update { it.copy(result = cached ?: it.result, loading = false) }
          val server = current?.remoteId ?: _state.value.remoteId ?: remoteId
          if (server == null) {
            _state.update { it.copy(offlineSaved = false, error = null) }
            return@launch
          }
          if (!_state.value.connected) {
            _state.update {
              it.copy(
                  offlineSaved = it.result != null,
                  error = if (current?.local != null) null else texts.loadFailed,
              )
            }
            return@launch
          }
          val wanted = preferred ?: _state.value.selectedAnalysisId ?: analysisId
          preferred = null
          library
              .refreshRecording(key, server, wanted)
              .onSuccess { fresh ->
                _state.update {
                  val result = if (fresh != null && (fresh.isSucceeded || it.result == null)) {
                    fresh
                  } else {
                    it.result
                  }
                  it.copy(result = result, loginExpired = false, offlineSaved = false, error = null)
                }
              }
              .onFailure { failure ->
                _state.update {
                  if (failure is RecordingNotOnServerException) {
                    // The server does not have this night; that is no connectivity problem. The
                    // saved result stays and the night's sync state now says it is phone-only.
                    it.copy(loginExpired = false, offlineSaved = false, error = null)
                  } else {
                    it.copy(
                        loginExpired = failure is TaigaUnauthorizedException,
                        offlineSaved = it.result != null,
                        error = failure.message,
                    )
                  }
                }
              }
        }
  }

  /** The selected run can change and its payload can arrive while the screen is open. */
  private suspend fun followSelectedResult(recording: RecordingView) {
    val selected = recording.analysisId
    if (selected == lastSelected && recording.resultDownloaded == lastDownloaded) return
    val first = !observedRecording
    observedRecording = true
    lastSelected = selected
    lastDownloaded = recording.resultDownloaded
    if (selected == null) return
    val cached = withContext(resultDispatcher) { library.cachedResult(key, selected) }
    _state.update {
      when {
        cached != null -> it.copy(result = cached)
        it.result?.id != selected -> it.copy(result = null)
        else -> it
      }
    }
    // A run this phone does not have yet is read from the server, as a changed selection always
    // was. The first recording is left to the load that opened the screen.
    if (!first && cached == null && _state.value.connected) load()
  }

  /**
   * Writes the chosen contents of [id] and starts the share sheet. Analysis results come from the
   * cache the screen already reads, so a result that is not on the phone is simply not exported:
   * nothing is downloaded or requested.
   */
  private suspend fun share(id: String, contents: ExportContents): Result<Unit> {
    _state.update { it.copy(exportPhase = ExportPhase.ZIPPING, exportProgress = null) }
    val results = if (contents.results) exportResults() else emptyList()
    return work.share(id, contents, results) { progress(it) }
  }

  private suspend fun restoreThenShare(
      id: String,
      server: String?,
      contents: ExportContents,
  ): Result<Unit> {
    val remote =
        server ?: return Result.failure(IllegalStateException("no server copy is available"))
    _state.update {
      it.copy(exportPhase = ExportPhase.DOWNLOADING, exportProgress = 0f, exportDoneBytes = 0)
    }
    val restored = work.restoreRaw(id, remote) { progress(it) }
    restored.exceptionOrNull()?.let { return Result.failure(it) }
    return share(id, contents)
  }

  /** Every result of this night that the phone already holds, as the JSON the export writes. */
  private suspend fun exportResults(): List<ExportResult> {
    val payloads =
        _state.value.analyses
            .filter { it.resultDownloaded }
            .mapNotNull { run ->
              withContext(resultDispatcher) { library.cachedResult(key, run.id) }
            }
    val shown = _state.value.result
    val all =
        if (shown != null && payloads.none { it.id == shown.id }) payloads + shown else payloads
    return all.map { ExportResult(analysisExportName(it.id), exportJson.encodeToString(it)) }
  }

  /** An export or a download finished. A failure keeps its message on the screen. */
  private fun finish(result: Result<*>) {
    _state.update { it.copy(exportBusy = false, exportPhase = null, exportProgress = null) }
    if (result.isFailure) {
      _state.update { it.copy(actionError = restoreFailure(result)) }
      return
    }
    load()
  }

  private fun restoreFailure(result: Result<*>): String {
    val detail = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
    return if (detail == null) texts.rawRestoreFailed
    else texts.errorDetail.format(texts.rawRestoreFailed, detail)
  }

  /** Progress of a download, whose size is known before it starts. */
  private fun progress(value: Float) {
    _state.update { it.copy(exportProgress = value) }
  }

  /**
   * Progress of writing the archive. A short export finishes before a percentage would mean
   * anything, so it only reports that work is happening; a large one also reports its bytes.
   */
  private fun progress(value: ExportProgress) {
    _state.update {
      it.copy(
          exportProgress = value.fraction.takeIf { value.totalBytes >= LARGE_EXPORT_BYTES },
          exportDoneBytes = value.doneBytes,
          exportTotalBytes = value.totalBytes,
      )
    }
  }

  /**
   * What the phone holds of this recording now: the size of its raw files and the streams in them.
   * A download, a delete and the retention policy all change them.
   */
  private fun readLocalFacts() {
    val id = _state.value.local?.id ?: localId
    val size = if (id == null) 0L else work.rawSize(id)
    val streams = if (id == null) emptyList() else work.rawStreams(id)
    _state.update { it.copy(rawSizeBytes = size, rawStreams = streams) }
  }

  private fun recordingIn(list: List<RecordingView>): RecordingView? =
      list.firstOrNull { it.key == key }
          ?: localId?.let { id -> list.firstOrNull { it.localId == id } }
}

/** Writes the cached payload of an analysis as JSON, with every field the app uses. */
private val exportJson = Json {
  encodeDefaults = true
  explicitNulls = false
}
