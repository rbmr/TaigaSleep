package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Verity and Loop profiles, with the SDK replaced by a seam that never needs a strap. */
@RunWith(RobolectricTestRunner::class)
class PolarTrackerProfilesTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the verity offers heart rate, pulse, ppg and movement in both sources`() {
    val verity = PolarVerityTracker(context, "V1", "Polar Verity Sense", FakePolarSeam())

    assertEquals(SensorKind.POLAR_VERITY, verity.kind)
    assertEquals(
        listOf("hr", "rr", "ppi", "ppg", "acc"),
        verity.streams.map { it.id },
    )
    assertTrue(verity.streams.any { it.type == StreamType.PPG })
    assertTrue(verity.streams.any { it.type == StreamType.PULSE_INTERVAL })
    assertTrue(
        verity.recordingModes(setOf("hr")) ==
            setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE)
    )
    assertFalse(verity.recordingModes(setOf("sr")).isNotEmpty())
  }

  @Test
  fun `the loop records heart rate on the tracker only`() {
    val loop = PolarLoopTracker(context, "L1", "Polar Loop", FakePolarSeam())

    assertEquals(SensorKind.POLAR_LOOP, loop.kind)
    assertEquals(listOf("hr", "rr"), loop.streams.map { it.id })
    assertEquals(setOf(RecordingMode.ON_DEVICE), loop.recordingModes(setOf("hr")))
    assertTrue(loop.recordingModes(setOf("ppg")).isEmpty())
  }

  @Test
  fun `a device is offered to the driver that names it`() {
    val h10 = PolarH10Driver()
    val verity = PolarVerityDriver()
    val loop = PolarLoopDriver()

    assertTrue(h10.matches(FoundSensor(SensorKind.POLAR_H10, "1", "Polar H10 1", 0)))
    assertTrue(verity.matches(FoundSensor(SensorKind.POLAR_VERITY, "2", "Polar Verity Sense", 0)))
    assertTrue(loop.matches(FoundSensor(SensorKind.POLAR_LOOP, "3", "Polar Loop", 0)))
    assertFalse(h10.matches(FoundSensor(SensorKind.POLAR_LOOP, "3", "Polar Loop", 0)))
  }
}
