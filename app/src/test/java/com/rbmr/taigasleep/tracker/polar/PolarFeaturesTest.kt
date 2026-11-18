package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Each driver asks the seam for the SDK features it uses, so no instance carries another's. */
@RunWith(RobolectricTestRunner::class)
class PolarFeaturesTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the h10 asks for its own features only`() {
    val seam = FakePolarSeam()
    val h10 = PolarH10Sensor(context, "H10-1", "Polar H10", seam)
    try {
      assertEquals(
          setOf(
              PolarBleSdkFeature.FEATURE_HR,
              PolarBleSdkFeature.FEATURE_DEVICE_INFO,
              PolarBleSdkFeature.FEATURE_BATTERY_INFO,
              PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
              PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
              PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
          ),
          seam.requestedFeatures.single(),
      )
    } finally {
      h10.release()
    }
  }

  @Test
  fun `the verity asks for its live streams and its own recordings`() {
    val seam = FakePolarSeam()
    val verity = PolarVerityTracker(context, "V1", "Polar Verity Sense", seam)
    try {
      assertEquals(
          setOf(
              PolarBleSdkFeature.FEATURE_HR,
              PolarBleSdkFeature.FEATURE_DEVICE_INFO,
              PolarBleSdkFeature.FEATURE_BATTERY_INFO,
              PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
              PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
              PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
          ),
          seam.requestedFeatures.single(),
      )
    } finally {
      verity.release()
    }
  }

  @Test
  fun `the loop asks for its own recordings and no live streaming`() {
    val seam = FakePolarSeam()
    val loop = PolarLoopTracker(context, "L1", "Polar Loop", seam)
    try {
      assertEquals(
          setOf(
              PolarBleSdkFeature.FEATURE_HR,
              PolarBleSdkFeature.FEATURE_DEVICE_INFO,
              PolarBleSdkFeature.FEATURE_BATTERY_INFO,
              PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
              PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
          ),
          seam.requestedFeatures.single(),
      )
    } finally {
      loop.release()
    }
  }
}
