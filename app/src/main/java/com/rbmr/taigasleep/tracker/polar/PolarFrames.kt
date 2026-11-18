@file:Suppress("TooManyFunctions")

package com.rbmr.taigasleep.tracker.polar

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarEcgDataSample
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarPpiData
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.PolarCodec
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener

/**
 * The one place Polar SDK samples become recording frames. Both drivers hand their live and
 * offline batches here, so a stream's bytes and its batch split are the same whichever path
 * delivered it.
 */
internal object PolarFrames {
  /** One heart rate frame per SDK sample, all carrying the stamp of the callback that arrived. */
  fun heartRate(stamp: Stamp, samples: List<PolarHrData.PolarHrSample>): List<TrackerFrame> {
    val frames = ArrayList<TrackerFrame>(samples.size)
    for (s in samples) {
      val enc =
          PolarCodec.encodeHr(
              stamp.phoneNs,
              stamp.elapsedNs,
              s.hr,
              PolarCodec.hrFlags(s.contactStatusSupported, s.contactStatus, s.rrAvailable),
              s.rrsMs.toIntArray(),
          )
      frames += TrackerFrame("hr", stamp, enc.bytes, enc.clamped)
    }
    return frames
  }

  /**
   * One heart rate frame per beat of an H10 exercise, timed forward from its own start. An
   * interval of 0 or less produces no frame, so the gap stays a gap.
   */
  fun heartRateFromIntervals(
      startUtcMs: Long,
      intervalsMs: List<Int>,
      clock: Clock,
  ): List<TrackerFrame> {
    val frames = ArrayList<TrackerFrame>(intervalsMs.size)
    var timeMs = startUtcMs
    for (rr in intervalsMs) {
      if (rr <= 0) continue
      timeMs += rr
      val stamp = Stamp(timeMs * 1_000_000L, clock.elapsedNs())
      val enc =
          PolarCodec.encodeHr(
              stamp.phoneNs,
              stamp.elapsedNs,
              60_000 / rr,
              PolarCodec.hrFlags(contactSupported = false, contact = false, rrAvailable = true),
              intArrayOf(rr),
          )
      frames += TrackerFrame("hr", stamp, enc.bytes, enc.clamped)
    }
    return frames
  }

  /** One heart rate frame per second of an offline recording, counted from its start. */
  fun heartRateFromRecording(
      startUtcMs: Long,
      samples: List<PolarHrData.PolarHrSample>,
      clock: Clock,
  ): List<TrackerFrame> =
      samples.mapIndexed { index, s ->
        val stamp = Stamp((startUtcMs + index * 1000L) * 1_000_000L, clock.elapsedNs())
        val enc =
            PolarCodec.encodeHr(
                stamp.phoneNs,
                stamp.elapsedNs,
                s.hr,
                PolarCodec.hrFlags(s.contactStatusSupported, s.contactStatus, s.rrAvailable),
                s.rrsMs.toIntArray(),
            )
        TrackerFrame("hr", stamp, enc.bytes, enc.clamped)
      }

  /** ECG batches; samples that are not voltage samples are dropped first. */
  fun ecg(stamp: Stamp, samples: List<PolarEcgDataSample>): List<TrackerFrame> {
    val ecg = samples.filterIsInstance<EcgSample>()
    if (ecg.isEmpty()) return emptyList()
    val t = LongArray(ecg.size) { ecg[it].timeStamp }
    val v = IntArray(ecg.size) { ecg[it].voltage }
    return batches(ecg.size) { from, to ->
      val enc =
          PolarCodec.encodeEcg(
              stamp.phoneNs,
              stamp.elapsedNs,
              t.copyOfRange(from, to),
              v.copyOfRange(from, to),
          )
      TrackerFrame("ecg", stamp, enc.bytes, enc.clamped)
    }
  }

  fun accelerometer(
      stamp: Stamp,
      samples: List<PolarAccelerometerData.PolarAccelerometerDataSample>,
  ): List<TrackerFrame> {
    if (samples.isEmpty()) return emptyList()
    val t = LongArray(samples.size) { samples[it].timeStamp }
    val xyz = IntArray(samples.size * 3)
    for (i in samples.indices) {
      xyz[i * 3] = samples[i].x
      xyz[i * 3 + 1] = samples[i].y
      xyz[i * 3 + 2] = samples[i].z
    }
    return batches(samples.size) { from, to ->
      val enc =
          PolarCodec.encodeAcc(
              stamp.phoneNs,
              stamp.elapsedNs,
              t.copyOfRange(from, to),
              xyz.copyOfRange(from * 3, to * 3),
          )
      TrackerFrame("acc", stamp, enc.bytes, enc.clamped)
    }
  }

  fun ppi(stamp: Stamp, samples: List<PolarPpiData.PolarPpiSample>): List<TrackerFrame> {
    if (samples.isEmpty()) return emptyList()
    return batches(samples.size) { from, to ->
      val enc =
          PolarCodec.encodePpi(
              stamp.phoneNs,
              stamp.elapsedNs,
              LongArray(to - from) { samples[from + it].timeStamp.toLong() },
              IntArray(to - from) { samples[from + it].ppi },
              IntArray(to - from) { samples[from + it].errorEstimate },
              IntArray(to - from) { samples[from + it].hr },
              IntArray(to - from) { ppiFlags(samples[from + it]) },
          )
      TrackerFrame("ppi", stamp, enc.bytes, enc.clamped)
    }
  }

  /** Pulse intervals of an offline recording, placed on their accumulated beats from its start. */
  fun ppiFromRecording(
      startUtcMs: Long,
      samples: List<PolarPpiData.PolarPpiSample>,
      elapsedNs: Long,
  ): List<TrackerFrame> {
    if (samples.isEmpty()) return emptyList()
    val intervals = IntArray(samples.size) { samples[it].ppi }
    val times = ppiTimestampsNs(startUtcMs, intervals)
    val stamp = Stamp(startUtcMs * 1_000_000L, elapsedNs)
    return batches(samples.size) { from, to ->
      val enc =
          PolarCodec.encodePpi(
              stamp.phoneNs,
              stamp.elapsedNs,
              times.copyOfRange(from, to),
              intervals.copyOfRange(from, to),
              IntArray(to - from) { samples[from + it].errorEstimate },
              IntArray(to - from) { samples[from + it].hr },
              IntArray(to - from) { ppiFlags(samples[from + it]) },
          )
      TrackerFrame("ppi", stamp, enc.bytes, enc.clamped)
    }
  }

  fun ppg(stamp: Stamp, samples: List<PolarPpgData.PolarPpgSample>): List<TrackerFrame> {
    if (samples.isEmpty()) return emptyList()
    val t = LongArray(samples.size) { samples[it].timeStamp }
    return batches(samples.size) { from, to ->
      val channels = Array(to - from) { samples[from + it].channelSamples.toIntArray() }
      val status =
          IntArray(to - from) {
            samples[from + it].statusBits.fold(0) { acc, bit -> acc or bit }
          }
      val enc =
          PolarCodec.encodePpg(
              stamp.phoneNs,
              stamp.elapsedNs,
              t.copyOfRange(from, to),
              channels,
              status,
          )
      TrackerFrame("ppg", stamp, enc.bytes, enc.clamped)
    }
  }

  private fun ppiFlags(sample: PolarPpiData.PolarPpiSample): Int =
      (if (sample.skinContactSupported) 1 else 0) or
          (if (sample.skinContactStatus) 2 else 0) or
          (if (sample.blockerBit) 4 else 0)

  /** Splits one SDK batch into the frames the payload can hold, in order. */
  private fun batches(
      size: Int,
      build: (from: Int, to: Int) -> TrackerFrame,
  ): List<TrackerFrame> {
    val frames = ArrayList<TrackerFrame>()
    var from = 0
    while (from < size) {
      val to = minOf(from + PolarCodec.MAX_BATCH, size)
      frames += build(from, to)
      from = to
    }
    return frames
  }
}

/**
 * The one settings negotiation for Polar streams: what the strap offers, the values the driver
 * wants where it offers them, and the keys the recording stores. The keys are the ones the H10
 * has always written, for every Polar driver.
 */
internal object PolarSettings {
  /** The highest setting the strap offers for a stream it is only asked to store. */
  suspend fun best(
      seam: PolarSeam,
      id: String,
      type: PolarDeviceDataType,
  ): PolarSensorSetting =
      PolarSensorSetting(choose(seam.streamSettings(id, type), emptyMap()))

  suspend fun negotiate(
      seam: PolarSeam,
      id: String,
      type: PolarDeviceDataType,
      wanted: Map<SettingType, Int>,
      listener: TrackerListener,
  ): PolarSensorSetting {
    val available = seam.streamSettings(id, type)
    val chosen = choose(available, wanted)
    if (chosen.any { (setting, value) -> wanted[setting] != null && wanted[setting] != value }) {
      listener.onError("$type settings differ from the wanted ones: $chosen")
    }
    listener.onNegotiated(
        streamName(type),
        chosen[SettingType.SAMPLE_RATE]?.toDouble(),
        chosen
            .mapKeys { (setting, _) -> settingName(setting) }
            .mapValues { (_, value) -> value.toString() },
    )
    return PolarSensorSetting(chosen)
  }

  private fun choose(
      available: PolarSensorSetting,
      wanted: Map<SettingType, Int>,
  ): Map<SettingType, Int> =
      available.settings.mapValues { (setting, values) ->
        wanted[setting]?.takeIf { it in values } ?: values.max()
      }

  /** The stream a data type streams into; each Polar driver offers one stream per type. */
  private fun streamName(type: PolarDeviceDataType): String =
      when (type) {
        PolarDeviceDataType.HR -> "hr"
        PolarDeviceDataType.ECG -> "ecg"
        PolarDeviceDataType.ACC -> "acc"
        PolarDeviceDataType.PPG -> "ppg"
        PolarDeviceDataType.PPI -> "ppi"
        else -> type.name.lowercase()
      }

  private fun settingName(type: SettingType): String =
      when (type) {
        SettingType.SAMPLE_RATE -> "rate_hz"
        SettingType.RESOLUTION -> "resolution_bits"
        SettingType.RANGE -> "range_g"
        else -> type.name.lowercase()
      }
}

/**
 * The timestamps of offline pulse intervals, in the Polar-epoch nanoseconds the PPI codec uses:
 * each sample sits one interval after the previous one, starting from the entry's start time.
 */
internal fun ppiTimestampsNs(startUtcMs: Long, intervalsMs: IntArray): LongArray {
  var timeMs = startUtcMs
  return LongArray(intervalsMs.size) {
    timeMs += intervalsMs[it]
    timeMs * 1_000_000L
  }
}
