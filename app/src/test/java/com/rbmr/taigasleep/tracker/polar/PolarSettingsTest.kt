package com.rbmr.taigasleep.tracker.polar

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every Polar driver names the negotiated settings the way the H10 always has. */
class PolarSettingsTest {
  private class Listener : TrackerListener {
    var stream: String? = null
    var rateHz: Double? = null
    var settings: Map<String, String> = emptyMap()
    val errors = mutableListOf<String>()

    override fun onFrame(frame: TrackerFrame) = Unit

    override fun onNegotiated(stream: String, rateHz: Double?, settings: Map<String, String>) {
      this.stream = stream
      this.rateHz = rateHz
      this.settings = settings
    }

    override fun onError(message: String) {
      errors += message
    }
  }

  private fun seam() =
      FakePolarSeam().apply {
        streamSettings =
            PolarSensorSetting(
                mapOf(
                    SettingType.SAMPLE_RATE to 52,
                    SettingType.RESOLUTION to 16,
                    SettingType.RANGE to 8,
                )
            )
      }

  @Test
  fun `the negotiated settings carry the h10 keys`() {
    val listener = Listener()
    val seam = seam()

    val settings =
        runBlocking {
          PolarSettings.negotiate(seam, "V1", PolarDeviceDataType.ACC, emptyMap(), listener)
        }

    assertEquals(52, settings.settings[SettingType.SAMPLE_RATE]?.max())
    assertEquals("acc", listener.stream)
    assertEquals(52.0, listener.rateHz ?: 0.0, 0.0)
    assertEquals(
        mapOf("rate_hz" to "52", "resolution_bits" to "16", "range_g" to "8"),
        listener.settings,
    )
    assertTrue(listener.errors.isEmpty())
  }

  @Test
  fun `a wanted value the strap offers is chosen without an error`() {
    val listener = Listener()

    val settings =
        runBlocking {
          PolarSettings.negotiate(
              seam(),
              "H10-1",
              PolarDeviceDataType.ECG,
              mapOf(SettingType.SAMPLE_RATE to 52),
              listener,
          )
        }

    assertEquals(52, settings.settings[SettingType.SAMPLE_RATE]?.max())
    assertTrue(listener.errors.isEmpty())
  }

  @Test
  fun `a wanted value the strap refuses falls back to the highest and is reported`() {
    val listener = Listener()

    val settings =
        runBlocking {
          PolarSettings.negotiate(
              seam(),
              "H10-1",
              PolarDeviceDataType.ECG,
              mapOf(SettingType.SAMPLE_RATE to 130),
              listener,
          )
        }

    assertEquals(52, settings.settings[SettingType.SAMPLE_RATE]?.max())
    assertEquals(1, listener.errors.size)
  }

  @Test
  fun `the best settings for on-device recording are not reported as negotiated`() {
    val listener = Listener()

    val settings = runBlocking { PolarSettings.best(seam(), "V1", PolarDeviceDataType.HR) }

    assertEquals(52, settings.settings[SettingType.SAMPLE_RATE]?.max())
    assertNull(listener.stream)
  }
}
