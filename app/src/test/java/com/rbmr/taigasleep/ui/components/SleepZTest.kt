package com.rbmr.taigasleep.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepZTest {
  private val eps = 1e-4f

  @Test
  fun `the second z is the first one half a loop later`() {
    val now = sleepZs(clock = 0.1f, animated = true)
    val later = sleepZs(clock = 0.6f, animated = true)
    assertEquals(now[1], later[0])
    assertEquals(now[0], later[1])
  }

  @Test
  fun `a z rises and grows at a steady pace`() {
    val a = sleepZs(0.3f, animated = true)[0]
    val b = sleepZs(0.4f, animated = true)[0]
    val c = sleepZs(0.5f, animated = true)[0]
    assertEquals(b.x - a.x, c.x - b.x, eps)
    assertEquals(b.y - a.y, c.y - b.y, eps)
    assertEquals(b.size - a.size, c.size - b.size, eps)
  }

  @Test
  fun `a z is invisible at both ends of its loop and fully shown between its fades`() {
    assertEquals(0f, sleepZs(0f, animated = true)[0].alpha, eps)
    assertEquals(0f, sleepZs(0.9999f, animated = true)[0].alpha, 1e-3f)
    assertEquals(Z_PEAK_ALPHA, sleepZs((Z_FADE_IN + Z_FADE_OUT) / 2, animated = true)[0].alpha, eps)
  }

  @Test
  fun `without motion the z's stand a quarter and three quarters into their rise`() {
    val still = sleepZs(clock = 0.77f, animated = false)
    val moving = listOf(sleepZs(0.25f, animated = true)[0], sleepZs(0.75f, animated = true)[0])
    assertEquals(moving.map { it.copy(alpha = 0f) }, still.map { it.copy(alpha = 0f) })
    assertEquals(listOf(Z_STATIC_ALPHA, Z_STATIC_ALPHA), still.map { it.alpha })
  }
}
