package com.rbmr.taigasleep

import com.rbmr.taigasleep.tracker.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * A clock that only moves when the test says so. With [scheduler] it follows a coroutine test
 * dispatcher's virtual time instead, so a suspending test measures the delays it drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestClock(
    var phoneNs: Long = 1_792_798_200_000_000_000L,
    var elapsed: Long = 5_000_000_000L,
    private val scheduler: TestCoroutineScheduler? = null,
) : Clock {
  // The instant a virtual clock starts from, taken once; the fields above would not follow it.
  private val phoneBaseNs = phoneNs
  private val elapsedBaseNs = elapsed

  override fun phoneNs() = scheduler?.let { phoneBaseNs + it.currentTime * 1_000_000L } ?: phoneNs

  override fun elapsedNs() = scheduler?.let { elapsedBaseNs + it.currentTime * 1_000_000L } ?: elapsed

  /** Moves a clock the test drives; a [scheduler] clock moves with the dispatcher's delays. */
  fun advanceMs(ms: Long) {
    check(scheduler == null) { "a scheduler clock moves with its dispatcher" }
    phoneNs += ms * 1_000_000L
    elapsed += ms * 1_000_000L
  }
}
