package com.rbmr.taigasleep.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Polar payloads are the bytes the H10 wrote before trackers reported their own streams. The
 * exact layout is pinned here, so moving the encoder out of the writer cannot change a recording.
 */
class PolarCodecBytesTest {
  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  @Test
  fun `the heart rate payload is the original layout`() {
    val encoded =
        PolarCodec.encodeHr(
            1,
            2,
            60,
            PolarCodec.hrFlags(contactSupported = true, contact = true, rrAvailable = true),
            intArrayOf(1000),
        )
    assertEquals("010000000000000002000000000000003c000701e803", hex(encoded.bytes))
  }

  @Test
  fun `the ecg payload is the original layout, linear timestamps included`() {
    val encoded = PolarCodec.encodeEcg(1, 2, longArrayOf(0, 1_000_000_000), intArrayOf(1000, -1000))
    assertEquals(
        "01000000000000000200000000000000000200000000000000000000ca9a3b00000000e803000018fcffff",
        hex(encoded.bytes),
    )
  }

  @Test
  fun `the accelerometer payload is the original layout`() {
    val encoded =
        PolarCodec.encodeAcc(0, 0, longArrayOf(0, 20_000_000), intArrayOf(1, 2, 3, 4, 5, 6))
    assertEquals(
        "000000000000000000000000000000000002000000000000000000002d310100000000010002000300040005000600",
        hex(encoded.bytes),
    )
  }
}
