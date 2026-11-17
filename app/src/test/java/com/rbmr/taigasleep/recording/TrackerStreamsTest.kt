package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStream
import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.FakeTracker
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.ReplayTracker
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The recorder takes a tracker's own stream list and the settings it negotiated. A fake whose
 * settings differ from the requested ones must still leave a meta.json that says what was really
 * recorded, and a replay of that directory must hand the same frames back.
 */
class TrackerStreamsTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()
  private val monitor =
      object : SampleMonitor {
        override fun onHr(stamp: Stamp, sample: HrSample) {}

        override fun onEcg(stamp: Stamp, batch: EcgBatch) {}

        override fun onAcc(stamp: Stamp, batch: AccBatch) {}
      }

  @Test
  fun aFakeTrackersNegotiatedSettingsReachMetaJson() {
    val sensor = FakeTracker(clock, autoRun = false, disconnectEveryMs = 0)
    sensor.connect()
    sensor.pump(clock.elapsedNs())
    val streams =
        sensor.streams.take(3).map {
          RecordingStream(it.id, it.type, it.codec, it.file, it.ratesHz.firstOrNull())
        }
    val meta =
        RecordingMeta(
            recordingId = "20261024_031138",
            state = RecordingPhase.RECORDING,
            startUtcMs = clock.phoneNs / 1_000_000L,
            endUtcMs = null,
            timezone = "UTC",
            appVersion = "test",
            appVersionCode = 1,
            phoneManufacturer = "JVM",
            phoneModel = "test",
            android = "0",
            sdk = 0,
            sensorKind = "simulated",
            sensorId = sensor.id,
            sensorName = sensor.name,
            sensorFirmware = null,
            sensorBatteryAtStart = 87,
            config = RecordingConfig(hr = true, ecg = true, acc = false),
            streams = streams,
        )
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta, clock)
    val fanout = SampleFanout(writer, monitor) { throw AssertionError("monitor failed") }

    // The tracker reports other settings than the ones the streams asked for.
    sensor.negotiatedRate = 100.0
    sensor.negotiatedSettings = mapOf("range_g" to "16", "resolution_bits" to "12")
    sensor.startStreams(setOf("hr", "ecg"), fanout)
    repeat(3) {
      clock.advanceMs(1_000)
      sensor.pump(clock.elapsedNs())
    }
    sensor.stopStreams()
    writer.finish(RecordingPhase.STOPPED, "done")

    val recorded = requireNotNull(RecordingMeta.read(writer.dir))
    assertEquals(100.0, recorded.stream("ecg")!!.rateHz!!, 0.0)
    assertEquals("16", recorded.stream("ecg")!!.settings["range_g"])
    assertTrue("hr frames are stored", frames(File(writer.dir, "hr.log")) > 0)
    assertTrue("ecg frames are stored", frames(File(writer.dir, "ecg.log")) > 0)
  }

  @Test
  fun aRecordedDirectoryReplaysThroughTheTrackerInterface() {
    val sensor = FakeTracker(clock, autoRun = false)
    sensor.connect()
    val streams =
        listOf(
            RecordingStream("hr", com.rbmr.taigasleep.tracker.StreamType.HEART_RATE, "polar-hr-v1", "hr.log", 1.0),
            RecordingStream("rr", com.rbmr.taigasleep.tracker.StreamType.RR_INTERVAL, "polar-hr-v1", "hr.log"),
        )
    val writer =
        RecordingWriter.create(
            tmp.newFolder("replay"),
            RecordingMeta(
                recordingId = "20261024_031139",
                state = RecordingPhase.RECORDING,
                startUtcMs = clock.phoneNs / 1_000_000L,
                endUtcMs = null,
                timezone = "UTC",
                appVersion = "test",
                appVersionCode = 1,
                phoneManufacturer = "JVM",
                phoneModel = "test",
                android = "0",
                sdk = 0,
                sensorKind = "simulated",
                sensorId = sensor.id,
                sensorName = sensor.name,
                sensorFirmware = null,
                sensorBatteryAtStart = 87,
                config = RecordingConfig(hr = true, ecg = false, acc = false),
                streams = streams,
            ),
            clock,
        )
    sensor.startStreams(setOf("hr"), SampleFanout(writer, monitor) {})
    repeat(5) {
      clock.advanceMs(1_000)
      sensor.pump(clock.elapsedNs())
    }
    sensor.stopStreams()
    writer.finish(RecordingPhase.STOPPED, "done")

    val expected = frames(File(writer.dir, "hr.log"))
    val replayed = mutableListOf<TrackerFrame>()
    val done = CountDownLatch(expected)
    val listener =
        object : TrackerListener {
          override fun onFrame(frame: TrackerFrame) {
            synchronized(replayed) { replayed += frame }
            done.countDown()
          }

          override fun onError(message: String) = throw AssertionError(message)
        }
    val replay = ReplayTracker(writer.dir, clock)
    replay.connect()
    replay.startStreams(setOf("hr"), listener)

    assertTrue("every stored frame is replayed", done.await(5, TimeUnit.SECONDS))
    assertEquals(expected, synchronized(replayed) { replayed.size })
  }

  private fun frames(file: File): Int {
    var count = 0
    FrameLog.scan(file) { count++ }
    return count
  }
}
