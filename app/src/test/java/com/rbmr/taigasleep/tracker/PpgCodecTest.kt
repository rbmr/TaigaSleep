package com.rbmr.taigasleep.tracker

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A PPG batch keeps every channel the SDK reports, on the Polar timestamp base. */
class PpgCodecTest {
  @Test
  fun everyChannelAndTimestampSurvivesTheBatch() {
    val t = longArrayOf(1_000, 1_020)
    val channels = arrayOf(intArrayOf(10, 20, 30), intArrayOf(11, 21, 31))
    val status = intArrayOf(1, 2)

    val encoded = PolarCodec.encodePpg(5, 6, t, channels, status).bytes
    val b = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(5, b.long)
    assertEquals(6, b.long)
    assertEquals(2, b.short.toInt())
    for (index in t.indices) {
      assertEquals(t[index], b.long)
      val width = b.get().toInt() and 0xFF
      val values = IntArray(width) { b.int }
      assertArrayEquals(channels[index], values)
      assertEquals(status[index], b.short.toInt())
    }
    assertEquals(0, b.remaining())
  }
}
