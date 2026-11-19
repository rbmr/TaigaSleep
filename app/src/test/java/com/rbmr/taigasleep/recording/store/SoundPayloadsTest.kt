package com.rbmr.taigasleep.recording.store

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The two `sound` payload layouts of `docs/recording-format.md`, byte for byte. */
class SoundPayloadsTest {
  private val phoneNs = 1_792_798_200_000_000_000L
  private val elapsedNs = 5_000_000_000L

  /** The samples as the microphone delivers them: little-endian 16-bit, in order. */
  private fun pcmOf(samples: ShortArray): ByteArray =
      ByteBuffer.allocate(samples.size * 2)
          .order(ByteOrder.LITTLE_ENDIAN)
          .apply { samples.forEach { putShort(it) } }
          .array()

  @Test
  fun `an uncompressed payload is the shared header followed by the samples`() {
    val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1234, -4321)
    val pcm = pcmOf(samples)

    val encoded = SoundPayloads.encodePcm(phoneNs, elapsedNs, 16_000, pcm)

    val expected =
        ByteBuffer.allocate(24 + 2 * samples.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(phoneNs)
            .putLong(elapsedNs)
            .putInt(16_000)
            .putInt(samples.size)
            .put(pcm)
            .array()
    assertArrayEquals(expected, encoded.bytes)
    assertEquals(24 + 2 * samples.size, encoded.bytes.size)
  }

  @Test
  fun `an uncompressed payload decodes back to its clocks, rate and samples`() {
    val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1234, -4321)
    val pcm = pcmOf(samples)

    val frame = SoundPayloads.decodePcm(SoundPayloads.encodePcm(phoneNs, elapsedNs, 16_000, pcm).bytes)

    assertEquals(phoneNs, frame.phoneNs)
    assertEquals(elapsedNs, frame.elapsedNs)
    assertEquals(16_000, frame.rateHz)
    assertEquals(samples.size, frame.sampleCount)
    assertArrayEquals(pcm, frame.pcm)
  }

  @Test
  fun `an uncompressed payload stores a partial frame's own length`() {
    val pcm = pcmOf(shortArrayOf(5, 6, 7))
    val encoded = SoundPayloads.encodePcm(phoneNs, elapsedNs, 16_000, pcm)

    val frame = SoundPayloads.decodePcm(encoded.bytes)

    assertEquals(3, frame.sampleCount)
    assertEquals(24 + 6, encoded.bytes.size)
    assertArrayEquals(pcm, frame.pcm)
  }

  @Test
  fun `a compressed payload is the header, the packet count and the packets in order`() {
    val packets = listOf(byteArrayOf(0xF8.toByte(), 1, 2, 3), byteArrayOf(0x78, 9, 9))

    val encoded = SoundPayloads.encodeOpus(phoneNs, elapsedNs, 16_000, 640, packets)

    val expected =
        ByteBuffer.allocate(24 + 2 + 2 * packets.size + packets.sumOf { it.size })
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(phoneNs)
            .putLong(elapsedNs)
            .putInt(16_000)
            .putInt(640)
            .putShort(packets.size.toShort())
            .putShort(4)
            .putShort(3)
            .apply { packets.forEach { put(it) } }
            .array()
    assertArrayEquals(expected, encoded.bytes)
    assertEquals(26 + 2 * packets.size + packets.sumOf { it.size }, encoded.bytes.size)
  }

  @Test
  fun `a compressed payload decodes back to its clocks, rate, sample count and packets`() {
    val packets = listOf(byteArrayOf(0xF8.toByte(), 1, 2, 3), byteArrayOf(0x78, 9, 9))

    val frame =
        SoundPayloads.decodeOpus(
            SoundPayloads.encodeOpus(phoneNs, elapsedNs, 16_000, 640, packets).bytes
        )

    assertEquals(phoneNs, frame.phoneNs)
    assertEquals(elapsedNs, frame.elapsedNs)
    assertEquals(16_000, frame.rateHz)
    assertEquals(640, frame.sampleCount)
    assertEquals(packets.size, frame.packets.size)
    packets.forEachIndexed { i, packet -> assertArrayEquals(packet, frame.packets[i]) }
  }
}
