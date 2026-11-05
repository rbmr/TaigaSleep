package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.RemoteEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingTarget : LiveSyncTarget {
  val calls = CopyOnWriteArrayList<String>()
  var onReconcile: suspend () -> Unit = {}
  var failRefresh = false

  override suspend fun reconcile() {
    calls += "reconcile"
    onReconcile()
  }

  override suspend fun refreshRecording(recordingId: String) {
    calls += "refresh:$recordingId"
    if (failRefresh) throw IllegalStateException("boom")
  }

  override suspend fun recordingDeleted(recordingId: String) {
    calls += "deleted:$recordingId"
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

/** The gating and lifecycle of the live sync: when a stream runs, what it triggers, when it stops. */
class LiveSyncTest {
  private val scope = CoroutineScope(Dispatchers.Default)
  private val target = RecordingTarget()
  private val gate = MutableStateFlow<String?>(null)
  private val shared = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 64)
  private val opened = AtomicInteger()
  private val closed = AtomicInteger()

  /** A stream that is "connected" for as long as it is collected. */
  private var connectOnOpen = true
  private val events: Flow<RemoteEvent> = flow {
    opened.incrementAndGet()
    try {
      if (connectOnOpen) emit(RemoteEvent.Connected)
      emitAll(shared)
    } finally {
      closed.incrementAndGet()
    }
  }

  private var job: Job? = null

  @After
  fun tearDown() {
    runBlocking { job?.cancelAndJoin() }
  }

  private fun start(graceMs: Long = 150, intervalMs: Long = 20) {
    job =
        scope.launch {
          LiveSync(
                  gate = gate,
                  events = events,
                  target = target,
                  connectGraceMs = graceMs,
                  refreshIntervalMs = intervalMs,
              )
              .run()
        }
  }

  private fun await(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
      if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
      Thread.sleep(10)
    }
  }

  private fun emit(event: RemoteEvent) = runBlocking { shared.emit(event) }

  @Test
  fun `no stream runs while no account may stream`() {
    start()

    Thread.sleep(300)

    assertEquals(0, opened.get())
    assertTrue(target.calls.isEmpty())
  }

  @Test
  fun `a stream opens for an account and its connect is one reconcile`() {
    start()
    gate.value = "server|me"

    await("the stream to open") { opened.get() == 1 }
    await("the reconcile") { target.calls.contains("reconcile") }
    // The grace period for a stream that does not connect must not add a second one.
    Thread.sleep(400)

    assertEquals(listOf("reconcile"), target.calls.toList())
  }

  @Test
  fun `an analysis event refreshes its recording and a recording event its own id`() {
    start()
    gate.value = "server|me"
    await("the reconcile") { target.calls.contains("reconcile") }

    emit(RemoteEvent.AnalysisChanged("a1", "r1", "succeeded"))
    emit(RemoteEvent.RecordingChanged("r2", "updated"))

    await("both refreshes") { target.calls.containsAll(listOf("refresh:r1", "refresh:r2")) }
  }

  @Test
  fun `a deleted recording event drops the recording without reading it`() {
    start()
    gate.value = "server|me"
    await("the reconcile") { target.calls.contains("reconcile") }

    emit(RemoteEvent.RecordingChanged("r3", "deleted"))

    await("the drop") { target.calls.contains("deleted:r3") }
    assertTrue("refresh:r3" !in target.calls)
  }

  @Test
  fun `a stream that does not connect still refreshes once, and again when it does`() {
    connectOnOpen = false
    start(graceMs = 100)
    gate.value = "server|me"

    await("the refresh after the grace period") { target.calls == listOf("reconcile") }
    Thread.sleep(250)
    assertEquals(listOf("reconcile"), target.calls.toList())

    emit(RemoteEvent.Connected)

    await("the reconcile on connecting") { target.calls.count { it == "reconcile" } == 2 }
  }

  @Test
  fun `every reconnect is a reconcile`() {
    start()
    gate.value = "server|me"
    await("the first reconcile") { target.calls.count { it == "reconcile" } == 1 }

    // The transport reconnected by itself: the stream announces it again.
    emit(RemoteEvent.Connected)

    await("the second reconcile") { target.calls.count { it == "reconcile" } == 2 }
  }

  @Test
  fun `the stream closes when the account may no longer stream`() {
    start()
    gate.value = "server|me"
    await("the stream to open") { opened.get() == 1 }

    gate.value = null

    await("the stream to close") { closed.get() == 1 }
    Thread.sleep(200)
    assertEquals(1, opened.get())
  }

  @Test
  fun `the stream restarts for another account`() {
    start()
    gate.value = "server|me"
    await("the first stream") { opened.get() == 1 }

    gate.value = "server|you"

    await("the second stream") { opened.get() == 2 }
    assertEquals(1, closed.get())
    await("a reconcile for each") { target.calls.count { it == "reconcile" } == 2 }
  }

  @Test
  fun `cancelling the foreground closes the stream and nothing is left running`() {
    start()
    gate.value = "server|me"
    await("the stream to open") { opened.get() == 1 }

    runBlocking { job?.cancelAndJoin() }

    assertEquals(1, closed.get())
    val calls = target.calls.size
    emit(RemoteEvent.RecordingChanged("late", "updated"))
    Thread.sleep(200)
    assertEquals(calls, target.calls.size)
  }

  @Test
  fun `a stream that breaks does not take the app down and starts again with the gate`() {
    var attempts = 0
    val breaking: Flow<RemoteEvent> = flow {
      attempts++
      throw IllegalStateException("boom")
    }
    job =
        scope.launch {
          LiveSync(gate, breaking, target, connectGraceMs = 50, refreshIntervalMs = 10).run()
        }

    gate.value = "server|me"
    await("the failed stream") { attempts == 1 }
    Thread.sleep(100)

    assertTrue(job?.isActive == true)
    gate.value = "server|you"
    await("the next stream") { attempts == 2 }
  }
}
