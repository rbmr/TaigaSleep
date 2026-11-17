package com.rbmr.taigasleep.recording.store

import com.rbmr.taigasleep.tracker.Encoded
import com.rbmr.taigasleep.tracker.PolarCodec

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
