package com.rbmr.taigasleep.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frame payload encoders and decoders for the three streams (see docs/recording-format.md). */
object Payloads {
  const val POLAR_EPOCH_OFFSET_NS = 946_684_800_000_000_000L

  const val FLAG_CONTACT_SUPPORTED = 1
  const val FLAG_CONTACT = 2
  const val FLAG_RR_AVAILABLE = 4

  const val TS_LINEAR = 0
  const val TS_EXPLICIT = 1

  const val MAX_RR = 255
  const val MAX_BATCH = 65535

  /** An encoded payload plus how many values had to be clamped to fit their field. */
  class Encoded(val bytes: ByteArray, val clamped: Int = 0)

  private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

  fun hrFlags(contactSupported: Boolean, contact: Boolean, rrAvailable: Boolean): Int =
      (if (contactSupported) FLAG_CONTACT_SUPPORTED else 0) or
          (if (contact) FLAG_CONTACT else 0) or
          (if (rrAvailable) FLAG_RR_AVAILABLE else 0)

  /** hr and rr values outside u16 (or more than 255 RR intervals) are clamped and counted. */
  fun encodeHr(phoneNs: Long, elapsedNs: Long, hr: Int, flags: Int, rrMs: IntArray): Encoded {
    var clamped = 0
    val nRr = minOf(rrMs.size, MAX_RR)
    if (rrMs.size > MAX_RR) clamped++
    val b = buffer(8 + 8 + 2 + 1 + 1 + 2 * nRr)
    b.putLong(phoneNs).putLong(elapsedNs)
    val hrClamped = hr.coerceIn(0, 0xFFFF)
    if (hrClamped != hr) clamped++
    b.putShort(hrClamped.toShort()).put(flags.toByte()).put(nRr.toByte())
    for (i in 0 until nRr) {
      val v = rrMs[i].coerceIn(0, 0xFFFF)
      if (v != rrMs[i]) clamped++
      b.putShort(v.toShort())
    }
    return Encoded(b.array(), clamped)
  }

  /** True if the mode 0 formula reproduces every timestamp exactly. */
  fun isLinear(t: LongArray): Boolean {
    val n = t.size
    if (n <= 1) return true
    return try {
      val span = Math.subtractExact(t[n - 1], t[0])
      for (i in 0 until n) {
        val offset = Math.multiplyExact(span, i.toLong()) / (n - 1)
        if (t[0] + offset != t[i]) return false
      }
      true
    } catch (_: ArithmeticException) {
      false
    }
  }

  fun reconstruct(first: Long, last: Long, n: Int): LongArray =
      LongArray(n) { i -> if (n == 1) first else first + ((last - first) * i) / (n - 1) }

  fun encodeEcg(phoneNs: Long, elapsedNs: Long, t: LongArray, voltageUv: IntArray): Encoded {
    val n = t.size
    require(n in 1..MAX_BATCH && voltageUv.size == n) { "bad ecg batch" }
    val linear = isLinear(t)
    val b = buffer(16 + 1 + 2 + (if (linear) 16 else 8 * n) + 4 * n)
    b.putLong(phoneNs).putLong(elapsedNs).put((if (linear) TS_LINEAR else TS_EXPLICIT).toByte())
    b.putShort(n.toShort())
    if (linear) b.putLong(t[0]).putLong(t[n - 1]) else t.forEach { b.putLong(it) }
    voltageUv.forEach { b.putInt(it) }
    return Encoded(b.array())
  }

  /** [xyzMg] is interleaved x, y, z; values outside i16 are clamped and counted. */
  fun encodeAcc(phoneNs: Long, elapsedNs: Long, t: LongArray, xyzMg: IntArray): Encoded {
    val n = t.size
    require(n in 1..MAX_BATCH && xyzMg.size == 3 * n) { "bad acc batch" }
    val linear = isLinear(t)
    val b = buffer(16 + 1 + 2 + (if (linear) 16 else 8 * n) + 6 * n)
    b.putLong(phoneNs).putLong(elapsedNs).put((if (linear) TS_LINEAR else TS_EXPLICIT).toByte())
    b.putShort(n.toShort())
    if (linear) b.putLong(t[0]).putLong(t[n - 1]) else t.forEach { b.putLong(it) }
    var clamped = 0
    for (v in xyzMg) {
      val c = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      if (c != v) clamped++
      b.putShort(c.toShort())
    }
    return Encoded(b.array(), clamped)
  }

  data class Hr(
      val phoneNs: Long,
      val elapsedNs: Long,
      val hr: Int,
      val flags: Int,
      val rrMs: IntArray,
  )

  class Batch(
      val phoneNs: Long,
      val elapsedNs: Long,
      val tsMode: Int,
      val t: LongArray,
      /** ECG: microvolts, one per sample. ACC: milli-g, interleaved x, y, z. */
      val values: IntArray,
  )

  fun decodeHr(p: ByteArray): Hr {
    val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
    val phone = b.long
    val elapsed = b.long
    val hr = b.short.toInt() and 0xFFFF
    val flags = b.get().toInt() and 0xFF
    val n = b.get().toInt() and 0xFF
    return Hr(phone, elapsed, hr, flags, IntArray(n) { b.short.toInt() and 0xFFFF })
  }

  fun decodeEcg(p: ByteArray): Batch = decodeBatch(p, perSample = 1, signed16 = false)

  fun decodeAcc(p: ByteArray): Batch = decodeBatch(p, perSample = 3, signed16 = true)

  private fun decodeBatch(p: ByteArray, perSample: Int, signed16: Boolean): Batch {
    val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
    val phone = b.long
    val elapsed = b.long
    val mode = b.get().toInt() and 0xFF
    val n = b.short.toInt() and 0xFFFF
    val t =
        if (mode == TS_LINEAR) {
          val first = b.long
          val last = b.long
          reconstruct(first, last, n)
        } else {
          LongArray(n) { b.long }
        }
    val values =
        IntArray(n * perSample) { if (signed16) b.short.toInt() else b.int }
    return Batch(phone, elapsed, mode, t, values)
  }
}
