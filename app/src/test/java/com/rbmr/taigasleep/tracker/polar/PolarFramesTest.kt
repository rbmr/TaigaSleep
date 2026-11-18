@file:Suppress("TooManyFunctions")

package com.rbmr.taigasleep.tracker.polar

import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarPpiData
import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.PolarCodec
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.TrackerFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bytes and timings the Polar drivers wrote before their sample handling was shared. The
 * literals are H10 or Verity/Loop payloads, so a change in a builder shows up here.
 */
class PolarFramesTest {
  private val clock = TestClock()
  private val stamp = Stamp(1_792_798_200_123_456_789L, 5_123_456_789L)

  private val hr =
      PolarHrData.PolarHrSample(62, 62, 0, listOf(960), listOf(960), true, true, true)

  private val ecgTwo =
      listOf(
          EcgSample(946_684_800_000_000_000L, 1000),
          EcgSample(946_684_800_000_000_000L + 7_692_307, -1000),
      )

  private val accTwo =
      listOf(
          PolarAccelerometerData.PolarAccelerometerDataSample(
              946_684_800_000_000_000L,
              1,
              -2,
              3,
          ),
          PolarAccelerometerData.PolarAccelerometerDataSample(
              946_684_800_000_000_000L + 20_000_000L,
              4,
              5,
              -6,
          ),
      )

  private val ppiTwo =
      listOf(
          PolarPpiData.PolarPpiSample(900, 5, 66, false, true, true, 1_000_000_000uL),
          PolarPpiData.PolarPpiSample(950, 2, 63, false, false, true, 1_950_000_000uL),
      )

  private val ppgTwo =
      listOf(
          PolarPpgData.PolarPpgSample(946_684_800_000_000_000L, listOf(11, 12), listOf(0, 1)),
          PolarPpgData.PolarPpgSample(
              946_684_800_000_000_000L + 18_000_000L,
              listOf(13, 14),
              listOf(2, 4),
          ),
      )

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  @Test
  fun `the heart rate builder writes the h10 payload`() {
    val samples =
        listOf(
            hr,
            PolarHrData.PolarHrSample(
                61,
                61,
                0,
                listOf(980, 990),
                listOf(980, 990),
                true,
                false,
                true,
            ),
        )
    val frames = PolarFrames.heartRate(stamp, samples)

    assertEquals(2, frames.size)
    val frame = frames.first()
    assertEquals("hr", frame.stream)
    assertEquals(stamp, frame.stamp)
    assertEquals(0, frame.clamped)
    assertEquals("157d51e0764ce11815bf6131010000003e000701c003", hex(frame.payload))
    assertFrames(oldH10HeartRate(stamp, samples), frames)
  }

  @Test
  fun `the heart rate builder writes one frame per sample`() {
    val frames = PolarFrames.heartRate(stamp, listOf(hr, hr))

    assertEquals(2, frames.size)
    assertArrayEquals(frames[0].payload, frames[1].payload)
    assertEquals(frames[0].stamp, frames[1].stamp)
  }

  @Test
  fun `the ecg builder writes the h10 payload`() {
    val frame = PolarFrames.ecg(stamp, ecgTwo).single()

    assertEquals("ecg", frame.stream)
    assertEquals(stamp, frame.stamp)
    assertEquals(
        "157d51e0764ce11815bf61310100000002020000004352cf4c230d1360b852cf4c230de80318fc",
        hex(frame.payload),
    )
    assertFrames(oldH10Ecg(stamp, ecgTwo), PolarFrames.ecg(stamp, ecgTwo))
  }

  @Test
  fun `the accelerometer builder writes the h10 payload`() {
    val frame = PolarFrames.accelerometer(stamp, accTwo).single()

    assertEquals("acc", frame.stream)
    assertEquals(stamp, frame.stamp)
    assertEquals(
        "157d51e0764ce11815bf61310100000000020000004352cf4c230d002d7453cf4c230d" +
            "0100feff030004000500faff",
        hex(frame.payload),
    )
    assertFrames(oldH10Acc(stamp, accTwo), PolarFrames.accelerometer(stamp, accTwo))
  }

  @Test
  fun `the offline heart rate builders write the old beats`() {
    val start = 1_792_798_200_000L
    val fromIntervals = PolarFrames.heartRateFromIntervals(start, listOf(1000, 900), clock)
    assertEquals(2, fromIntervals.size)
    assertEquals((start + 1000L) * 1_000_000L, fromIntervals[0].stamp.phoneNs)
    assertEquals((start + 1900L) * 1_000_000L, fromIntervals[1].stamp.phoneNs)
    assertEquals(clock.elapsedNs(), fromIntervals[1].stamp.elapsedNs)
    assertEquals(
        "007a9014774ce11800f2052a010000003c000401e803",
        hex(fromIntervals[0].payload),
    )
    assertEquals(
        "0063354a774ce11800f2052a01000000420004018403",
        hex(fromIntervals[1].payload),
    )
    assertFrames(oldH10Intervals(start, listOf(1000, 900), clock), fromIntervals)

    val fromRecording = PolarFrames.heartRateFromRecording(start, listOf(hr, hr), clock)
    assertEquals(2, fromRecording.size)
    assertEquals(start * 1_000_000L, fromRecording[0].stamp.phoneNs)
    assertEquals((start + 1000L) * 1_000_000L, fromRecording[1].stamp.phoneNs)
    assertEquals(
        "00b0f5d8764ce11800f2052a010000003e000701c003",
        hex(fromRecording[0].payload),
    )
    assertFrames(oldGenericRecordingHr(start, listOf(hr, hr), clock), fromRecording)
  }

  @Test
  fun `an interval of zero or less produces no beat`() {
    val frames = PolarFrames.heartRateFromIntervals(1_000L, listOf(0, -5, 1000), clock)

    assertEquals(1, frames.size)
  }

  @Test
  fun `the ppi builder writes the generic live payload`() {
    val frame = PolarFrames.ppi(stamp, ppiTwo).single()

    assertEquals("ppi", frame.stream)
    assertEquals(stamp, frame.stamp)
    assertEquals(
        "157d51e0764ce11815bf613101000000020000ca9a3b0000000084030500420380a33a7400" +
            "000000b60302003f01",
        hex(frame.payload),
    )
    assertFrames(listOf(oldGenericPpi(stamp, ppiTwo)), listOf(frame))
  }

  @Test
  fun `the offline ppi builder writes the generic payload`() {
    val frame =
        PolarFrames.ppiFromRecording(1_792_798_200_000L, ppiTwo, 5_123_456_789L).single()

    assertEquals(Stamp(1_792_798_200_000L * 1_000_000L, 5_123_456_789L), frame.stamp)
    assertEquals(
        "00b0f5d8764ce11815bf613101000000020000999a0e774ce11884030500420380723a4777" +
            "4ce118b60302003f01",
        hex(frame.payload),
    )
    assertFrames(
        listOf(oldGenericRecordingPpi(1_792_798_200_000L, ppiTwo, 5_123_456_789L)),
        listOf(frame),
    )
  }

  @Test
  fun `the ppg builder writes the generic payload`() {
    val frame = PolarFrames.ppg(stamp, ppgTwo).single()

    assertEquals("ppg", frame.stream)
    assertEquals(stamp, frame.stamp)
    assertEquals(
        "157d51e0764ce11815bf613101000000020000004352cf4c230d020b0000000c0000000100" +
            "80a85553cf4c230d020d0000000e0000000600",
        hex(frame.payload),
    )
    assertFrames(listOf(oldGenericPpg(stamp, ppgTwo)), listOf(frame))
  }

  @Test
  fun `a long ecg batch is split like the h10 split it`() {
    val samples = ecgSamples(PolarCodec.MAX_BATCH + 2)

    val frames = PolarFrames.ecg(stamp, samples)

    assertEquals(2, frames.size)
    assertEquals(stamp, frames[1].stamp)
    assertFrames(oldH10Ecg(stamp, samples), frames)
  }

  @Test
  fun `a long accelerometer batch is split like the h10 split it`() {
    val samples = accSamples(PolarCodec.MAX_BATCH + 1)

    val frames = PolarFrames.accelerometer(stamp, samples)

    assertEquals(2, frames.size)
    assertFrames(oldH10Acc(stamp, samples), frames)
  }

  @Test
  fun `a long ppi batch is split into payload-sized frames`() {
    val samples = ppiSamples(PolarCodec.MAX_BATCH + 2)

    val frames = PolarFrames.ppi(stamp, samples)

    assertEquals(2, frames.size)
    assertArrayEquals(oldPpiSlice(stamp, samples, 0, PolarCodec.MAX_BATCH), frames[0].payload)
    assertArrayEquals(
        oldPpiSlice(stamp, samples, PolarCodec.MAX_BATCH, samples.size),
        frames[1].payload,
    )
  }

  @Test
  fun `a long ppg batch is split into payload-sized frames`() {
    val samples = ppgSamples(PolarCodec.MAX_BATCH + 2)

    val frames = PolarFrames.ppg(stamp, samples)

    assertEquals(2, frames.size)
    assertArrayEquals(oldPpgSlice(stamp, samples, 0, PolarCodec.MAX_BATCH), frames[0].payload)
    assertArrayEquals(
        oldPpgSlice(stamp, samples, PolarCodec.MAX_BATCH, samples.size),
        frames[1].payload,
    )
  }

  private fun ecgSamples(count: Int): List<EcgSample> =
      List(count) { EcgSample(946_684_800_000_000_000L + it * 7_692_307L, 1000) }

  private fun accSamples(count: Int): List<PolarAccelerometerData.PolarAccelerometerDataSample> =
      List(count) {
        PolarAccelerometerData.PolarAccelerometerDataSample(
            946_684_800_000_000_000L + it * 20_000_000L,
            it,
            -it,
            it + 1,
        )
      }

  private fun ppiSamples(count: Int): List<PolarPpiData.PolarPpiSample> =
      List(count) {
        PolarPpiData.PolarPpiSample(900, 5, 66, false, true, true, (it * 1_000_000L).toULong())
      }

  private fun ppgSamples(count: Int): List<PolarPpgData.PolarPpgSample> =
      List(count) {
        PolarPpgData.PolarPpgSample(
            946_684_800_000_000_000L + it * 18_000_000L,
            listOf(11, 12),
            listOf(0, 1),
        )
      }

  private fun assertFrames(expected: List<TrackerFrame>, actual: List<TrackerFrame>) {
    assertEquals(expected.size, actual.size)
    expected.zip(actual).forEach { (e, a) ->
      assertEquals(e.stream, a.stream)
      assertEquals(e.stamp, a.stamp)
      assertEquals(e.clamped, a.clamped)
      assertArrayEquals(e.payload, a.payload)
    }
  }

  /** The H10's live heart rate loop before the builders were shared. */
  private fun oldH10HeartRate(
      stamp: Stamp,
      samples: List<PolarHrData.PolarHrSample>,
  ): List<TrackerFrame> {
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

  /** The H10's old ECG loop: one frame per payload-sized slice, all with the callback's stamp. */
  private fun oldH10Ecg(stamp: Stamp, samples: List<EcgSample>): List<TrackerFrame> {
    if (samples.isEmpty()) return emptyList()
    val t = LongArray(samples.size) { samples[it].timeStamp }
    val v = IntArray(samples.size) { samples[it].voltage }
    val frames = ArrayList<TrackerFrame>()
    for (from in t.indices step PolarCodec.MAX_BATCH) {
      val to = minOf(from + PolarCodec.MAX_BATCH, t.size)
      val enc =
          PolarCodec.encodeEcg(
              stamp.phoneNs,
              stamp.elapsedNs,
              t.copyOfRange(from, to),
              v.copyOfRange(from, to),
          )
      frames += TrackerFrame("ecg", stamp, enc.bytes, enc.clamped)
    }
    return frames
  }

  /** The H10's old accelerometer loop, x, y and z interleaved in one batch per slice. */
  private fun oldH10Acc(
      stamp: Stamp,
      samples: List<PolarAccelerometerData.PolarAccelerometerDataSample>,
  ): List<TrackerFrame> {
    if (samples.isEmpty()) return emptyList()
    val t = LongArray(samples.size) { samples[it].timeStamp }
    val xyz =
        IntArray(samples.size * 3) {
          when (it % 3) {
            0 -> samples[it / 3].x
            1 -> samples[it / 3].y
            else -> samples[it / 3].z
          }
        }
    val frames = ArrayList<TrackerFrame>()
    for (from in t.indices step PolarCodec.MAX_BATCH) {
      val to = minOf(from + PolarCodec.MAX_BATCH, t.size)
      val enc =
          PolarCodec.encodeAcc(
              stamp.phoneNs,
              stamp.elapsedNs,
              t.copyOfRange(from, to),
              xyz.copyOfRange(from * 3, to * 3),
          )
      frames += TrackerFrame("acc", stamp, enc.bytes, enc.clamped)
    }
    return frames
  }

  /** The H10's old offline loop: one frame per beat, from the exercise's start. */
  private fun oldH10Intervals(
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

  /** The generic driver's old offline heart rate loop. */
  private fun oldGenericRecordingHr(
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

  /** The generic driver's old live PPI payload: the whole callback in one frame. */
  private fun oldGenericPpi(
      stamp: Stamp,
      samples: List<PolarPpiData.PolarPpiSample>,
  ): TrackerFrame =
      TrackerFrame("ppi", stamp, oldPpiSlice(stamp, samples, 0, samples.size), 0)

  /** The generic driver's old offline PPI payload, on its accumulated beats. */
  private fun oldGenericRecordingPpi(
      startUtcMs: Long,
      samples: List<PolarPpiData.PolarPpiSample>,
      elapsedNs: Long,
  ): TrackerFrame {
    val stamp = Stamp(startUtcMs * 1_000_000L, elapsedNs)
    val intervals = IntArray(samples.size) { samples[it].ppi }
    val enc =
        PolarCodec.encodePpi(
            stamp.phoneNs,
            stamp.elapsedNs,
            ppiTimestampsNs(startUtcMs, intervals),
            intervals,
            IntArray(samples.size) { samples[it].errorEstimate },
            IntArray(samples.size) { samples[it].hr },
            IntArray(samples.size) {
              val sample = samples[it]
              (if (sample.skinContactSupported) 1 else 0) or
                  (if (sample.skinContactStatus) 2 else 0) or
                  (if (sample.blockerBit) 4 else 0)
            },
        )
    return TrackerFrame("ppi", stamp, enc.bytes, enc.clamped)
  }

  /** The generic driver's old live PPG payload: the whole callback in one frame. */
  private fun oldGenericPpg(
      stamp: Stamp,
      samples: List<PolarPpgData.PolarPpgSample>,
  ): TrackerFrame =
      TrackerFrame("ppg", stamp, oldPpgSlice(stamp, samples, 0, samples.size), 0)

  private fun oldPpiSlice(
      stamp: Stamp,
      samples: List<PolarPpiData.PolarPpiSample>,
      from: Int,
      to: Int,
  ): ByteArray =
      PolarCodec.encodePpi(
              stamp.phoneNs,
              stamp.elapsedNs,
              LongArray(to - from) { samples[from + it].timeStamp.toLong() },
              IntArray(to - from) { samples[from + it].ppi },
              IntArray(to - from) { samples[from + it].errorEstimate },
              IntArray(to - from) { samples[from + it].hr },
              IntArray(to - from) {
                val sample = samples[from + it]
                (if (sample.skinContactSupported) 1 else 0) or
                    (if (sample.skinContactStatus) 2 else 0) or
                    (if (sample.blockerBit) 4 else 0)
              },
          )
          .bytes

  private fun oldPpgSlice(
      stamp: Stamp,
      samples: List<PolarPpgData.PolarPpgSample>,
      from: Int,
      to: Int,
  ): ByteArray =
      PolarCodec.encodePpg(
              stamp.phoneNs,
              stamp.elapsedNs,
              LongArray(to - from) { samples[from + it].timeStamp },
              Array(to - from) { samples[from + it].channelSamples.toIntArray() },
              IntArray(to - from) {
                samples[from + it].statusBits.fold(0) { acc, bit -> acc or bit }
              },
          )
          .bytes
}
