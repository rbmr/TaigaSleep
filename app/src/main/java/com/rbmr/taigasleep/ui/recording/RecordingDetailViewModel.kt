package com.rbmr.taigasleep.ui.recording

import androidx.lifecycle.ViewModel
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.library.RecordingSource
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.recording.ExportSource
import com.rbmr.taigasleep.recording.exportSource
import com.rbmr.taigasleep.sync.RawActionResult
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
) : ViewModel() {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val _state =
      MutableStateFlow(
          RecordingDetailUiState(
              connected = library.remoteStatus.value is RemoteStatus.Connected,
              loginError = texts.loginError,
          )
      )
  val state: StateFlow<RecordingDetailUiState> = _state.asStateFlow()

  private var loadJob: Job? = null
  private var preferred: String? = null
  private var lastStatus: RemoteStatus? = null
  private var lastSelected: String? = null
  private var lastDownloaded = false
  private var observedRecording = false

  init {
    scope.launch {
      library.recordings.collect { list ->
        val recording = recordingIn(list)
        _state.update { it.copy(recording = recording, canExport = canExportOf(recording)) }
        if (recording != null) {
          followSelectedResult(recording)
          // The raw files appear and go behind the sheet, so its size follows the recording.
          if (_state.value.showDetails) readRawSize()
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

  /** The screen was shown: read the recording and its result now. */
  fun refresh() = load()

  fun upload() {
    val id = _state.value.local?.id ?: localId ?: return
    if (!_state.value.connected) {
      _state.update { it.copy(loginRequested = true) }
      return
    }
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

  /** Prepare the export, restoring the raw files from the server when it has the only copy. */
  fun export() {
    val id = _state.value.local?.id ?: localId ?: return
    if (_state.value.exportBusy) return
    _state.update {
      it.copy(actionError = null, exportBusy = true, exportPhase = null, exportProgress = null)
    }
    scope.launch {
      val source = exportSource(_state.value.local?.hasRaw == true, _state.value.remoteId != null)
      val result =
          when (source) {
            ExportSource.LOCAL -> share(id)
            ExportSource.UNAVAILABLE ->
                Result.failure(IllegalStateException("no copy of this night is available"))
            ExportSource.RESTORE -> restoreThenShare(id, _state.value.remoteId)
          }
      finish(result, closeOnSuccess = true)
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
      finish(work.restoreRaw(id, server) { progress(it) }, closeOnSuccess = false)
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
          _state.update { it.copy(showDetails = false, actionError = texts.rawNotUploaded) }
        }
        is RawActionResult.Failed -> {
          _state.update { it.copy(showDetails = false, actionError = texts.rawDeleteFailed) }
        }
      }
    }
  }

  /** Delete the recording everywhere it exists, then leave the screen. */
  fun delete() {
    _state.update { it.copy(showDetails = false) }
    work.delete(_state.value.local?.id ?: localId, _state.value.remoteId ?: remoteId)
    _state.update { it.copy(closeRequested = true) }
  }

  fun openDetails() {
    _state.update { it.copy(showDetails = true) }
    readRawSize()
  }

  fun closeDetails() {
    _state.update { it.copy(showDetails = false) }
  }

  /** The login screen was shown for a request this screen asked for. */
  fun loginShown() {
    _state.update { it.copy(loginRequested = false) }
  }

  override fun onCleared() {
    scope.cancel()
  }

  /** Re-read the recording and its result: the library, then the server when the account allows it. */
  private fun load() {
    loadJob?.cancel()
    loadJob =
        scope.launch {
          work.refreshRecordings()
          library.reload()
          val current = library.recording(key) ?: _state.value.recording
          val cached = current?.let { library.cachedResult(key, it.analysisId) }
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
                  it.copy(
                      loginExpired = failure is TaigaUnauthorizedException,
                      offlineSaved = it.result != null,
                      error = failure.message,
                  )
                }
              }
        }
  }

  /** The selected run can change and its payload can arrive while the screen is open. */
  private fun followSelectedResult(recording: RecordingView) {
    val selected = recording.analysisId
    if (selected == lastSelected && recording.resultDownloaded == lastDownloaded) return
    val first = !observedRecording
    observedRecording = true
    lastSelected = selected
    lastDownloaded = recording.resultDownloaded
    if (selected == null) return
    val cached = library.cachedResult(key, selected)
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

  private suspend fun share(id: String): Result<Unit> {
    _state.update { it.copy(exportPhase = ExportPhase.ZIPPING, exportProgress = 0f) }
    val result = work.share(id) { progress(it) }
    if (result.isSuccess) _state.update { it.copy(exportProgress = 1f) }
    return result
  }

  private suspend fun restoreThenShare(id: String, server: String?): Result<Unit> {
    val remote =
        server ?: return Result.failure(IllegalStateException("no server copy is available"))
    _state.update { it.copy(exportPhase = ExportPhase.DOWNLOADING, exportProgress = 0f) }
    val restored = work.restoreRaw(id, remote) { progress(it) }
    if (restored.isFailure) return restored
    return share(id)
  }

  /**
   * An export or a download finished. A failure keeps its message on the list and closes the sheet;
   * a successful export closes it too, while a download leaves it open to show the new size.
   */
  private fun finish(result: Result<Unit>, closeOnSuccess: Boolean) {
    _state.update { it.copy(exportBusy = false, exportPhase = null, exportProgress = null) }
    if (result.isFailure) {
      _state.update { it.copy(showDetails = false, actionError = restoreFailure(result)) }
      return
    }
    if (closeOnSuccess) _state.update { it.copy(showDetails = false) }
    load()
  }

  private fun restoreFailure(result: Result<Unit>): String =
      listOfNotNull(
              texts.rawRestoreFailed,
              result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() },
          )
          .joinToString(": ")

  private fun progress(value: Float) {
    _state.update { it.copy(exportProgress = value) }
  }

  /** Bytes the raw files take on this phone now, which a download or a delete can change. */
  private fun readRawSize() {
    val id = _state.value.local?.id ?: localId
    val size = if (id == null) 0L else work.rawSize(id)
    _state.update { it.copy(rawSizeBytes = size) }
  }

  /** The phone has the raw files, or the server does: either copy can be exported. */
  private fun canExportOf(recording: RecordingView?): Boolean {
    val local = recording?.local ?: return false
    return exportSource(local.hasRaw, recording.remoteId != null) != ExportSource.UNAVAILABLE
  }

  private fun recordingIn(list: List<RecordingView>): RecordingView? =
      list.firstOrNull { it.key == key }
          ?: localId?.let { id -> list.firstOrNull { it.localId == id } }
}
