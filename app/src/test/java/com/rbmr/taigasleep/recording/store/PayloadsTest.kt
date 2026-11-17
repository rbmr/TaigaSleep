package com.rbmr.taigasleep.recording.store

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadsTest {
  private fun mode(p: ByteArray) = p[16].toInt()

  private fun tsMode(p: ByteArray) = mode(p) and Payloads.TS_VALUES_I16.inv()

  @Test
  fun evenlySpacedTimestampsUseLinearMode() {
    val t = LongArray(36) { 1_000_000_000L + it * 20_000_000L }
    val enc = Payloads.encodeAcc(1, 2, t, IntArray(36 * 3) { it })
    assertEquals(Payloads.TS_LINEAR, mode(enc.bytes))
    val b = Payloads.decodeAcc(enc.bytes)
    assertArrayEquals(t, b.t)
    // 8+8+1+2 header, 16 bytes of timestamps, 6 bytes per sample.
    assertEquals(19 + 16 + 36 * 6, enc.bytes.size)
  }

  @Test
  fun nonDivisibleSpanFallsBackToExplicitAndStaysExact() {
    // The Polar SDK spaces 130 Hz samples at 1e9/130 ns, which is not an integer.
    val t = LongArray(73) { 5_000_000_000L + (it * 1_000_000_000L) / 130 }
    assertFalse(Payloads.isLinear(t))
    val enc = Payloads.encodeEcg(1, 2, t, IntArray(73) { it * 10 - 300 })
    assertEquals(Payloads.TS_EXPLICIT, tsMode(enc.bytes))
    val b = Payloads.decodeEcg(enc.bytes)
    assertArrayEquals(t, b.t)
    assertEquals(73, b.values.size)
    assertEquals(-300, b.values[0])
  }

  @Test
  fun linearModeIsOnlyUsedWhenReconstructionIsExact() {
    val rnd = java.util.Random(42)
    repeat(2000) {
      val n = 1 + rnd.nextInt(120)
      val first = rnd.nextLong() % 1_000_000_000_000L
      val step = 1L + rnd.nextInt(30_000_000)
      // Mix of exact grids, grids with one jittered sample and floor-rounded grids.
      val t = LongArray(n) { first + it * step }
      when (it % 3) {
        1 -> if (n > 2) t[n / 2] += 1
        2 -> for (i in 0 until n) t[i] = first + (i * 1_000_000_000L) / (50 + rnd.nextInt(200))
      }
      val enc = Payloads.encodeEcg(0, 0, t, IntArray(n))
      assertArrayEquals(t, Payloads.decodeEcg(enc.bytes).t)
      if (tsMode(enc.bytes) == Payloads.TS_LINEAR) {
        assertArrayEquals(t, Payloads.reconstruct(t[0], t[n - 1], n))
      } else {
        assertFalse(Payloads.isLinear(t))
      }
    }
  }

  @Test
  fun singleSampleAndTruncatingDivision() {
    val one = Payloads.encodeEcg(0, 0, longArrayOf(123), intArrayOf(7))
    assertEquals(Payloads.TS_LINEAR, tsMode(one.bytes))
    assertArrayEquals(longArrayOf(123), Payloads.decodeEcg(one.bytes).t)

    // Span 10 over 4 samples: 0, 3, 6, 10 (truncating 10/3 and 20/3).
    val t = longArrayOf(0, 3, 6, 10)
    assertTrue(Payloads.isLinear(t))
    assertArrayEquals(t, Payloads.reconstruct(0, 10, 4))
    // A descending (negative span) series truncates toward zero too.
    val down = longArrayOf(10, 7, 4, 0) // floor division would give 10, 6, 3, 0
    assertTrue(Payloads.isLinear(down))
    assertArrayEquals(down, Payloads.decodeEcg(Payloads.encodeEcg(0, 0, down, IntArray(4)).bytes).t)
  }

  @Test
  fun overflowingSpanFallsBackToExplicit() {
    val t = longArrayOf(Long.MIN_VALUE / 2, 0, Long.MAX_VALUE / 2)
    val enc = Payloads.encodeEcg(0, 0, t, IntArray(3))
    assertEquals(Payloads.TS_EXPLICIT, tsMode(enc.bytes))
    assertArrayEquals(t, Payloads.decodeEcg(enc.bytes).t)
  }

  @Test
  fun hrFlagsAndRrPacking() {
    val flags = Payloads.hrFlags(contactSupported = true, contact = false, rrAvailable = true)
    assertEquals(0b101, flags)
    val enc = Payloads.encodeHr(11, 22, 61, flags, intArrayOf(980, 1012, 65535))
    val b = ByteBuffer.wrap(enc.bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(11L, b.long)
    assertEquals(22L, b.long)
    assertEquals(61, b.short.toInt())
    assertEquals(0b101, b.get().toInt())
    assertEquals(3, b.get().toInt())
    assertEquals(20 + 6, enc.bytes.size)
    val d = Payloads.decodeHr(enc.bytes)
    assertEquals(61, d.hr)
    assertArrayEquals(intArrayOf(980, 1012, 65535), d.rrMs)
    assertEquals(0, enc.clamped)

    val none = Payloads.decodeHr(Payloads.encodeHr(1, 2, 70, 0, IntArray(0)).bytes)
    assertEquals(0, none.rrMs.size)
    assertEquals(0b011, Payloads.hrFlags(true, true, false))
  }

  @Test
  fun accValuesOutsideI16AreClampedAndCounted() {
    val xyz = intArrayOf(32767, -32768, 0, 40000, -40000, 123)
    val enc = Payloads.encodeAcc(0, 0, longArrayOf(0, 20_000_000), xyz)
    assertEquals(2, enc.clamped)
    val d = Payloads.decodeAcc(enc.bytes)
    assertArrayEquals(intArrayOf(32767, -32768, 0, 32767, -32768, 123), d.values)
  }

  @Test
  fun ecgBatchesRoundTripRandomSamplesAsI16() {
    val rnd = java.util.Random(251)
    repeat(500) {
      val n = 1 + rnd.nextInt(500)
      val t = LongArray(n) { 5_000_000_000L + (it * 1_000_000_000L) / 130 }
      val values = IntArray(n) { rnd.nextInt(65536) - 32768 }
      val enc = Payloads.encodeEcg(11, 22, t, values)

      assertEquals(Payloads.TS_VALUES_I16, mode(enc.bytes) and Payloads.TS_VALUES_I16)
      val batch = Payloads.decodeEcg(enc.bytes)
      assertTrue(batch.valuesI16)
      assertArrayEquals(t, batch.t)
      assertArrayEquals(values, batch.values)
      if (tsMode(enc.bytes) == Payloads.TS_LINEAR) {
        assertEquals(19 + 16 + 2 * n, enc.bytes.size)
      }
    }
  }

  @Test
  fun ecgSamplesAtAndBeyondTheI16LimitsStayExact() {
    val atLimits = intArrayOf(-32768, -32767, 0, 32767)
    val fits = Payloads.encodeEcg(0, 0, longArrayOf(10, 20, 30, 40), atLimits)
    assertEquals(Payloads.TS_VALUES_I16, mode(fits.bytes) and Payloads.TS_VALUES_I16)
    assertArrayEquals(atLimits, Payloads.decodeEcg(fits.bytes).values)

    for (beyond in intArrayOf(-32769, 32768)) {
      val values = intArrayOf(-1, beyond, 1)
      val enc = Payloads.encodeEcg(0, 0, longArrayOf(10, 20, 30), values)
      assertEquals(0, mode(enc.bytes) and Payloads.TS_VALUES_I16)
      val batch = Payloads.decodeEcg(enc.bytes)
      assertFalse(batch.valuesI16)
      assertArrayEquals(values, batch.values)
    }
  }

  @Test
  fun aSaturatedEcgBatchIsStoredAsI16AndRoundTrips() {
    val values = intArrayOf(18_000, -18_000, 18_000, -18_000)
    val t = longArrayOf(0, 7, 14, 21)
    val enc = Payloads.encodeEcg(0, 0, t, values)
    assertTrue(Payloads.decodeEcg(enc.bytes).valuesI16)
    assertArrayEquals(values, Payloads.decodeEcg(enc.bytes).values)
    // 8+8+1+2 header, 16 bytes of timestamps, 2 bytes per sample.
    assertEquals(19 + 16 + 4 * 2, enc.bytes.size)
  }

  @Test
  fun anEcgPayloadWithoutTheWidthFlagDecodesAs32Bit() {
    // A payload written before the flag existed: ts_mode 0, 32-bit values.
    val values = intArrayOf(1234, -5678, 32768)
    val b = ByteBuffer.allocate(19 + 16 + 4 * values.size).order(ByteOrder.LITTLE_ENDIAN)
    b.putLong(1).putLong(2).put(0).putShort(values.size.toShort())
    b.putLong(100).putLong(104)
    values.forEach { b.putInt(it) }
    val batch = Payloads.decodeEcg(b.array())
    assertFalse(batch.valuesI16)
    assertEquals(Payloads.TS_LINEAR, batch.mode)
    assertArrayEquals(values, batch.values)
  }

  @Test
  fun accValuesAreAlwaysI16AndCarryNoWidthFlag() {
    val enc = Payloads.encodeAcc(0, 0, longArrayOf(0, 20_000_000), intArrayOf(1, 2, 3, 4, 5, 6))
    assertEquals(0, mode(enc.bytes) and Payloads.TS_VALUES_I16)
    assertTrue(Payloads.decodeAcc(enc.bytes).valuesI16)
  }
}
