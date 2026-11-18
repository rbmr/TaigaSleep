package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.rbmr.taigasleep.recording.TrackerCleanup
import com.rbmr.taigasleep.recording.TrackerRemoval
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.RecordingMode
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A Polar offline entry the tracker refuses to remove stays pending instead of counting as gone. */
@RunWith(RobolectricTestRunner::class)
class PolarRemovalTest {
  @get:Rule val tmp = TemporaryFolder()
  private val context: Context = ApplicationProvider.getApplicationContext()

  private val entry =
      PolarOfflineRecordingEntry(
          "hr/20261118",
          1_000,
          LocalDateTime.of(2026, 11, 18, 22, 0),
          PolarDeviceDataType.HR,
      )

  private fun recording(pending: List<String>): File {
    val dir = tmp.newFolder("rec")
    val meta =
        RecordingMeta(
            recordingId = "20261118_220000",
            state = RecordingPhase.STOPPED,
            mode = RecordingMode.ON_DEVICE,
            startUtcMs = 0,
            endUtcMs = 1,
            timezone = "UTC",
            appVersion = "test",
            appVersionCode = 1,
            phoneManufacturer = "JVM",
            phoneModel = "test",
            android = "0",
            sdk = 0,
            sensorKind = "polar-verity-sense",
            sensorId = "V1",
            sensorName = "Polar Verity Sense",
            sensorFirmware = null,
            sensorBatteryAtStart = null,
            config = RecordingConfig(ecg = false, acc = false),
            trackerRemovalPending = pending,
        )
    assertTrue(meta.write(dir))
    return dir
  }

  @Test
  fun `a failed removal is reported and stays pending`() {
    val seam = FakePolarSeam().apply { offlineEntries = listOf(entry) }
    val tracker = PolarVerityTracker(context, "V1", "Polar Verity Sense", seam)
    val dir = recording(listOf(entry.path))
    try {
      seam.failsRemoval = true

      val failed = TrackerCleanup(dir, tracker).retry()

      assertTrue(failed is TrackerRemoval.Failed)
      assertEquals(listOf(entry.path), RecordingMeta.read(dir)!!.trackerRemovalPending)

      seam.failsRemoval = false
      assertEquals(TrackerRemoval.Done, TrackerCleanup(dir, tracker).retry())

      assertEquals(listOf(entry.path), seam.removedOffline)
      assertEquals(emptyList<String>(), RecordingMeta.read(dir)!!.trackerRemovalPending)
    } finally {
      tracker.release()
    }
  }
}
