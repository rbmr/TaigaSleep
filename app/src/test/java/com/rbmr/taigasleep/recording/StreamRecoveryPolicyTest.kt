package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamRecoveryPolicyTest {
  private val clock = TestClock()

  private fun nowMs() = clock.elapsedNs() / 1_000_000L

  private fun connectedPolicy() =
      StreamRecoveryPolicy(nowMs()).also { it.onLink(connected = true, nowMs = nowMs()) }

  /** Moves the fake clock forward and returns the steps the policy asks for at the end. */
  private fun StreamRecoveryPolicy.after(ms: Long): List<RecoveryStep> {
    clock.advanceMs(ms)
    return evaluate(nowMs())
  }

  @Test
  fun aSilentHrRestartsTheStreamsThenReconnectsWithBackoff() {
    val policy = connectedPolicy()

    // The first 30 s of silence are the monitor's stale display, not a recovery.
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RESTART_STREAMS, policy.after(1).single().kind)

    // Still silent 30 s after the restart: the link is dropped and made again.
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    val first = policy.after(1).single()
    assertEquals(RecoveryStepKind.RECONNECT, first.kind)

    // Then 5 s, 15 s, 30 s, and every 60 s for the rest of the recording.
    assertEquals(emptyList<RecoveryStep>(), policy.after(4_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(14_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(59_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(59_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
  }

  @Test
  fun samplesResumingEndTheEscalation() {
    val policy = connectedPolicy()
    assertEquals(RecoveryStepKind.RESTART_STREAMS, policy.after(30_000).single().kind)
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(30_000).single().kind)
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(5_000).single().kind)

    // The stream delivers again: the next stall starts at the restart step, with the first delay.
    policy.onHrSample(nowMs())
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RESTART_STREAMS, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(4_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
  }

  @Test
  fun recoveryKeepsGoingForTheRestOfTheRecording() {
    val policy = connectedPolicy()
    var reconnects = 0
    var restarts = 0
    // Half an hour of silence in one-second ticks: one restart, then a reconnect about every minute.
    repeat(1_800) {
      clock.advanceMs(1_000)
      for (step in policy.evaluate(nowMs())) {
        when (step.kind) {
          RecoveryStepKind.RESTART_STREAMS -> restarts++
          RecoveryStepKind.RECONNECT -> reconnects++
          RecoveryStepKind.WAIT -> fail("unexpected wait: ${step.reason}")
          RecoveryStepKind.RESET_SENSOR -> fail("unexpected reset: ${step.reason}")
        }
      }
    }
    assertEquals(1, restarts)
    assertTrue("reconnects $reconnects", reconnects in 28..32)
  }

  @Test
  fun aReconnectThatBringsNoSamplesContinuesTheBackoff() {
    val policy = connectedPolicy()
    assertEquals(RecoveryStepKind.RESTART_STREAMS, policy.after(30_000).single().kind)
    val first = policy.after(30_000).single()
    assertEquals(RecoveryStepKind.RECONNECT, first.kind)

    // The service drops the link and makes it again, and HR is still silent.
    policy.onLink(connected = false, nowMs = nowMs())
    policy.onLink(connected = true, nowMs = nowMs())
    assertEquals(emptyList<RecoveryStep>(), policy.after(4_999))
    val second = policy.after(1).single()
    assertEquals(RecoveryStepKind.RECONNECT, second.kind)
    assertTrue(second.reason, second.reason.contains("attempt 2"))

    // The next attempt waits 15 s, not 5 s again: the ladder continues across the reconnect.
    assertEquals(emptyList<RecoveryStep>(), policy.after(14_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
  }

  @Test
  fun aReconnectThatNeverConnectsIsFollowedByFurtherAttempts() {
    val policy = connectedPolicy()
    assertEquals(RecoveryStepKind.RESTART_STREAMS, policy.after(30_000).single().kind)
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(30_000).single().kind)

    // The service drops the link and asks for it again, but it never comes back.
    policy.onLink(connected = false, nowMs = nowMs())
    assertEquals(emptyList<RecoveryStep>(), policy.after(4_999))
    val second = policy.after(1).single()
    assertEquals(RecoveryStepKind.RECONNECT, second.kind)
    assertTrue(second.reason, second.reason.contains("attempt 2"))

    // The ladder goes on while the link stays down: 15 s, then 30 s, then every 60 s.
    assertEquals(emptyList<RecoveryStep>(), policy.after(14_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(59_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)

    // Until samples arrive: the next stall starts at the restart step and the first delay again.
    policy.onLink(connected = true, nowMs = nowMs())
    policy.onHrSample(nowMs())
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RESTART_STREAMS, policy.after(1).single().kind)
  }

  @Test
  fun bluetoothOffAndOnReconnectsAndRestartsTheStreams() {
    val policy = connectedPolicy()

    val off = policy.onBluetooth(enabled = false, nowMs = nowMs()).single()
    assertEquals(RecoveryStepKind.WAIT, off.kind)
    // Nothing is escalated while the radio is off, however long the night has left.
    assertEquals(emptyList<RecoveryStep>(), policy.after(5 * 60_000))

    assertEquals(
        RecoveryStepKind.RECONNECT,
        policy.onBluetooth(enabled = true, nowMs = nowMs()).single().kind,
    )
    // A new link that stays silent is reconnected again rather than restarted: the streams were
    // started with the reconnect.
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(4_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
  }

  @Test
  fun bluetoothTurningOnWithoutHavingBeenOffChangesNothing() {
    val policy = connectedPolicy()
    assertEquals(emptyList<RecoveryStep>(), policy.onBluetooth(enabled = true, nowMs = nowMs()))
  }

  @Test
  fun aLinkThatStaysDownIsReconnectedThenTheSensorIsReset() {
    val policy = connectedPolicy()

    // A short drop is ordinary, so the first minute is left to the sensor's own reconnection.
    policy.onLink(connected = false, nowMs = nowMs())
    assertEquals(emptyList<RecoveryStep>(), policy.after(59_999))

    val first = policy.after(1).single()
    assertEquals(RecoveryStepKind.RECONNECT, first.kind)
    assertTrue(first.reason, first.reason.contains("60 s"))

    // The next attempt does not drop and make the same parked session again: the sensor is reset.
    assertEquals(emptyList<RecoveryStep>(), policy.after(4_999))
    assertEquals(RecoveryStepKind.RESET_SENSOR, policy.after(1).single().kind)

    // Then the existing ladder: 15 s, 30 s, and every 60 s for the rest of the recording.
    assertEquals(emptyList<RecoveryStep>(), policy.after(14_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(59_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
  }

  @Test
  fun aLinkThatNeverComesBackIsRetriedForHoursWithoutAGapOverAMinute() {
    val policy = connectedPolicy()
    val downAtMs = nowMs()
    policy.onLink(connected = false, nowMs = downAtMs)

    val steps = mutableListOf<Pair<Long, RecoveryStepKind>>()
    repeat(2 * 60 * 60) { // Two hours in one-second ticks.
      clock.advanceMs(1_000)
      for (step in policy.evaluate(nowMs())) steps += nowMs() to step.kind
    }

    assertEquals(
        "the link is remade about a minute after it went",
        60_000L,
        steps.first().first - downAtMs,
    )
    assertEquals(RecoveryStepKind.RECONNECT, steps.first().second)
    assertEquals(
        "the next attempt resets the sensor",
        RecoveryStepKind.RESET_SENSOR,
        steps[1].second,
    )
    assertTrue(
        "only the second attempt resets: ${steps.map { it.second }}",
        steps.drop(2).all { it.second == RecoveryStepKind.RECONNECT },
    )

    var previous = steps.first().first
    for ((at, kind) in steps.drop(1)) {
      assertTrue("$kind waited ${(at - previous) / 1_000} s", at - previous <= 60_000)
      previous = at
    }
    // Two hours at no more than one attempt a minute, and none of them ended the recording.
    assertTrue("attempts ${steps.size}", steps.size in 110..130)
  }

  @Test
  fun aLinkThatComesBackMidLadderResetsIt() {
    val policy = connectedPolicy()
    policy.onLink(connected = false, nowMs = nowMs())
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(60_000).single().kind)
    assertEquals(RecoveryStepKind.RESET_SENSOR, policy.after(5_000).single().kind)
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(15_000).single().kind)

    // The link comes back with the reconnect, and the streams deliver again.
    policy.onLink(connected = true, nowMs = nowMs())
    policy.onHrSample(nowMs())

    // Nothing from the old ladder is left: a new outage gets its full minute, and its second
    // attempt resets the sensor again, exactly like the first outage did.
    policy.onLink(connected = false, nowMs = nowMs())
    assertEquals(emptyList<RecoveryStep>(), policy.after(59_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(RecoveryStepKind.RESET_SENSOR, policy.after(5_000).single().kind)
  }

  @Test
  fun bluetoothToggledDuringTheLinkDownLadderReconnectsAtOnce() {
    val policy = connectedPolicy()
    policy.onLink(connected = false, nowMs = nowMs())
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(60_000).single().kind)

    assertEquals(
        RecoveryStepKind.WAIT,
        policy.onBluetooth(enabled = false, nowMs = nowMs()).single().kind,
    )
    // Nothing is escalated while the radio is off, however long the link has been down.
    assertEquals(emptyList<RecoveryStep>(), policy.after(30 * 60_000))

    assertEquals(
        RecoveryStepKind.RECONNECT,
        policy.onBluetooth(enabled = true, nowMs = nowMs()).single().kind,
    )
    // The radio came back with a fresh reconnect, so the ladder starts again rather than resets.
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
    assertEquals(emptyList<RecoveryStep>(), policy.after(4_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)
  }

  @Test
  fun aLinkThatComesBackLetsTheStreamClockStartAgain() {
    val policy = connectedPolicy()
    policy.after(25_000)

    policy.onLink(connected = false, nowMs = nowMs())
    // A short drop does not fire the stream ladder; the link-down ladder waits its minute.
    assertEquals(emptyList<RecoveryStep>(), policy.after(59_999))
    assertEquals(RecoveryStepKind.RECONNECT, policy.after(1).single().kind)

    policy.onLink(connected = true, nowMs = nowMs())
    assertEquals(emptyList<RecoveryStep>(), policy.after(29_999))
    assertEquals(RecoveryStepKind.RESTART_STREAMS, policy.after(1).single().kind)
  }
}
