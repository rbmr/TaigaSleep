package com.rbmr.taigasleep.recording.store

import com.rbmr.taigasleep.tracker.Encoded
import com.rbmr.taigasleep.tracker.PolarCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Polar payload codec under the name the store used before codecs were named. It keeps the
 * frame format in one implementation while old call sites read naturally.
 */
object Payloads {
  const val FLAG_CONTACT_SUPPORTED = PolarCodec.FLAG_CONTACT_SUPPORTED
  const val FLAG_CONTACT = PolarCodec.FLAG_CONTACT
  const val FLAG_RR_AVAILABLE = PolarCodec.FLAG_RR_AVAILABLE
  const val TS_LINEAR = PolarCodec.TS_LINEAR
  const val TS_EXPLICIT = PolarCodec.TS_EXPLICIT
  const val TS_VALUES_I16 = PolarCodec.TS_VALUES_I16
  const val MAX_RR = PolarCodec.MAX_RR
  const val MAX_BATCH = PolarCodec.MAX_BATCH

  fun hrFlags(contactSupported: Boolean, contact: Boolean, rrAvailable: Boolean): Int =
      PolarCodec.hrFlags(contactSupported, contact, rrAvailable)

  fun encodeHr(phoneNs: Long, elapsedNs: Long, hr: Int, flags: Int, rrMs: IntArray): Encoded =
      PolarCodec.encodeHr(phoneNs, elapsedNs, hr, flags, rrMs)

  fun isLinear(t: LongArray): Boolean = PolarCodec.isLinear(t)

  fun reconstruct(first: Long, last: Long, n: Int): LongArray = PolarCodec.reconstruct(first, last, n)

  fun encodeEcg(phoneNs: Long, elapsedNs: Long, t: LongArray, voltageUv: IntArray): Encoded =
      PolarCodec.encodeEcg(phoneNs, elapsedNs, t, voltageUv)

  fun encodeAcc(phoneNs: Long, elapsedNs: Long, t: LongArray, xyzMg: IntArray): Encoded =
      PolarCodec.encodeAcc(phoneNs, elapsedNs, t, xyzMg)

  fun decodeHr(p: ByteArray): PolarCodec.Hr = PolarCodec.decodeHr(p)

  fun decodeEcg(p: ByteArray): PolarCodec.Batch = PolarCodec.decodeEcg(p)

  fun decodeAcc(p: ByteArray): PolarCodec.Batch = PolarCodec.decodeAcc(p)
}

/** One sound payload: its two clocks, its rate and length, and its audio as stored. */
class SoundFrame(
    val phoneNs: Long,
    val elapsedNs: Long,
    val rateHz: Int,
    val sampleCount: Int,
    /** The mono 16-bit little-endian samples of a `pcm-s16le-v1` payload. */
    val pcm: ByteArray = ByteArray(0),
    /** The packets of an `opus-v1` payload, in the order the encoder emitted them. */
    val packets: List<ByteArray> = emptyList(),
)

/**
 * The `sound` stream's payloads of `docs/recording-format.md`: mono audio at one rate, stored either
 * uncompressed (`pcm-s16le-v1`) or as Opus packets (`opus-v1`). Both layouts share the header of
 * two clocks, the sample rate and the sample count; everything after it is the codec's own.
 */
object SoundPayloads {
  /** The rate the app records at. The payload carries it, so another rate needs no new codec. */
  const val RATE_HZ = 16_000

  /** The Opus encoder's bit rate; the storage estimate is derived from it. */
  const val OPUS_BIT_RATE_BPS = 32_000

  /** Bytes of the header both codecs share. */
  const val HEADER_BYTES = 24

  private const val PCM_BYTES_PER_SAMPLE = 2
  private const val MAX_PACKETS = 0xFFFF
  private const val MAX_PACKET_BYTES = 0xFFFF

  /**
   * One second of mono 16-bit little-endian PCM, in the order the microphone delivered it. The
   * payload ends after the samples, so [length] is the number of bytes stored.
   */
  fun encodePcm(
      phoneNs: Long,
      elapsedNs: Long,
      rateHz: Int,
      pcm: ByteArray,
      length: Int = pcm.size,
  ): Encoded {
    require(rateHz > 0) { "a sound frame needs a sample rate" }
    require(length in 1..pcm.size && length % PCM_BYTES_PER_SAMPLE == 0) {
      "a sound frame holds whole 16-bit samples"
    }
    val b = buffer(HEADER_BYTES + length)
    b.putLong(phoneNs).putLong(elapsedNs).putInt(rateHz).putInt(length / PCM_BYTES_PER_SAMPLE)
    b.put(pcm, 0, length)
    return Encoded(b.array())
  }

  /**
   * One `opus-v1` payload: the packets the encoder emitted, each with its length. [sampleCount] is
   * how many samples they decode to at [rateHz], which places the frame on the timeline.
   */
  fun encodeOpus(
      phoneNs: Long,
      elapsedNs: Long,
      rateHz: Int,
      sampleCount: Int,
      packets: List<ByteArray>,
  ): Encoded {
    require(rateHz > 0) { "a sound frame needs a sample rate" }
    require(sampleCount > 0) { "a sound frame needs at least one sample" }
    require(packets.size in 1..MAX_PACKETS) { "a sound frame needs one to $MAX_PACKETS packets" }
    require(packets.all { it.size in 1..MAX_PACKET_BYTES }) { "an Opus packet does not fit its length" }
    val b = buffer(HEADER_BYTES + 2 + 2 * packets.size + packets.sumOf { it.size })
    b.putLong(phoneNs).putLong(elapsedNs).putInt(rateHz).putInt(sampleCount)
    b.putShort(packets.size.toShort())
    packets.forEach { b.putShort(it.size.toShort()) }
    packets.forEach { b.put(it) }
    return Encoded(b.array())
  }

  /** The `pcm-s16le-v1` payload read back; its samples must fill the frame exactly. */
  fun decodePcm(payload: ByteArray): SoundFrame {
    val header = header(payload)
    require(payload.size == HEADER_BYTES + PCM_BYTES_PER_SAMPLE * header.sampleCount) {
      "pcm payload length mismatch"
    }
    return SoundFrame(
        header.phoneNs,
        header.elapsedNs,
        header.rateHz,
        header.sampleCount,
        pcm = payload.copyOfRange(HEADER_BYTES, payload.size),
    )
  }

  /** The `opus-v1` payload read back; its packet lengths must end exactly at the payload's end. */
  fun decodeOpus(payload: ByteArray): SoundFrame {
    val header = header(payload)
    require(payload.size >= HEADER_BYTES + 2) { "opus payload has no packet count" }
    val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    b.position(HEADER_BYTES)
    val count = b.short.toInt() and 0xFFFF
    require(count >= 1) { "opus payload has no packets" }
    val lengths = IntArray(count) { b.short.toInt() and 0xFFFF }
    val packets = ArrayList<ByteArray>(count)
    var offset = HEADER_BYTES + 2 + 2 * count
    for (length in lengths) {
      val end = offset + length
      require(length > 0 && end <= payload.size) { "opus payload length mismatch" }
      packets += payload.copyOfRange(offset, end)
      offset = end
    }
    require(offset == payload.size) { "opus payload length mismatch" }
    return SoundFrame(
        header.phoneNs,
        header.elapsedNs,
        header.rateHz,
        header.sampleCount,
        packets = packets,
    )
  }

  /** The header both layouts share. */
  private fun header(payload: ByteArray): SoundHeader {
    require(payload.size >= HEADER_BYTES) { "sound payload is shorter than its header" }
    val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val phoneNs = b.long
    val elapsedNs = b.long
    val rateHz = b.int
    val sampleCount = b.int
    require(rateHz > 0) { "sound payload has no sample rate" }
    require(sampleCount > 0) { "sound payload has no samples" }
    return SoundHeader(phoneNs, elapsedNs, rateHz, sampleCount)
  }

  private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

/** The fields every sound payload starts with. */
private class SoundHeader(
    val phoneNs: Long,
    val elapsedNs: Long,
    val rateHz: Int,
    val sampleCount: Int,
)
