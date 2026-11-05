package com.rbmr.taigasleep.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.library.LibrarySource
import com.rbmr.taigasleep.library.RecordingLibrary
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.sync.PendingDelete
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.SyncService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything History draws: the nights that are not waiting for the server, and the last failure. */
data class HistoryUiState(
    val items: List<RecordingView> = emptyList(),
    /**
     * Why the last background step failed. History has nothing that shows a message, so this only
     * keeps a failed refresh from taking the list down; a screen that reports it can read it here.
     */
    val error: String? = null,
)

/** The background steps of the History screen, split out so the view model needs no Android types. */
interface HistoryWork {
  /** Once, when the screen opens: the delete queue, the upload queue and the retention policy. */
  suspend fun onOpen()

  /** Every time the screen becomes visible: re-read the recordings from disk. */
  suspend fun onResume()

  /** Delete one night everywhere it exists; the row leaves the list as soon as its local copy goes. */
  fun delete(view: RecordingView)
}

/**
 * The state holder of the History screen: it follows the library, filters the nights whose server
 * copy is already being deleted, and runs the work the screen does when it opens or resumes.
 *
 * It owns no state that the library or the sync stores also hold. The library still writes the
 * nights and the connection state; this only mirrors them for drawing.
 */
class HistoryViewModel(
    private val library: LibrarySource,
    private val pendingDeletes: StateFlow<Map<String, PendingDelete>>,
    private val work: HistoryWork,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
  private val failure = MutableStateFlow<String?>(null)
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)

  val state: StateFlow<HistoryUiState> =
      combine(library.recordings, pendingDeletes, failure) { nights, pending, error ->
            HistoryUiState(
                items = nights.filterNot { it.localId == null && it.remoteId in pending },
                error = error,
            )
          }
          .stateIn(scope, SharingStarted.Eagerly, HistoryUiState())

  init {
    // A fresh account or a reconnect can change the cached server list.
    scope.launch { library.remoteStatus.collect { library.reload() } }
  }

  /** The screen was shown: reconcile the queues and the retention policy, then read the nights. */
  fun onOpen() {
    failure.value = null
    launch { work.onOpen() }
  }

  /** The screen became visible again: the nights may have changed on disk. */
  fun refresh() {
    launch { work.onResume() }
  }

  fun delete(view: RecordingView) {
    work.delete(view)
  }

  override fun onCleared() {
    scope.cancel()
  }

  /** A background step threw; the list keeps the rows it already has. */
  private fun recordFailure(cause: Throwable) {
    failure.value = cause.message ?: cause::class.java.simpleName
  }

  private fun launch(block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
      } catch (e: Exception) {
        recordFailure(e)
      }
    }
  }

  /**
   * Builds the view model from the singletons the app already has. The work the screen does lives
   * here, in the one part of the screen that may call the stores.
   */
  class Factory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      val library = RecordingLibrary.of(context)
      val sync = SyncService.of(context)
      val work =
          object : HistoryWork {
            override suspend fun onOpen() {
              RecordingRepository.refreshRecordings(context)
              sync.reconcileAll()
              sync.enforceRawRetention()
              library.reload()
            }

            override suspend fun onResume() {
              RecordingRepository.refreshRecordings(context)
              library.reload()
            }

            override fun delete(view: RecordingView) {
              sync.delete(view.localId, view.remoteId)
            }
          }
      return HistoryViewModel(
          library = library,
          pendingDeletes = PendingDeleteStore.of(context).pending,
          work = work,
      ) as T
    }
  }
}
