package com.rbmr.taigasleep.recording

/** A scheduled action that can be cancelled before it runs. */
fun interface AlarmCancel {
  fun cancel()
}

/** The service's own delayed stops; a test supplies a manual scheduler. */
interface AlarmTimers {
  fun schedule(delayMs: Long, action: () -> Unit): AlarmCancel
}

/** Which ring, if any, the session is running. */
enum class AlarmPhase {
  IDLE,
  TEST,
  RINGING,
}

/**
 * The ringing lifecycle of [WakeAlarmService], without Android: one ring at a time, its own test
 * stop, and a start after a stop is a new ring. The service supplies [WakeAlarmRing] and does the
 * foreground and notification work; this decides what may ring and when.
 */
class WakeAlarmSession(
    private val ring: WakeAlarmRing,
    private val timers: AlarmTimers,
    private val onRinging: (Boolean) -> Unit = {},
    private val onStopped: (String) -> Unit = {},
) {
  var phase: AlarmPhase = AlarmPhase.IDLE
    private set

  /** True while a ring is active. */
  val ringing: Boolean
    get() = phase != AlarmPhase.IDLE

  private var testTimer: AlarmCancel? = null
  private var timeoutTimer: AlarmCancel? = null

  /**
   * Rings for [reason]. Returns null when the request is ignored: a test while a real alarm rings,
   * or a test while a test already rings. A real alarm always wins over a test.
   */
  @Suppress("ReturnCount")
  fun start(reason: WakeReason, source: String, testMs: Long = 0L): AlarmRing? {
    val test = testMs > 0
    when (phase) {
      AlarmPhase.RINGING -> {
        if (test) return null
        // The ring reports the repeat and keeps the volume it has to restore.
        return ring.start(reason, source)
      }
      AlarmPhase.TEST -> {
        if (test) return null
        // A real alarm wins: end the test ring and its pending stops, then ring for real.
        cancelTimers()
        ring.stop(HOW_TEST)
        phase = AlarmPhase.IDLE
      }
      AlarmPhase.IDLE -> Unit
    }
    val result = ring.start(reason, source)
    phase = if (test) AlarmPhase.TEST else AlarmPhase.RINGING
    onRinging(true)
    timeoutTimer = timers.schedule(MAX_RING_MS) { stop(HOW_TIMEOUT) }
    if (test) testTimer = timers.schedule(testMs) { stop(HOW_TEST) }
    return result
  }

  /** Ends the active ring. False when there was nothing to end. */
  fun stop(how: String): Boolean {
    if (!ringing) return false
    cancelTimers()
    phase = AlarmPhase.IDLE
    ring.stop(how)
    onRinging(false)
    onStopped(how)
    return true
  }

  private fun cancelTimers() {
    testTimer?.cancel()
    testTimer = null
    timeoutTimer?.cancel()
    timeoutTimer = null
  }

  companion object {
    const val HOW_TEST = "test"
    const val HOW_TIMEOUT = "timeout"

    /** A ring never runs longer than this, even if Stop is never pressed. */
    const val MAX_RING_MS = 60 * 60_000L
  }
}
