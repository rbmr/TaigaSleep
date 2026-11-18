package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.model.PolarHrData
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The H10 driven through the seam: connect and stream start without the SDK or a strap. */
@RunWith(RobolectricTestRunner::class)
class PolarH10SensorTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private class RecordingListener : TrackerListener {
    val frames = CopyOnWriteArrayList<TrackerFrame>()
    val errors = CopyOnWriteArrayList<String>()
    private val firstFrame = CountDownLatch(1)

    override fun onFrame(frame: TrackerFrame) {
      frames += frame
      firstFrame.countDown()
    }

    override fun onError(message: String) {
      errors += message
    }

    fun awaitFirstFrame(): TrackerFrame {
      assertTrue("no frame arrived: $errors", firstFrame.await(10, TimeUnit.SECONDS))
      return frames.first()
    }
  }

  @Test
  fun `the SDK log the seam kept is handed out for the connect watch`() {
    val seam = FakePolarSeam().apply { logLines = listOf("device connecting", "device connected") }
    val sensor = PolarH10Sensor(context, "H10-1", "Polar H10", seam)
    try {
      assertEquals(seam.logLines, sensor.logLinesSince(1_234))
    } finally {
      sensor.release()
    }
  }

  @Test
  fun `connecting and starting the heart rate stream uses the seam`() {
    val seam = FakePolarSeam()
    val sensor = PolarH10Sensor(context, "H10-1", "Polar H10", seam)
    val listener = RecordingListener()
    try {
      sensor.startStreams(setOf("hr"), listener)
      sensor.connect()

      assertEquals(listOf("H10-1"), seam.connected)
      assertEquals(ConnectionState.CONNECTING, sensor.connection.value)

      val sdk = requireNotNull(seam.listener("H10-1"))
      seam.hrData =
          flowOf(
              PolarHrData(
                  listOf(
                      PolarHrData.PolarHrSample(62, 62, 0, listOf(960), listOf(960), true, true, true)
                  )
              )
          )
      sdk.onFeatureReady(PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING)
      sdk.onFeatureReady(PolarBleSdkFeature.FEATURE_HR)
      sdk.onFeatureReady(PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP)
      sdk.onConnected("AA:BB:CC:DD:EE:FF")

      assertEquals(ConnectionState.CONNECTED, sensor.connection.value)
      val frame = listener.awaitFirstFrame()
      assertEquals("hr", frame.stream)
      assertTrue(frame.payload.size > 16)
    } finally {
      sensor.stopStreams()
      sensor.release()
    }
    assertTrue("the released sensor is still registered", seam.registered.isEmpty())
  }
}
