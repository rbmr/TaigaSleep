package com.rbmr.taigasleep.tracker

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One encoded payload plus how many values had to be clamped to fit their field. */
class Encoded(val bytes: ByteArray, val clamped: Int = 0)

/** Heart-rate sample, in the units every decoder uses. */
class HrSample(
    val hr: Int,
    val contactSupported: Boolean,
    val contact: Boolean,
    val rrAvailable: Boolean,
    val rrMs: IntArray,
)

/** One ECG batch: Polar-epoch nanosecond timestamps and raw microvolts. */
class EcgBatch(val timestampsNs: LongArray, val voltageUv: IntArray)

/** One ACC batch: Polar-epoch nanosecond timestamps and raw milli-g, interleaved x, y, z. */
class AccBatch(val timestampsNs: LongArray, val xyzMg: IntArray)

/** A batch of per-sample values over one common timeline. */
class ValueBatch(val timestampsNs: LongArray, val values: FloatArray)

/** Nanoseconds from the Unix epoch to the Polar epoch (2000-01-01). */
const val POLAR_EPOCH_OFFSET_NS = 946_684_800_000_000_000L

/** Codec names, as `meta.json` stores them. */
object Codec {
  const val POLAR_HR = "polar-hr-v1"
  const val POLAR_ECG = "polar-ecg-v1"
  const val POLAR_ECG_V2 = "polar-ecg-v2"
  const val POLAR_ACC = "polar-acc-v1"
  const val POLAR_PPG = "polar-ppg-v1"
  const val POLAR_PPI = "polar-ppi-v1"
  const val POLAR_GYRO = "polar-gyro-v1"
  const val POLAR_MAG = "polar-mag-v1"
  const val BLE_HRS = "ble-hrs-v1"
}

private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

/** The Polar payloads of `docs/recording-format.md`; the same bytes the H10 always wrote. */
object PolarCodec {
  const val FLAG_CONTACT_SUPPORTED = 1
  const val FLAG_CONTACT = 2
  const val FLAG_RR_AVAILABLE = 4

  const val TS_LINEAR = 0
  const val TS_EXPLICIT = 1

  /**
   * A set bit in the ts_mode byte of an ECG batch says its values are 16-bit integers; a clear
   * bit says 32-bit, which is how every recording made before the flag was added is stored. The
   * timestamp mode is the rest of the byte. See `docs/recording-format.md`.
   */
  const val TS_VALUES_I16 = 1 shl 1

  const val MAX_RR = 255
  const val MAX_BATCH = 65535

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

  /**
   * One ECG batch in the `polar-ecg-v2` payload layout. Values are stored as 16-bit integers
   * when every sample of the batch fits, and as 32-bit otherwise. The choice is flagged in the
   * batch's ts_mode, so a reader needs no guess. Nothing is clamped: the sensor's limits are far
   * inside i16, and a batch that reaches beyond it is stored 32-bit so that it stays exact.
   */
  fun encodeEcg(phoneNs: Long, elapsedNs: Long, t: LongArray, voltageUv: IntArray): Encoded {
    val n = t.size
    require(n in 1..MAX_BATCH && voltageUv.size == n) { "bad ecg batch" }
    val linear = isLinear(t)
    val valuesI16 = voltageUv.all { it >= Short.MIN_VALUE && it <= Short.MAX_VALUE }
    val valueBytes = if (valuesI16) 2 else 4
    val mode =
        (if (linear) TS_LINEAR else TS_EXPLICIT) or (if (valuesI16) TS_VALUES_I16 else 0)
    val b = buffer(16 + 1 + 2 + (if (linear) 16 else 8 * n) + valueBytes * n)
    b.putLong(phoneNs).putLong(elapsedNs).put(mode.toByte())
    b.putShort(n.toShort())
    if (linear) b.putLong(t[0]).putLong(t[n - 1]) else t.forEach { b.putLong(it) }
    if (valuesI16) {
      voltageUv.forEach { b.putShort(it.toShort()) }
    } else {
      voltageUv.forEach { b.putInt(it) }
    }
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

  /**
   * PPG: one channel per sample, the SDK's status bits on each. The sample rate is the stream's
   * negotiated rate; a sample's own timestamp is the Polar-epoch one.
   */
  fun encodePpg(
      phoneNs: Long,
      elapsedNs: Long,
      t: LongArray,
      channel: IntArray,
      status: IntArray,
  ): Encoded {
    val n = t.size
    require(n in 1..MAX_BATCH && channel.size == n && status.size == n) { "bad ppg batch" }
    val b = buffer(16 + 2 + n * (8 + 4 + 2))
    b.putLong(phoneNs).putLong(elapsedNs).putShort(n.toShort())
    for (i in 0 until n) {
      b.putLong(t[i]).putInt(channel[i]).putShort(status[i].toShort())
    }
    return Encoded(b.array())
  }

  /** Pulse-to-pulse intervals, one SDK sample each: interval, error estimate, hr and flags. */
  fun encodePpi(
      phoneNs: Long,
      elapsedNs: Long,
      t: LongArray,
      ppiMs: IntArray,
      errorMs: IntArray,
      hr: IntArray,
      flags: IntArray,
  ): Encoded {
    val n = t.size
    require(
        n in 1..MAX_BATCH &&
            ppiMs.size == n &&
            errorMs.size == n &&
            hr.size == n &&
            flags.size == n
    ) { "bad ppi batch" }
    val b = buffer(16 + 2 + n * (8 + 2 + 2 + 1 + 1))
    b.putLong(phoneNs).putLong(elapsedNs).putShort(n.toShort())
    for (i in 0 until n) {
      b.putLong(t[i])
      b.putShort(ppiMs[i].coerceIn(0, 0xFFFF).toShort())
      b.putShort(errorMs[i].coerceIn(0, 0xFFFF).toShort())
      b.put(hr[i].coerceIn(0, 0xFF).toByte())
      b.put(flags[i].toByte())
    }
    return Encoded(b.array())
  }

  /** Gyroscope and magnetometer: three float components per Polar-epoch sample. */
  fun encodeVector(
      phoneNs: Long,
      elapsedNs: Long,
      t: LongArray,
      xyz: FloatArray,
  ): Encoded {
    val n = t.size
    require(n in 1..MAX_BATCH && xyz.size == 3 * n) { "bad vector batch" }
    val b = buffer(16 + 2 + n * (8 + 12))
    b.putLong(phoneNs).putLong(elapsedNs).putShort(n.toShort())
    for (i in 0 until n) {
      b.putLong(t[i]).putFloat(xyz[i * 3]).putFloat(xyz[i * 3 + 1]).putFloat(xyz[i * 3 + 2])
    }
    return Encoded(b.array())
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
      /** Timestamp mode: [TS_LINEAR] or [TS_EXPLICIT]. */
      val mode: Int,
      val t: LongArray,
      /** ECG: microvolts, one per sample. ACC: milli-g, interleaved x, y, z. */
      val values: IntArray,
      /** True when the payload stored its values as 16-bit integers. */
      val valuesI16: Boolean = false,
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

  fun decodeEcg(p: ByteArray): Batch = decodeBatch(p, perSample = 1, fixedValuesI16 = null)

  fun decodeAcc(p: ByteArray): Batch = decodeBatch(p, perSample = 3, fixedValuesI16 = true)

  /**
   * [fixedValuesI16] is the width of a stream that has no width flag (ACC); ECG passes null and
   * takes the width from [TS_VALUES_I16].
   */
  private fun decodeBatch(p: ByteArray, perSample: Int, fixedValuesI16: Boolean?): Batch {
    val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
    val phone = b.long
    val elapsed = b.long
    val rawMode = b.get().toInt() and 0xFF
    val flagsI16 = rawMode and TS_VALUES_I16 != 0
    require(flagsI16.not() || fixedValuesI16 == null) { "16-bit values in a stream that has none" }
    val valuesI16 = fixedValuesI16 ?: flagsI16
    val mode = rawMode and TS_VALUES_I16.inv()
    require(mode == TS_LINEAR || mode == TS_EXPLICIT) { "unknown ts mode $mode" }
    val n = b.short.toInt() and 0xFFFF
    val t =
        if (mode == TS_LINEAR) {
          val first = b.long
          val last = b.long
          reconstruct(first, last, n)
        } else {
          LongArray(n) { b.long }
        }
    val values = IntArray(n * perSample) { if (valuesI16) b.short.toInt() else b.int }
    return Batch(phone, elapsed, mode, t, values, valuesI16)
  }
}

/**
 * The Bluetooth Heart Rate Measurement characteristic (0x2A37) stored as received under the
 * service's own service (0x180D). The payload keeps the notification bytes verbatim, so every flag
 * combination survives to the worker unchanged.
 */
object BleHrsCodec {
  fun encode(phoneNs: Long, elapsedNs: Long, measurement: ByteArray): Encoded {
    val b = buffer(8 + 8 + 2 + measurement.size)
    b.putLong(phoneNs).putLong(elapsedNs)
    b.putShort(measurement.size.toShort())
    b.put(measurement)
    return Encoded(b.array())
  }

  class Measurement(
      val hr: Int,
      val contactSupported: Boolean,
      val contact: Boolean,
      val rrMs: IntArray,
      val energyExpended: Int?,
      /** True when the interval list was present, even if it was empty. */
      val rrPresent: Boolean,
  )

  fun decode(payload: ByteArray): Measurement {
    val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    b.long
    b.long
    val n = b.short.toInt() and 0xFFFF
    val value = ByteArray(n)
    b.get(value)
    return decodeMeasurement(value)
  }

  /** Decodes the characteristic value itself, which is what the shared fixtures hold. */
  fun decodeMeasurement(value: ByteArray): Measurement {
    require(value.isNotEmpty()) { "empty heart rate measurement" }
    val flags = value[0].toInt() and 0xFF
    val wide = flags and 0x01 != 0
    val contactSupported = flags and 0x04 != 0
    val contact = flags and 0x02 != 0
    val energy = flags and 0x08 != 0
    val rrPresent = flags and 0x10 != 0
    val b = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
    b.get()
    val hr = if (wide) b.short.toInt() and 0xFFFF else b.get().toInt() and 0xFF
    var energyExpended: Int? = null
    if (energy) energyExpended = b.short.toInt() and 0xFFFF
    val rr = ArrayList<Int>()
    if (rrPresent) {
      while (b.remaining() >= 2) {
        // Units of 1/1024 s; round to the nearest millisecond like the worker does.
        val raw = b.short.toInt() and 0xFFFF
        rr += (raw * 1000 + 512) / 1024
      }
    }
    return Measurement(hr, contactSupported, contact, rr.toIntArray(), energyExpended, rrPresent)
  }
}
