package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.RemoteEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingTarget : LiveSyncTarget {
  val calls = CopyOnWriteArrayList<String>()
  var onReconcile: suspend () -> Unit = {}
  var failRefresh = false

  /** Ticks on every recorded call, so a test can wait for a call instead of polling for one. */
  private val callsMade = MutableStateFlow(0)

  private fun record(call: String) {
    calls += call
    callsMade.update { it + 1 }
  }

  /** Suspends until the calls recorded so far satisfy [condition]. No time passes while it waits. */
  suspend fun awaitCalls(condition: (List<String>) -> Boolean) {
    callsMade.first { condition(calls) }
  }

  override suspend fun reconcile() {
    record("reconcile")
    onReconcile()
  }

  override suspend fun refreshRecording(recordingId: String) {
    record("refresh:$recordingId")
    if (failRefresh) throw IllegalStateException("boom")
  }

  override suspend fun recordingDeleted(recordingId: String) {
    record("deleted:$recordingId")
  }
}

/** Which events and requests cost a refresh, and how many. No clock and no network. */
class SyncQueueTest {
  private val target = RecordingTarget()
  private var now = 1_000_000L
  private val slept = mutableListOf<Long>()

  private fun queue() =
      SyncQueue(target, intervalMs = 2_000, clock = { now }, sleep = { slept += it; now += it })

  @Test
  fun `events for different recordings are refreshed one after the other`() = runBlocking {
    val queue = queue()
    val worker = launch { queue.run() }

    queue.requestRefresh("a")
    queue.requestRefresh("b")
    yield()

    assertEquals(listOf("refresh:a", "refresh:b"), target.calls)
    worker.cancelAndJoin()
  }

  @Test
  fun `several events for one recording that are waiting are one refresh`() = runBlocking {
    val queue = queue()
    val worker = launch { queue.run() }

    repeat(5) { queue.requestRefresh("a") }
    yield()

    assertEquals(listOf("refresh:a"), target.calls)
    worker.cancelAndJoin()
  }

  @Test
  fun `a recording is refreshed at most once per interval and the last event is still served`() =
      runBlocking {
        val queue = queue()
        val worker = launch { queue.run() }

        queue.requestRefresh("a")
        yield()
        now += 500
        // An upload changes the recording again and again; these are all one trailing refresh.
        repeat(10) { queue.requestRefresh("a") }
        yield()

        assertEquals(listOf("refresh:a", "refresh:a"), target.calls)
        // It waited out the rest of the interval before reading again.
        assertEquals(listOf(1_500L), slept)
        worker.cancelAndJoin()
      }

  @Test
  fun `a reconcile covers the single refreshes that were waiting`() = runBlocking {
    val queue = queue()
    val worker = launch { queue.run() }

    queue.requestRefresh("a")
    queue.requestRefresh("b")
    queue.requestReconcile()
    yield()

    assertEquals(listOf("reconcile"), target.calls)
    worker.cancelAndJoin()
  }

  @Test
  fun `an event that arrives during a reconcile is served after it`() = runBlocking {
    val queue = queue()
    val release = CompletableDeferred<Unit>()
    target.onReconcile = { release.await() }
    val worker = launch { queue.run() }

    queue.requestReconcile()
    yield()
    // The reconcile has started and may already have read this recording.
    queue.requestRefresh("a")
    yield()
    assertEquals(listOf("reconcile"), target.calls)

    release.complete(Unit)
    yield()
    yield()

    assertEquals(listOf("reconcile", "refresh:a"), target.calls)
    worker.cancelAndJoin()
  }

  @Test
  fun `a refresh that fails does not stop the ones after it`() = runBlocking {
    val queue = queue()
    target.failRefresh = true
    val worker = launch { queue.run() }

    queue.requestRefresh("a")
    queue.requestRefresh("b")
    yield()

    assertEquals(listOf("refresh:a", "refresh:b"), target.calls)
    worker.cancelAndJoin()
  }

  @Test
  fun `a deleted recording is dropped once, however many events announce it`() = runBlocking {
    val queue = queue()
    val worker = launch { queue.run() }

    repeat(3) { queue.requestDeleted("a") }
    yield()

    assertEquals(listOf("deleted:a"), target.calls)
    worker.cancelAndJoin()
  }
}

/**
 * The gating and lifecycle of the live sync: when a stream runs, what it triggers, when it stops.
 *
 * Everything runs on the test dispatcher, so the grace period and the refresh interval are virtual
 * time: a test advances it by an amount and nothing waits on the wall clock. Each check waits on a
 * signal, either one the fake stream counts or one the target's calls produce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSyncTest {
  private val target = RecordingTarget()
  private val gate = MutableStateFlow<String?>(null)
  private val shared = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 64)
  private val opened = MutableStateFlow(0)
  private val closed = MutableStateFlow(0)

  /** A stream that is "connected" for as long as it is collected. */
  private var connectOnOpen = true
  private val events: Flow<RemoteEvent> = flow {
    opened.update { it + 1 }
    try {
      if (connectOnOpen) emit(RemoteEvent.Connected)
      emitAll(shared)
    } finally {
      closed.update { it + 1 }
    }
  }

  private var job: Job? = null

  /** Starts the live sync on the test dispatcher, with the clock reading the virtual time. */
  private fun TestScope.start(graceMs: Long = 150, intervalMs: Long = 20) {
    job =
        backgroundScope.launch {
          LiveSync(
                  gate = gate,
                  events = events,
                  target = target,
                  connectGraceMs = graceMs,
                  refreshIntervalMs = intervalMs,
                  clock = { testScheduler.currentTime },
              )
              .run()
        }
  }

  /**
   * Sends an event the way the server would, once the stream listens. The fake stream announces
   * `Connected` before it subscribes, and a shared flow without replay drops what nobody hears yet.
   */
  private suspend fun emit(event: RemoteEvent) {
    shared.subscriptionCount.first { it > 0 }
    shared.emit(event)
  }

  @Test
  fun `no stream runs while no account may stream`() = runTest {
    start()
    advanceTimeBy(1_000)
    runCurrent()

    assertEquals(0, opened.value)
    assertTrue(target.calls.isEmpty())
  }

  @Test
  fun `a stream opens for an account and its connect is one reconcile`() = runTest {
    start()
    gate.value = "server|me"

    opened.first { it >= 1 }
    target.awaitCalls { "reconcile" in it }
    // The grace period for a stream that does not connect must not add a second one.
    advanceTimeBy(1_000)
    runCurrent()

    assertEquals(listOf("reconcile"), target.calls.toList())
  }

  @Test
  fun `an analysis event refreshes its recording and a recording event its own id`() = runTest {
    start()
    gate.value = "server|me"
    target.awaitCalls { "reconcile" in it }

    emit(RemoteEvent.AnalysisChanged("a1", "r1", "succeeded"))
    emit(RemoteEvent.RecordingChanged("r2", "updated"))

    target.awaitCalls { it.containsAll(listOf("refresh:r1", "refresh:r2")) }
  }

  @Test
  fun `a deleted recording event drops the recording without reading it`() = runTest {
    start()
    gate.value = "server|me"
    target.awaitCalls { "reconcile" in it }

    emit(RemoteEvent.RecordingChanged("r3", "deleted"))

    target.awaitCalls { "deleted:r3" in it }
    assertTrue("refresh:r3" !in target.calls)
  }

  @Test
  fun `a stream that does not connect still refreshes once, and again when it does`() = runTest {
    connectOnOpen = false
    start(graceMs = 100)
    gate.value = "server|me"

    target.awaitCalls { it == listOf("reconcile") }
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(listOf("reconcile"), target.calls.toList())

    emit(RemoteEvent.Connected)

    target.awaitCalls { it.count { call -> call == "reconcile" } == 2 }
  }

  @Test
  fun `every reconnect is a reconcile`() = runTest {
    start()
    gate.value = "server|me"
    target.awaitCalls { it.count { call -> call == "reconcile" } == 1 }

    // The transport reconnected by itself: the stream announces it again.
    emit(RemoteEvent.Connected)

    target.awaitCalls { it.count { call -> call == "reconcile" } == 2 }
  }

  @Test
  fun `the stream closes when the account may no longer stream`() = runTest {
    start()
    gate.value = "server|me"
    opened.first { it >= 1 }

    gate.value = null

    closed.first { it >= 1 }
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(1, opened.value)
  }

  @Test
  fun `the stream restarts for another account`() = runTest {
    start()
    gate.value = "server|me"
    opened.first { it >= 1 }

    gate.value = "server|you"

    opened.first { it >= 2 }
    assertEquals(1, closed.value)
    target.awaitCalls { it.count { call -> call == "reconcile" } == 2 }
  }

  @Test
  fun `cancelling the foreground closes the stream and nothing is left running`() = runTest {
    start()
    gate.value = "server|me"
    opened.first { it >= 1 }

    job?.cancelAndJoin()

    assertEquals(1, closed.value)
    val calls = target.calls.size
    // Nothing listens any more, so the event is sent without waiting for a listener.
    shared.emit(RemoteEvent.RecordingChanged("late", "updated"))
    advanceUntilIdle()
    assertEquals(calls, target.calls.size)
  }

  @Test
  fun `a stream that breaks does not take the app down and starts again with the gate`() = runTest {
    val attempts = MutableStateFlow(0)
    val breaking: Flow<RemoteEvent> = flow {
      attempts.update { it + 1 }
      throw IllegalStateException("boom")
    }
    job =
        backgroundScope.launch {
          LiveSync(
                  gate = gate,
                  events = breaking,
                  target = target,
                  connectGraceMs = 50,
                  refreshIntervalMs = 10,
                  clock = { testScheduler.currentTime },
              )
              .run()
        }

    gate.value = "server|me"
    attempts.first { it >= 1 }
    advanceUntilIdle()

    assertTrue(job?.isActive == true)
    gate.value = "server|you"
    attempts.first { it >= 2 }
  }
}
