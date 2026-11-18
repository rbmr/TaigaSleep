package com.rbmr.taigasleep.recording

/**
 * The ringing lifecycle of [WakeAlarmService], without Android: one ring at a time, and a start
 * after a stop is a new ring. The service supplies [WakeAlarmRing] and does the foreground and
 * notification work; this decides what may ring and when.
 */
class WakeAlarmSession(
    private val ring: WakeAlarmRing,
    private val onRinging: (Boolean) -> Unit = {},
    private val onStopped: (String) -> Unit = {},
) {
  /** True while a ring is active. */
  var ringing: Boolean = false
    private set

  /**
   * Rings for [reason]. A request while a ring is already active goes to the ring, which reports it
   * and changes nothing; it is still returned so the caller need not special-case it.
   */
  fun start(reason: WakeReason, source: String): AlarmRing {
    if (ringing) return ring.start(reason, source)
    val result = ring.start(reason, source)
    ringing = true
    onRinging(true)
    return result
  }

  /** Ends the active ring. False when there was nothing to end. */
  fun stop(how: String): Boolean {
    if (!ringing) return false
    ringing = false
    ring.stop(how)
    onRinging(false)
    onStopped(how)
    return true
  }
}
