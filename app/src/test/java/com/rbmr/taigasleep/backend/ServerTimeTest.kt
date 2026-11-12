package com.rbmr.taigasleep.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTimeTest {
  @Test
  fun `a PocketBase record time is read`() {
    assertEquals(1794348000000, parseServerTime("2026-11-10 22:00:00.000Z")?.toEpochMilli())
  }

  @Test
  fun `an RFC 3339 time is read`() {
    assertEquals(1794348000000, parseServerTime("2026-11-10T22:00:00Z")?.toEpochMilli())
  }

  @Test
  fun `an empty or broken value is no time`() {
    assertNull(parseServerTime(""))
    assertNull(parseServerTime(null))
    assertNull(parseServerTime("yesterday"))
  }
}
