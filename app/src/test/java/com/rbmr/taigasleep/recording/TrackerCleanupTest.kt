package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.StreamConfig
import com.rbmr.taigasleep.tracker.TrackerEntry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The tracker copies a finished recording still owes. The list lives in meta.json, so a removal
 * the tracker refused is retried on its next connection and cleared once it succeeds.
 */
class TrackerCleanupTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun meta(pending: List<String>, failures: Int = 0) =
      RecordingMeta(
          recordingId = "20261023_223000",
          state = RecordingPhase.STOPPED,
          mode = RecordingMode.ON_DEVICE,
          startUtcMs = clock.phoneNs / 1_000_000L,
          endUtcMs = clock.phoneNs / 1_000_000L + 3_600_000L,
          timezone = "UTC",
          appVersion = "test",
          appVersionCode = 1,
          phoneManufacturer = "JVM",
          phoneModel = "test",
          android = "0",
          sdk = 0,
          sensorKind = "simulated",
          sensorId = "SIM00001",
          sensorName = "Simulated sensor",
          sensorFirmware = null,
          sensorBatteryAtStart = 87,
          config = RecordingConfig(ecg = false, acc = false),
          trackerRemovalPending = pending,
          trackerRemovalFailures = failures,
      )

  private fun recording(pending: List<String>, failures: Int = 0) =
      tmp.newFolder("rec").also { assertTrue(meta(pending, failures).write(it)) }

  @Test
  fun aFailedRemovalIsRetriedOnTheNextConnectionAndClearedOnSuccess() {
    val dir = recording(listOf("entry-1", "entry-2"))
    val tracker = FakeTracker()
    tracker.refuses = true

    val failed = TrackerCleanup(dir, tracker).retry()

    assertTrue(failed is TrackerRemoval.Failed)
    assertEquals(1, (failed as TrackerRemoval.Failed).attempts)
    // Nothing was lost: the removal is still to do, one connection further.
    assertEquals(listOf("entry-1", "entry-2"), RecordingMeta.read(dir)!!.trackerRemovalPending)
    assertEquals(1, RecordingMeta.read(dir)!!.trackerRemovalFailures)

    // The tracker is connected again and takes them this time.
    tracker.refuses = false
    assertEquals(TrackerRemoval.Done, TrackerCleanup(dir, tracker).retry())

    assertEquals(listOf("entry-1", "entry-2"), tracker.removed)
    assertEquals(emptyList<String>(), RecordingMeta.read(dir)!!.trackerRemovalPending)
    assertEquals(0, RecordingMeta.read(dir)!!.trackerRemovalFailures)
  }

  @Test
  fun nothingToRemoveIsDoneWithoutTouchingTheTracker() {
    val dir = recording(emptyList())
    val tracker = FakeTracker()

    assertEquals(TrackerRemoval.Done, TrackerCleanup(dir, tracker).retry())

    assertEquals(emptyList<String>(), tracker.removed)
  }

  @Test
  fun anEntryTheTrackerNoLongerHasCountsAsRemoved() {
    val dir = recording(listOf("entry-1"))
    // The fake tracker holds nothing, so the entry is already gone.
    val tracker = FakeTracker()

    assertEquals(TrackerRemoval.Done, TrackerCleanup(dir, tracker).retry())

    assertEquals(emptyList<String>(), RecordingMeta.read(dir)!!.trackerRemovalPending)
  }

  @Test
  fun theFailedConnectionsAreCountedUntilTheRemovalIsWorthShowing() {
    val dir = recording(listOf("entry-1"))
    val tracker = FakeTracker().apply { refuses = true }

    repeat(TrackerCleanup.SHOW_AFTER) {
      assertTrue(TrackerCleanup(dir, tracker).retry() is TrackerRemoval.Failed)
    }

    assertEquals(TrackerCleanup.SHOW_AFTER, RecordingMeta.read(dir)!!.trackerRemovalFailures)
    assertEquals(listOf("entry-1"), RecordingMeta.read(dir)!!.trackerRemovalPending)
  }

  /** A tracker that keeps whatever it is given until a removal is allowed to work. */
  private class FakeTracker : OnDeviceTracker {
    var refuses = false
    var removed = mutableListOf<String>()

    override fun startOnDevice(
        config: StreamConfig,
        listener: SampleListener,
        onResult: (error: String?) -> Unit,
    ) = onResult(null)

    override fun stopOnDevice() {}

    override fun fetchOnDevice(): List<TrackerEntry> = emptyList()

    override fun removeFromDevice(ids: List<String>) {
      if (refuses) throw IOException("the tracker is out of reach")
      removed += ids
    }

    override fun discardOnDevice() {}
  }
}
