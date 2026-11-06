package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.RemoteEvent
import com.rbmr.taigasleep.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the live stream refreshes. The library's repository is the implementation. */
interface LiveSyncTarget {
  /**
   * One refresh of everything that may be out of date: the History list and every recording whose
   * analysis is not settled. It runs whenever the stream (re)connects, because events are not
   * replayed.
   */
  suspend fun reconcile()

  /** One refresh of a single recording and its analyses. */
  suspend fun refreshRecording(recordingId: String)

  /** The server says the recording is gone: drop it from what is shown, without reading it. */
  suspend fun recordingDeleted(recordingId: String)
}

/**
 * Keeps what the app shows current while it is on screen, with no polling. [run] is meant to be
 * collected only while the app is in the foreground (`repeatOnLifecycle(STARTED)`): cancelling it
 * closes the stream, and nothing runs in the background. What happened while the app was closed is
 * read at the next open.
 *
 * - [gate] says whether a stream may run, and for which account. It starts the stream when it turns
 *   non-null, restarts it when the account changes and stops it when it turns null, so logging in or
 *   out, or a server that turns out to speak another API revision, takes effect at once.
 * - Every connect of [events] triggers one [LiveSyncTarget.reconcile]. If the stream has not
 *   connected after [connectGraceMs] (a server without the stream, a proxy that blocks it), one is
 *   done anyway, so opening the app always refreshes once.
 * - An `analysis` or `recording` event refreshes that recording only. Events for the same recording
 *   that arrive close together are one refresh: an upload changes its recording once per chunk, and
 *   a refresh per chunk would be a request storm. A `deleted` recording event is not read back: the
 *   recording is already gone, so it is dropped from the list instead.
 */
class LiveSync(
    private val gate: Flow<String?>,
    private val events: Flow<RemoteEvent>,
    private val target: LiveSyncTarget,
    private val connectGraceMs: Long = CONNECT_GRACE_MS,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
  /** Suspends until cancelled. */
  suspend fun run() {
    gate.collectLatest { account -> if (account != null) session() }
  }

  private suspend fun session() {
    try {
      coroutineScope {
        val queue = SyncQueue(target, refreshIntervalMs, clock, sleep)
        val worker = launch { queue.run() }
        val connected = AtomicBoolean(false)
        val grace: Job = launch {
          sleep(connectGraceMs)
          if (!connected.get()) queue.requestReconcile()
        }
        try {
          events.collect { event ->
            when (event) {
              RemoteEvent.Connected -> {
                connected.set(true)
                grace.cancel()
                queue.requestReconcile()
              }
              is RemoteEvent.AnalysisChanged -> queue.requestRefresh(event.recording)
              is RemoteEvent.RecordingChanged ->
                  if (event.change == "deleted") queue.requestDeleted(event.id)
                  else queue.requestRefresh(event.id)
            }
          }
        } finally {
          grace.cancel()
          worker.cancel()
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Nothing that goes wrong here may take the app down; the next foreground starts over.
      Diagnostics.log("live_sync_error", mapOf("error" to e.javaClass.simpleName))
    }
  }

  private companion object {
    const val CONNECT_GRACE_MS = 3_000L
    const val REFRESH_INTERVAL_MS = 2_000L
  }
}

/**
 * The work an event asks for, folded together. A reconcile covers every single refresh asked for
 * before it starts; a recording asked for again while it waits is still one refresh; and a recording
 * is refreshed at most once per [intervalMs], the last request always being served after the pause.
 * A deleted recording is dropped once, however many events announce it. One worker runs the work one
 * step at a time, so the stream is never held up by it.
 */
internal class SyncQueue(
    private val target: LiveSyncTarget,
    private val intervalMs: Long,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
  private val lock = Any()
  private var reconcile = false
  private val recordings = LinkedHashSet<String>()
  private val deleted = LinkedHashSet<String>()
  private val lastRefresh = HashMap<String, Long>()
  private val wake = Channel<Unit>(Channel.CONFLATED)

  fun requestReconcile() {
    synchronized(lock) { reconcile = true }
    wake.trySend(Unit)
  }

  fun requestRefresh(recordingId: String) {
    synchronized(lock) { recordings += recordingId }
    wake.trySend(Unit)
  }

  fun requestDeleted(recordingId: String) {
    synchronized(lock) { deleted += recordingId }
    wake.trySend(Unit)
  }

  suspend fun run() {
    for (ignored in wake) drain()
  }

  private sealed interface Step {
    data object Idle : Step

    data object Reconcile : Step

    data class Refresh(val recordingId: String) : Step

    data class Deleted(val recordingId: String) : Step

    data class Wait(val ms: Long) : Step
  }

  private suspend fun drain() {
    while (true) {
      when (val step = next()) {
        Step.Idle -> return
        Step.Reconcile -> guarded { target.reconcile() }
        is Step.Refresh -> guarded { target.refreshRecording(step.recordingId) }
        is Step.Deleted -> guarded { target.recordingDeleted(step.recordingId) }
        is Step.Wait -> sleep(step.ms)
      }
    }
  }

  private fun next(): Step =
      synchronized(lock) {
        val now = clock()
        when {
          reconcile -> {
            // It reads everything a single refresh would, and starts after the events it replaces.
            reconcile = false
            recordings.clear()
            lastRefresh.clear()
            Step.Reconcile
          }
          deleted.isNotEmpty() -> {
            val recordingId = deleted.first()
            deleted.remove(recordingId)
            recordings.remove(recordingId)
            Step.Deleted(recordingId)
          }
          recordings.isEmpty() -> Step.Idle
          else -> {
            val due = recordings.firstOrNull { now - (lastRefresh[it] ?: Long.MIN_VALUE / 2) >= intervalMs }
            if (due != null) {
              recordings.remove(due)
              lastRefresh[due] = now
              Step.Refresh(due)
            } else {
              Step.Wait(recordings.minOf { lastRefresh.getValue(it) + intervalMs } - now)
            }
          }
        }
      }

  /** A refresh that fails is not retried: the next event or the next connect reads it again. */
  private suspend fun guarded(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Diagnostics.log("live_sync_refresh_error", mapOf("error" to e.javaClass.simpleName))
    }
  }
}
