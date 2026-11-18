@file:Suppress("TooManyFunctions")

package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.TrackerEntry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** How long a wait may last before the test fails instead of hanging the build. */
private const val HANG_GUARD_MS = 60_000L

/** How often a wait looks again when the code under test has not reported a step. */
private const val RECHECK_MS = 10L

/** What the fake host did, in the order the recording did it: the tests wait on these, not on time. */
internal enum class TestHostSignal {
  DISARMED,
  REFRESHED,
  FINISHED,
}

/** A wake lock that only remembers whether it is held. */
internal class TestWakeLock : RecordingWakeLock {
  override var isHeld = false
    private set

  override fun acquire(timeoutMs: Long) {
    isHeld = true
  }

  override fun release() {
    isHeld = false
  }
}

/**
 * The recording's outside world in a test: the tracker, the wake lock and the callbacks the
 * recording makes after each step, without Android or a service.
 */
internal class TestRecordingHost(val sensor: Tracker) : RecordingHost {
  @Volatile override var activeSessionId: String? = null
  @Volatile override var heartbeatMs = 0L
  @Volatile var running = false
  @Volatile var finished = false
  @Volatile var state: RecordingState? = null
  @Volatile var armed: ArmedWake? = null
  @Volatile var firedAlarms = 0

  /** Counts the recorder's last step of a start, which comes after the streams are open. */
  @Volatile var refreshed = 0

  val finishedRecordings = CopyOnWriteArrayList<String>()

  private val signals = LinkedBlockingQueue<Pair<Long, TestHostSignal>>()
  private val issued = AtomicLong()

  /** Where the host's history stands now: a wait looks only at what comes after it. */
  fun mark(): Long = issued.get()

  /**
   * Blocks until this host reports [signal] after [mark]. The recording decides when that is;
   * [HANG_GUARD_MS] only turns a regression into a failure instead of a hung build.
   */
  fun awaitAfter(mark: Long, signal: TestHostSignal) {
    while (true) {
      val (at, seen) =
          signals.poll(HANG_GUARD_MS, TimeUnit.MILLISECONDS)
              ?: error("the host never reported $signal")
      if (at > mark && seen == signal) return
    }
  }

  private fun signal(signal: TestHostSignal) {
    signals.put(issued.incrementAndGet() to signal)
  }

  override fun obtain(kind: SensorKind, id: String, name: String): Tracker = sensor

  override fun mayStart(mode: RecordingMode) = true

  override fun readinessSummary() = "test readiness"

  override fun newWakeLock(): RecordingWakeLock = TestWakeLock()

  override fun scheduleWatchdog() = Unit

  override fun cancelWatchdog() = Unit

  override fun wakeWindow(): ArmedWake? = armed

  override fun armWake(armed: ArmedWake) {
    this.armed = armed
  }

  override fun disarmWake() {
    armed = null
    signal(TestHostSignal.DISARMED)
  }

  override fun fireWakeAlarm(reason: WakeReason) {
    firedAlarms++
  }

  override fun markRunning(running: Boolean) {
    this.running = running
  }

  override val device =
      RecordingDevice(
          manufacturer = "JVM",
          model = "test",
          android = "0",
          sdk = 0,
          appVersion = "test",
          appVersionCode = 1,
      )

  override fun message(message: RecordingMessage) = message.name

  override fun refreshRecordings() {
    refreshed++
    signal(TestHostSignal.REFRESHED)
  }

  override fun onState(state: RecordingState) {
    this.state = state
  }

  override fun onRecordingFinished(recordingId: String) {
    finishedRecordings += recordingId
  }

  override fun onFetchCompleted(recordingId: String) = Unit

  override fun finishService() {
    finished = true
    signal(TestHostSignal.FINISHED)
  }
}

/**
 * A tracker whose fetch says when it ended, so a test can wait for a cancelled read instead of
 * sleeping a fixed time to see whether it still writes.
 */
internal class EndingFetchTracker(private val tracker: OnDeviceTracker) :
    OnDeviceTracker by tracker {
  private val ended = CountDownLatch(1)

  override fun fetchOnDevice(): List<TrackerEntry> =
      try {
        tracker.fetchOnDevice()
      } finally {
        ended.countDown()
      }

  /** Waits for the fetch to return or fail, cancelled or not, and fails when it never does. */
  fun awaitFetchEnded(timeoutMs: Long = HANG_GUARD_MS) {
    assertTrue("the fetch did not end", ended.await(timeoutMs, TimeUnit.MILLISECONDS))
  }
}

/**
 * Waits until [condition] holds, then returns. It is a condition wait, not a duration: a run needs a
 * fraction of the timeout, which only turns a regression into a named failure instead of a hung
 * build.
 */
internal fun awaitCondition(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
  val deadline = System.currentTimeMillis() + timeoutMs
  while (!condition()) {
    if (System.currentTimeMillis() >= deadline) fail("timed out waiting for $what")
    Thread.sleep(RECHECK_MS)
  }
}
