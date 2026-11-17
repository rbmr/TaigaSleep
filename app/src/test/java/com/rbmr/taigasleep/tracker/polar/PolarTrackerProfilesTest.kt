package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarPpiData
import com.polar.sdk.api.model.PolarSensorSetting
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.StreamType
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

  private class FakeSeam : PolarSeam {
    val started = mutableListOf<PolarDeviceDataType>()

    override fun register(id: String, listener: PolarSeam.ConnectionListener) = Unit

    override fun connect(id: String) = Unit

    override fun disconnect(id: String) = Unit

    override fun scan(context: Context): Flow<PolarDeviceInfo> = emptyFlow()

    override fun startHr(id: String): Flow<PolarHrData> = emptyFlow()

    override fun startPpg(id: String, settings: PolarSensorSetting): Flow<PolarPpgData> =
        emptyFlow()

    override fun startPpi(id: String): Flow<PolarPpiData> = emptyFlow()

    override fun startAcc(
        id: String,
        settings: PolarSensorSetting,
    ): Flow<PolarAccelerometerData> = emptyFlow()

    override suspend fun streamSettings(
        id: String,
        type: PolarDeviceDataType,
    ): PolarSensorSetting =
        PolarSensorSetting(
            mapOf(PolarSensorSetting.SettingType.SAMPLE_RATE to 55)
        )

    override suspend fun setLocalTime(id: String, time: LocalDateTime) = Unit

    override suspend fun getLocalTime(id: String): LocalDateTime = LocalDateTime.now()

    override suspend fun startOffline(
        id: String,
        type: PolarDeviceDataType,
        settings: PolarSensorSetting,
    ) {
      started += type
    }

    override suspend fun stopOffline(id: String, type: PolarDeviceDataType) = Unit

    override fun listOffline(id: String): Flow<PolarOfflineRecordingEntry> = emptyFlow()

    override suspend fun readOffline(
        id: String,
        entry: PolarOfflineRecordingEntry,
    ): PolarOfflineRecordingData = throw UnsupportedOperationException()

    override suspend fun removeOffline(id: String, entry: PolarOfflineRecordingEntry) = Unit
  }

  @Test
  fun `the verity offers heart rate, pulse, ppg and movement in both sources`() {
    val verity = PolarVerityTracker(context, "V1", "Polar Verity Sense", FakeSeam())

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
    val loop = PolarLoopTracker(context, "L1", "Polar Loop", FakeSeam())

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

    assertTrue(
        h10.matches(
            com.rbmr.taigasleep.tracker.FoundSensor(SensorKind.POLAR_H10, "1", "Polar H10 1", 0)
        )
    )
    assertTrue(
        verity.matches(
            com.rbmr.taigasleep.tracker.FoundSensor(
                SensorKind.POLAR_VERITY,
                "2",
                "Polar Verity Sense",
                0,
            )
        )
    )
    assertTrue(
        loop.matches(
            com.rbmr.taigasleep.tracker.FoundSensor(SensorKind.POLAR_LOOP, "3", "Polar Loop", 0)
        )
    )
    assertFalse(
        h10.matches(
            com.rbmr.taigasleep.tracker.FoundSensor(SensorKind.POLAR_LOOP, "3", "Polar Loop", 0)
        )
    )
  }
}
