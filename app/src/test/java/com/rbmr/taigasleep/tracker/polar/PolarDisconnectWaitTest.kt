package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Polar drivers wait for the SDK's own disconnected callback, not the state their `disconnect`
 * sets at once: a connect issued before the callback is lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PolarDisconnectWaitTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the h10 waits for the sdk to report the disconnect`() = runTest {
    val seam = FakePolarSeam()
    val sensor = PolarH10Sensor(context, "H10-1", "Polar H10", seam)
    try {
      val wait = async { sensor.disconnectAndWait(5_000) }
      runCurrent()

      assertEquals(listOf("H10-1"), seam.disconnected)
      assertFalse("the wait ended before the callback", wait.isCompleted)

      requireNotNull(seam.listener("H10-1")).onDisconnected()
      advanceUntilIdle()
      assertTrue(wait.await())
    } finally {
      sensor.release()
    }
  }

  @Test
  fun `a generic polar tracker waits for the sdk to report the disconnect`() = runTest {
    val seam = FakePolarSeam()
    val tracker = PolarVerityTracker(context, "V1", "Polar Verity Sense", seam)
    try {
      val wait = async { tracker.disconnectAndWait(5_000) }
      runCurrent()

      assertEquals(listOf("V1"), seam.disconnected)
      assertFalse("the wait ended before the callback", wait.isCompleted)

      requireNotNull(seam.listener("V1")).onDisconnected()
      advanceUntilIdle()
      assertTrue(wait.await())
    } finally {
      tracker.release()
    }
  }
}
