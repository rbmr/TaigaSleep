package com.rbmr.taigasleep

import com.rbmr.taigasleep.sensor.Clock

/** A clock that only moves when the test says so. */
class TestClock(var phoneNs: Long = 1_792_798_200_000_000_000L, var elapsed: Long = 5_000_000_000L) :
    Clock {
  override fun phoneNs() = phoneNs

  override fun elapsedNs() = elapsed

  fun advanceMs(ms: Long) {
    phoneNs += ms * 1_000_000L
    elapsed += ms * 1_000_000L
  }
}
