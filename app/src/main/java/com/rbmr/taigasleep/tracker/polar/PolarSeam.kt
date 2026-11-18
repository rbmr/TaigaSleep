@file:Suppress("TooManyFunctions")

package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.BleDisClient
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarH10OfflineExerciseApi
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarExerciseData
import com.polar.sdk.api.model.PolarExerciseEntry
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarPpiData
import com.polar.sdk.api.model.PolarSensorSetting
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SensorKind
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The part of the Polar SDK every Polar driver uses, behind one interface so their logic can be
 * tested with a fake instead of a strap. [PolarSdkSeam] is the real one; tests supply their own.
 *
 * Every Polar tracker shares the one instance, so one SDK drives GATT and scanning on the radio.
 */
interface PolarSeam {
  /** What a driver needs to hear from the SDK; a driver implements the calls it needs. */
  interface ConnectionListener {
    fun onConnecting(address: String?) {}

    fun onConnected(address: String?) {}

    fun onDisconnected() {}

    fun onFeatureReady(feature: PolarBleSdkFeature) {}

    fun onBattery(level: Int?) {}

    fun onFirmware(value: String?) {}
  }

  /** Registers the tracker that owns this device id; one tracker per id. */
  fun register(id: String, listener: ConnectionListener)

  /** Forgets the tracker for this device id, so its callbacks stop. */
  fun unregister(id: String)

  fun connect(id: String)

  fun disconnect(id: String)

  /**
   * Shuts the SDK down and makes a fresh one; the next call creates it. A dropped session can be
   * parked until a scan hears the strap advertise, and a new instance is what clears that.
   */
  fun recreate()

  fun scan(context: Context): Flow<PolarDeviceInfo>

  fun startHr(id: String): Flow<PolarHrData>

  fun startEcg(id: String, settings: PolarSensorSetting): Flow<PolarEcgData>

  fun startPpg(id: String, settings: PolarSensorSetting): Flow<PolarPpgData>

  fun startPpi(id: String): Flow<PolarPpiData>

  fun startAcc(id: String, settings: PolarSensorSetting): Flow<PolarAccelerometerData>

  suspend fun streamSettings(id: String, type: PolarDeviceDataType): PolarSensorSetting

  suspend fun setLocalTime(id: String, time: LocalDateTime)

  suspend fun getLocalTime(id: String): LocalDateTime

  suspend fun startOffline(id: String, type: PolarDeviceDataType, settings: PolarSensorSetting)

  suspend fun stopOffline(id: String, type: PolarDeviceDataType)

  fun listOffline(id: String): Flow<PolarOfflineRecordingEntry>

  suspend fun readOffline(id: String, entry: PolarOfflineRecordingEntry): PolarOfflineRecordingData

  suspend fun removeOffline(id: String, entry: PolarOfflineRecordingEntry)

  /** Starts an exercise recording on the strap; the SDK stores one sample kind per recording. */
  suspend fun startExercise(
      id: String,
      exerciseId: String,
      sampleType: PolarH10OfflineExerciseApi.SampleType,
  )

  suspend fun stopExercise(id: String)

  /** True while the strap is already storing an exercise. */
  suspend fun exerciseOngoing(id: String): Boolean

  fun listExercises(id: String): Flow<PolarExerciseEntry>

  suspend fun fetchExercise(id: String, entry: PolarExerciseEntry): PolarExerciseData

  suspend fun removeExercise(id: String, entry: PolarExerciseEntry)
}

/** The Polar SDK, one instance for every Polar driver in the app. */
object PolarSdkSeam : PolarSeam {
  // One lock for the context and the instance, so a tracker created on any thread sees the same
  // SDK rather than a second one that would fight it for the radio.
  private val lock = Any()

  @Volatile private var api: PolarBleApi? = null
  @Volatile private var context: Context? = null
  private val listeners = ConcurrentHashMap<String, PolarSeam.ConnectionListener>()

  /** Keeps the app context the SDK needs and returns this seam. */
  fun install(context: Context): PolarSeam {
    synchronized(lock) { this.context = context.applicationContext }
    return this
  }

  override fun register(id: String, listener: PolarSeam.ConnectionListener) {
    listeners[id] = listener
  }

  override fun unregister(id: String) {
    listeners.remove(id)
  }

  private fun api(context: Context): PolarBleApi {
    api?.let {
      return it
    }
    return synchronized(lock) {
      api
          ?: PolarBleApiDefaultImpl.defaultImplementation(
                  context.applicationContext,
                  setOf(
                      PolarBleSdkFeature.FEATURE_HR,
                      PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                      PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                      PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                      PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                      PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                      PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                  ),
              )
              .also {
                // The SDK reconnects dropped links itself (it decides how to call connectGatt).
                it.setAutomaticReconnection(true)
                it.setApiCallback(callback)
                api = it
              }
    }
  }

  private val callback =
      object : PolarBleApiCallback() {
        override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
          listeners[polarDeviceInfo.deviceId]?.onConnecting(polarDeviceInfo.address)
        }

        override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
          listeners[polarDeviceInfo.deviceId]?.onConnected(polarDeviceInfo.address)
        }

        override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
          listeners[polarDeviceInfo.deviceId]?.onDisconnected()
        }

        override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
          listeners[identifier]?.onFeatureReady(feature)
        }

        override fun batteryLevelReceived(identifier: String, level: Int) {
          listeners[identifier]?.onBattery(level)
        }

        override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
          if (uuid == BleDisClient.SOFTWARE_REVISION_STRING) {
            listeners[identifier]?.onFirmware(value)
          }
        }

        // The SDK's callback interface requires these two; we do not use them.
        override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

        override fun htsNotificationReceived(
            identifier: String,
            data: PolarHealthThermometerData,
        ) = Unit
      }

  override fun connect(id: String) {
    context?.let { api(it).connectToDevice(id) }
  }

  override fun disconnect(id: String) {
    context?.let { api(it).disconnectFromDevice(id) }
  }

  override fun recreate() {
    synchronized(lock) {
      api?.let { runCatching { it.shutDown() } }
      api = null
    }
  }

  override fun scan(context: Context): Flow<PolarDeviceInfo> = api(context).searchForDevice()

  override fun startHr(id: String): Flow<PolarHrData> = api(requireContext()).startHrStreaming(id)

  override fun startEcg(id: String, settings: PolarSensorSetting): Flow<PolarEcgData> =
      api(requireContext()).startEcgStreaming(id, settings)

  override fun startPpg(id: String, settings: PolarSensorSetting): Flow<PolarPpgData> =
      api(requireContext()).startPpgStreaming(id, settings)

  override fun startPpi(id: String): Flow<PolarPpiData> = api(requireContext()).startPpiStreaming(id)

  override fun startAcc(id: String, settings: PolarSensorSetting): Flow<PolarAccelerometerData> =
      api(requireContext()).startAccStreaming(id, settings)

  override suspend fun streamSettings(
      id: String,
      type: PolarDeviceDataType,
  ): PolarSensorSetting = api(requireContext()).requestStreamSettings(id, type)

  override suspend fun setLocalTime(id: String, time: LocalDateTime) {
    api(requireContext()).setLocalTime(id, time)
  }

  override suspend fun getLocalTime(id: String): LocalDateTime =
      api(requireContext()).getLocalTime(id)

  override suspend fun startOffline(
      id: String,
      type: PolarDeviceDataType,
      settings: PolarSensorSetting,
  ) {
    api(requireContext()).startOfflineRecording(id, type, settings, null)
  }

  override suspend fun stopOffline(id: String, type: PolarDeviceDataType) {
    api(requireContext()).stopOfflineRecording(id, type)
  }

  override fun listOffline(id: String): Flow<PolarOfflineRecordingEntry> =
      api(requireContext()).listOfflineRecordings(id)

  override suspend fun readOffline(
      id: String,
      entry: PolarOfflineRecordingEntry,
  ): PolarOfflineRecordingData =
      api(requireContext()).getOfflineRecord(id, entry, null)

  override suspend fun removeOffline(id: String, entry: PolarOfflineRecordingEntry) {
    api(requireContext()).removeOfflineRecord(id, entry)
  }

  override suspend fun startExercise(
      id: String,
      exerciseId: String,
      sampleType: PolarH10OfflineExerciseApi.SampleType,
  ) {
    api(requireContext())
        .startRecording(
            id,
            exerciseId,
            PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S,
            sampleType,
        )
  }

  override suspend fun stopExercise(id: String) {
    api(requireContext()).stopRecording(id)
  }

  override suspend fun exerciseOngoing(id: String): Boolean =
      api(requireContext()).requestRecordingStatus(id).ongoing

  override fun listExercises(id: String): Flow<PolarExerciseEntry> =
      api(requireContext()).listExercises(id)

  override suspend fun fetchExercise(id: String, entry: PolarExerciseEntry): PolarExerciseData =
      api(requireContext()).fetchExercise(id, entry)

  override suspend fun removeExercise(id: String, entry: PolarExerciseEntry) {
    api(requireContext()).removeExercise(id, entry)
  }

  private fun requireContext(): Context =
      checkNotNull(context) { "PolarSdkSeam.install was not called" }
}

/** The Polar kind a device name names; a device no Polar driver knows is not listed. */
internal fun polarKindOf(name: String): SensorKind? =
    when {
      name.contains("H10", ignoreCase = true) -> SensorKind.POLAR_H10
      name.contains("Verity", ignoreCase = true) ||
          name.contains("Polar Sense", ignoreCase = true) -> SensorKind.POLAR_VERITY
      name.contains("Loop", ignoreCase = true) -> SensorKind.POLAR_LOOP
      else -> null
    }

/** Every Polar device in range, named by the driver that runs it. */
fun scanPolar(context: Context): Flow<FoundSensor> =
    PolarSdkSeam.install(context).scan(context).mapNotNull { info ->
      polarKindOf(info.name)?.let { FoundSensor(it, info.deviceId, info.name, info.rssi) }
    }
