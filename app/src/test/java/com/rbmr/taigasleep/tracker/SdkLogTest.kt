package com.rbmr.taigasleep.tracker

import com.rbmr.taigasleep.TestClock
import org.junit.Assert.assertEquals
import org.junit.Test

class SdkLogTest {
  private val clock = TestClock()

  @Test
  fun `the ring keeps only its newest 200 lines`() {
    val log = SdkLog(clock)

    repeat(205) { log.add("line $it") }

    val kept = log.since(0)
    assertEquals(200, kept.size)
    assertEquals("line 5", kept.first())
    assertEquals("line 204", kept.last())
  }

  @Test
  fun `a line longer than 300 characters is cut`() {
    val log = SdkLog(clock)

    log.add("x".repeat(400))

    assertEquals(300, log.since(0).single().length)
  }

  @Test
  fun `lines from before the moment asked for are left out`() {
    val log = SdkLog(clock)
    clock.advanceMs(1_000)
    log.add("before")
    clock.advanceMs(1_000)
    log.add("after")

    val fromLast = (clock.elapsed - 500_000_000L) / 1_000_000L

    assertEquals(listOf("after"), log.since(fromLast))
  }

  @Test
  fun `a line that carries sample bytes never enters the ring`() {
    val log = SdkLog(clock)

    log.add("ble notification hr hex: 00 12 ff 7a")
    log.add("device connected")

    assertEquals(listOf("device connected"), log.since(0))
  }
}
