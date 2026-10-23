package com.rbmr.taigasleep.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateDiffTest {
  private val before = mapOf("bucket" to "EXEMPTED", "whitelisted" to true, "charging" to true)

  @Test
  fun noChangeGivesNoDiff() {
    assertTrue(StateDiff.diff(before, before.toMap()).isEmpty())
  }

  @Test
  fun reportsOldAndNewValues() {
    val after = before + mapOf("bucket" to "ACTIVE", "whitelisted" to false)
    val diff = StateDiff.diff(before, after)
    assertEquals(listOf<Any?>("EXEMPTED", "ACTIVE"), diff["bucket"])
    assertEquals(listOf<Any?>(true, false), diff["whitelisted"])
    assertEquals(2, diff.size)
  }

  @Test
  fun missingKeyCountsAsNull() {
    val diff = StateDiff.diff(before, before - "charging")
    assertEquals(listOf<Any?>(true, null), diff["charging"])
  }

  @Test
  fun criticalKeysAreAllReadByTheHeartbeat() {
    assertTrue(StateDiff.KEYS.containsAll(StateDiff.CRITICAL))
  }
}
