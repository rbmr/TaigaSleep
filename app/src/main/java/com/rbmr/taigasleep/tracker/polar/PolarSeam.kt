@file:Suppress("TooManyFunctions")

package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.BleDisClient
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarPpiData
import com.polar.sdk.api.model.PolarSensorSetting
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow

/**
 * The part of the Polar SDK the generic Polar drivers use, behind one interface so their logic can
 * be tested with a fake instead of a strap. [PolarSdkSeam] is the real one; tests supply their own.
 */
interface PolarSeam {
  /** What a driver needs to hear from the SDK. */
  interface ConnectionListener {
    fun onConnected()

    fun onDisconnected()

    fun onBattery(level: Int?)

    fun onFirmware(value: String?)
  }

  /** Registers the tracker that owns this device id; one tracker per id. */
  fun register(id: String, listener: ConnectionListener)

  fun connect(id: String)

  fun disconnect(id: String)

  fun scan(context: Context): Flow<PolarDeviceInfo>

  fun startHr(id: String): Flow<PolarHrData>

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
}

/** The Polar SDK, one instance for the generic drivers. */
object PolarSdkSeam : PolarSeam {
  @Volatile private var api: PolarBleApi? = null
  private val listeners = ConcurrentHashMap<String, PolarSeam.ConnectionListener>()

  override fun register(id: String, listener: PolarSeam.ConnectionListener) {
    listeners[id] = listener
  }

  private fun api(context: Context): PolarBleApi =
      api
          ?: PolarBleApiDefaultImpl.defaultImplementation(
                  context.applicationContext,
                  setOf(
                      PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                      PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                      PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                      PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                      PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                      PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                  ),
              )
              .also {
                it.setAutomaticReconnection(true)
                it.setApiCallback(callback)
                api = it
              }

  private val callback =
      object : PolarBleApiCallback() {
        override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
          listeners[polarDeviceInfo.deviceId]?.onConnected()
        }

        override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
          listeners[polarDeviceInfo.deviceId]?.onDisconnected()
        }

        override fun bleSdkFeatureReady(
            identifier: String,
            feature: PolarBleApi.PolarBleSdkFeature,
        ) = Unit

        override fun batteryLevelReceived(identifier: String, level: Int) {
          listeners[identifier]?.onBattery(level)
        }

        override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
          if (uuid == BleDisClient.SOFTWARE_REVISION_STRING) {
            listeners[identifier]?.onFirmware(value.trimEnd('\u0000').trim())
          }
        }

        override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

        override fun htsNotificationReceived(
            identifier: String,
            data: com.polar.sdk.api.model.PolarHealthThermometerData,
        ) = Unit
      }

  private var context: Context? = null

  fun install(context: Context): PolarSeam {
    this.context = context.applicationContext
    return this
  }

  override fun connect(id: String) {
    context?.let { api(it).connectToDevice(id) }
  }

  override fun disconnect(id: String) {
    context?.let { api(it).disconnectFromDevice(id) }
  }

  override fun scan(context: Context): Flow<PolarDeviceInfo> = api(context).searchForDevice()

  override fun startHr(id: String): Flow<PolarHrData> = api(requireContext()).startHrStreaming(id)

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

  private fun requireContext(): Context =
      checkNotNull(context) { "PolarSdkSeam.install was not called" }
}
