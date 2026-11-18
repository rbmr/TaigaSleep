package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The SDK pads its revision string with a NUL; no driver stores the padding. */
@RunWith(RobolectricTestRunner::class)
class PolarFirmwareTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the h10 stores the trimmed revision`() {
    val seam = FakePolarSeam()
    val sensor = PolarH10Sensor(context, "H10-1", "Polar H10", seam)
    try {
      seam.emitFirmware("H10-1", "3.0.35\u0000")

      assertEquals("3.0.35", sensor.firmware.value)
    } finally {
      sensor.release()
    }
  }

  @Test
  fun `the generic drivers store the trimmed revision`() {
    val seam = FakePolarSeam()
    val verity = PolarVerityTracker(context, "V1", "Polar Verity Sense", seam)
    val loop = PolarLoopTracker(context, "L1", "Polar Loop", seam)
    try {
      seam.emitFirmware("V1", "2.1.0\u0000")
      seam.emitFirmware("L1", "1.0.4\u0000")

      assertEquals("2.1.0", verity.firmware.value)
      assertEquals("1.0.4", loop.firmware.value)
    } finally {
      verity.release()
      loop.release()
    }
  }
}
