package com.rbmr.taigasleep.ui.history

import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.LibrarySource
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.sync.PendingDelete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeLibrary(nights: List<RecordingView>) : LibrarySource {
  override val recordings: MutableStateFlow<List<RecordingView>> = MutableStateFlow(nights)
  override val remoteStatus: MutableStateFlow<RemoteStatus> =
      MutableStateFlow<RemoteStatus>(RemoteStatus.NotConfigured)
  var reloads = 0

  override fun reload() {
    reloads++
  }
}

private class FakeWork : HistoryWork {
  val calls = mutableListOf<String>()
  var openFailure: Exception? = null

  override suspend fun onOpen() {
    calls += "open"
    openFailure?.let { throw it }
  }

  override suspend fun onResume() {
    calls += "resume"
  }

  override fun delete(view: RecordingView) {
    calls += "delete:${view.key}"
  }
}

private fun night(
    key: String,
    localId: String?,
    remoteId: String?,
    status: RecordingStatus = RecordingStatus.LOCAL_ONLY,
) = RecordingView(
    key = key,
    localId = localId,
    remoteId = remoteId,
    startUtcMs = 1_790_000_000_000,
    endUtcMs = 1_790_003_600_000,
    durationMs = 3_600_000,
    device = "Polar H10",
    status = status,
    progress = 0f,
    failure = null,
    errorDetail = null,
    warning = null,
    analyses = emptyList(),
    selectedAnalysisId = null,
    backup = BackupState(),
    local = null,
    remote = null,
    resultDownloaded = false,
)

class HistoryViewModelTest {
  private val library = FakeLibrary(listOf(night("local", "local", null), night("remote", null, "r1")))
  private val pending = MutableStateFlow<Map<String, PendingDelete>>(emptyMap())
  private val work = FakeWork()

  private fun viewModel(): HistoryViewModel =
      HistoryViewModel(library, pending, work, Dispatchers.Unconfined)

  @Test
  fun `the state shows every night the library holds`() {
    val state = viewModel().state

    assertEquals(listOf("local", "remote"), state.value.items.map { it.key })
    assertNull(state.value.error)
  }

  @Test
  fun `a newer library list replaces the rows`() {
    val model = viewModel()

    library.recordings.value = listOf(night("local", "local", "r1", RecordingStatus.READY))

    assertEquals(listOf("local"), model.state.value.items.map { it.key })
    assertEquals(RecordingStatus.READY, model.state.value.items.single().status)
  }

  @Test
  fun `opening the screen reconciles once`() {
    val model = viewModel()

    model.onOpen()

    assertEquals(listOf("open"), work.calls)
  }

  @Test
  fun `refreshing re-reads the recordings without reconciling`() {
    val model = viewModel()

    model.refresh()

    assertEquals(listOf("resume"), work.calls)
  }

  @Test
  fun `deleting a night is handed to the work once`() {
    val model = viewModel()

    model.delete(model.state.value.items.single { it.key == "local" })

    assertEquals(listOf("delete:local"), work.calls)
  }

  @Test
  fun `a night whose server copy is being deleted leaves the list although the server still lists it`() {
    val model = viewModel()

    pending.value = mapOf("r1" to PendingDelete("r1", 1))

    assertEquals(listOf("local"), model.state.value.items.map { it.key })
  }

  @Test
  fun `a local night stays while it is still on the phone`() {
    val model = viewModel()

    // Its local copy is what History lists; only a remote-only row waits for the server delete.
    pending.value = mapOf("local" to PendingDelete("local", 1))

    assertEquals(listOf("local", "remote"), model.state.value.items.map { it.key })
  }

  @Test
  fun `a failed open becomes the error state and keeps the rows`() {
    work.openFailure = IllegalStateException("the queue is unreachable")
    val model = viewModel()

    model.onOpen()

    assertEquals("the queue is unreachable", model.state.value.error)
    assertEquals(listOf("local", "remote"), model.state.value.items.map { it.key })
  }

  @Test
  fun `a later open clears the error`() {
    work.openFailure = IllegalStateException("the queue is unreachable")
    val model = viewModel()
    model.onOpen()

    work.openFailure = null
    model.onOpen()

    assertNull(model.state.value.error)
  }

  @Test
  fun `the connection state asks for a reload when it changes`() {
    val model = viewModel()
    val before = library.reloads

    library.remoteStatus.value = RemoteStatus.LoginNeeded(email = "user@test.com", checkedAtMs = null)

    assertEquals(before + 1, library.reloads)
    // The first emission is from the constructor, so the reload is asked for on every change.
    assertEquals(1, before)
    assertEquals(2, library.reloads)
  }
}
