package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect step on a scripted link: it waits for the old link to go, confirms the new one and
 * records what it did. The virtual dispatcher drives every wait, so no test sleeps or measures the
 * wall clock.
 */
class StreamRecoveryStepTest {
  private val listener =
      object : TrackerListener {
        override fun onFrame(frame: TrackerFrame) = Unit

        override fun onError(message: String) = Unit
      }

  private fun reconnectStep() = RecoveryStep(RecoveryStepKind.RECONNECT, "test reconnect")

  private class Events {
    val written = mutableListOf<Pair<String, String>>()

    fun write(type: String, message: String) {
      written += type to message
    }
  }

  @Test
  fun `a reconnect waits for the disconnect callback and connects in one step`() = runTest {
    val clock = TestClock(scheduler = testScheduler)
    val tracker =
        ScriptedLinkTracker().apply {
          disconnectCallbackMs = 500
          connectNeedsDisconnect = true
        }
    val events = Events()

    val report =
        requireNotNull(
            executeRecovery(
                reconnectStep(),
                RecoveryTarget(tracker, setOf("hr"), listener),
                clock,
                events::write,
                reconnectPauseMs = 0,
            )
        )

    assertEquals(ConnectionState.CONNECTED, tracker.connection.value)
    assertEquals(1, tracker.connects)
    assertEquals(1, tracker.streamStarts)
    assertTrue(report.connected)
    assertFalse(report.secondConnect)
    assertEquals(500L, report.disconnectMs)
    assertFalse(report.disconnectTimedOut)
    assertEquals(0L, report.connectMs)
    assertEquals(
        listOf("reconnect_step" to "reconnect: disconnect took 500 ms, connected after 0 ms"),
        events.written,
    )
  }

  @Test
  fun `a disconnect that is never reported still connects after the timeout`() = runTest {
    val clock = TestClock(scheduler = testScheduler)
    val tracker =
        ScriptedLinkTracker().apply {
          disconnectCallbackMs = null
          connectNeedsDisconnect = true
        }
    val events = Events()

    val report =
        requireNotNull(
            executeRecovery(
                reconnectStep(),
                RecoveryTarget(tracker, setOf("hr"), listener),
                clock,
                events::write,
                reconnectPauseMs = 0,
            )
        )

    assertEquals(ConnectionState.CONNECTED, tracker.connection.value)
    assertEquals(1, tracker.connects)
    assertTrue(report.disconnectTimedOut)
    assertEquals(StreamRecoveryPolicy.RECONNECT_DISCONNECT_TIMEOUT_MS, report.disconnectMs)
    assertTrue(report.connected)
    assertEquals(
        listOf(
            "reconnect_step" to
                "reconnect: disconnect timed out after 5000 ms, connected after 0 ms"
        ),
        events.written,
    )
  }

  @Test
  fun `a connect that is lost once is made again in the same step`() = runTest {
    val clock = TestClock(scheduler = testScheduler)
    val tracker =
        ScriptedLinkTracker().apply {
          connectNeedsDisconnect = true
          ignoredConnects = 1
        }
    val events = Events()

    val report =
        requireNotNull(
            executeRecovery(
                reconnectStep(),
                RecoveryTarget(tracker, setOf("hr"), listener),
                clock,
                events::write,
                reconnectPauseMs = 0,
            )
        )

    assertEquals(2, tracker.connects)
    assertEquals(1, tracker.streamStarts)
    assertTrue(report.connected)
    assertTrue(report.secondConnect)
    assertEquals(StreamRecoveryPolicy.RECONNECT_CONNECT_TIMEOUT_MS, report.connectMs)
    assertEquals(
        listOf(
            "reconnect_step" to
                "reconnect: disconnect took 100 ms, connected after 15000 ms on a second connect"
        ),
        events.written,
    )
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `a failed reconnect step leaves the next attempt due at once`() = runTest {
    val clock = TestClock(scheduler = testScheduler)
    val policy = StreamRecoveryPolicy(clock.elapsedNs() / 1_000_000L)
    policy.onLink(connected = true, nowMs = clock.elapsedNs() / 1_000_000L)
    // 30 s of silence restart the streams, and 30 s more ask for the first reconnect.
    advanceTimeBy(30_000)
    assertEquals(
        RecoveryStepKind.RESTART_STREAMS,
        policy.evaluate(clock.elapsedNs() / 1_000_000L).single().kind,
    )
    advanceTimeBy(30_000)
    val step = policy.evaluate(clock.elapsedNs() / 1_000_000L).single()
    assertEquals(RecoveryStepKind.RECONNECT, step.kind)

    val tracker = ScriptedLinkTracker().apply { connectSucceeds = false }
    val report =
        executeRecovery(
            step,
            RecoveryTarget(tracker, setOf("hr"), listener),
            clock,
            reconnectPauseMs = 0,
        )

    assertFalse(requireNotNull(report).connected)
    // Both waits have already outlasted the ladder's 5 s delay, so the next attempt is due now.
    assertEquals(
        RecoveryStepKind.RECONNECT,
        policy.evaluate(clock.elapsedNs() / 1_000_000L).single().kind,
    )
  }

  @Test
  fun `a connect that never completes is tried once more and reported as failed`() = runTest {
    val clock = TestClock(scheduler = testScheduler)
    val tracker = ScriptedLinkTracker().apply { connectSucceeds = false }
    val events = Events()

    val report =
        requireNotNull(
            executeRecovery(
                reconnectStep(),
                RecoveryTarget(tracker, setOf("hr"), listener),
                clock,
                events::write,
                reconnectPauseMs = 0,
            )
        )

    assertEquals(2, tracker.connects)
    assertEquals(0, tracker.streamStarts)
    assertFalse(report.connected)
    assertTrue(report.secondConnect)
    assertNull(report.connectMs)
    assertEquals(
        listOf(
            "reconnect_step" to
                "reconnect: disconnect took 100 ms, no link after a second connect"
        ),
        events.written,
    )
    assertEquals(
        mapOf(
            "disconnect_ms" to 100L,
            "disconnect_timed_out" to false,
            "connect_ms" to null,
            "second_connect" to true,
            "connected" to false,
        ),
        report.fields(),
    )
  }
}
