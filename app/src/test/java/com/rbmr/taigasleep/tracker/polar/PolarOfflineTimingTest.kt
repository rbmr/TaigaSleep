package com.rbmr.taigasleep.tracker.polar

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Offline pulse intervals are placed on their accumulated beats, not one per millisecond. */
class PolarOfflineTimingTest {
  @Test
  fun pulseIntervalsAccumulateFromTheEntryStart() {
    val startMs = 1_792_798_200_000L
    val stamps = ppiTimestampsNs(startMs, intArrayOf(1000, 900, 1100))

    assertArrayEquals(
        longArrayOf(startMs + 1000L, startMs + 1900L, startMs + 3000L)
            .map { it * 1_000_000L }
            .toLongArray(),
        stamps,
    )
  }
}
