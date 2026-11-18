package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarH10OfflineExerciseApi
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarExerciseData
import com.polar.sdk.api.model.PolarExerciseEntry
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarPpiData
import com.polar.sdk.api.model.PolarSensorSetting
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A Polar seam a test drives by hand: it records the calls and answers with what the test puts in,
 * so a driver can be exercised without a strap or the SDK.
 */
class FakePolarSeam : PolarSeam {
  private val listeners = ConcurrentHashMap<String, PolarSeam.ConnectionListener>()

  val registered = mutableListOf<String>()
  val connected = mutableListOf<String>()
  val disconnected = mutableListOf<String>()
  val startedOffline = mutableListOf<PolarDeviceDataType>()
  val stoppedOffline = mutableListOf<PolarDeviceDataType>()
  val removedOffline = mutableListOf<String>()
  val removedExercises = mutableListOf<String>()

  var hrData: Flow<PolarHrData> = emptyFlow()
  var ecgData: Flow<PolarEcgData> = emptyFlow()
  var ppgData: Flow<PolarPpgData> = emptyFlow()
  var ppiData: Flow<PolarPpiData> = emptyFlow()
  var accData: Flow<PolarAccelerometerData> = emptyFlow()
  var streamSettings =
      PolarSensorSetting(mapOf(PolarSensorSetting.SettingType.SAMPLE_RATE to 130))
  var offlineEntries: List<PolarOfflineRecordingEntry> = emptyList()
  var exerciseEntries: List<PolarExerciseEntry> = emptyList()
  var exercisesOngoing = false
  var failsRemoval = false

  fun listener(id: String): PolarSeam.ConnectionListener? = listeners[id]

  /** Reports a firmware revision as the seam does: the SDK's NUL padding is already trimmed. */
  fun emitFirmware(id: String, value: String) {
    listeners[id]?.onFirmware(polarFirmware(value))
  }

  override fun register(id: String, listener: PolarSeam.ConnectionListener) {
    registered += id
    listeners[id] = listener
  }

  override fun unregister(id: String) {
    registered -= id
    listeners.remove(id)
  }

  override fun connect(id: String) {
    connected += id
  }

  override fun disconnect(id: String) {
    disconnected += id
  }

  override fun recreate() = Unit

  override fun scan(context: Context): Flow<PolarDeviceInfo> = emptyFlow()

  override fun startHr(id: String): Flow<PolarHrData> = hrData

  override fun startEcg(id: String, settings: PolarSensorSetting): Flow<PolarEcgData> = ecgData

  override fun startPpg(id: String, settings: PolarSensorSetting): Flow<PolarPpgData> = ppgData

  override fun startPpi(id: String): Flow<PolarPpiData> = ppiData

  override fun startAcc(
      id: String,
      settings: PolarSensorSetting,
  ): Flow<PolarAccelerometerData> = accData

  override suspend fun streamSettings(
      id: String,
      type: PolarDeviceDataType,
  ): PolarSensorSetting = streamSettings

  override suspend fun setLocalTime(id: String, time: LocalDateTime) = Unit

  override suspend fun getLocalTime(id: String): LocalDateTime = LocalDateTime.now()

  override suspend fun startOffline(
      id: String,
      type: PolarDeviceDataType,
      settings: PolarSensorSetting,
  ) {
    startedOffline += type
  }

  override suspend fun stopOffline(id: String, type: PolarDeviceDataType) {
    stoppedOffline += type
  }

  override fun listOffline(id: String): Flow<PolarOfflineRecordingEntry> =
      offlineEntries.asFlow()

  override suspend fun readOffline(
      id: String,
      entry: PolarOfflineRecordingEntry,
  ): PolarOfflineRecordingData = throw UnsupportedOperationException("no offline data in this fake")

  override suspend fun removeOffline(id: String, entry: PolarOfflineRecordingEntry) {
    if (failsRemoval) throw IOException("the tracker is out of reach")
    removedOffline += entry.path
  }

  override suspend fun startExercise(
      id: String,
      exerciseId: String,
      sampleType: PolarH10OfflineExerciseApi.SampleType,
  ) = Unit

  override suspend fun stopExercise(id: String) = Unit

  override suspend fun exerciseOngoing(id: String): Boolean = exercisesOngoing

  override fun listExercises(id: String): Flow<PolarExerciseEntry> = exerciseEntries.asFlow()

  override suspend fun fetchExercise(
      id: String,
      entry: PolarExerciseEntry,
  ): PolarExerciseData = throw UnsupportedOperationException("no exercises in this fake")

  override suspend fun removeExercise(id: String, entry: PolarExerciseEntry) {
    removedExercises += entry.identifier
  }
}
