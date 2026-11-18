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
import com.rbmr.taigasleep.tracker.SdkLog
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
 * The instance carries the features of the driver using it, never those of another.
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

  /**
   * The SDK features this driver's calls need. The instance is made with them, so a feature one
   * strap mishandles is not enabled on another.
   */
  fun useFeatures(features: Set<PolarBleSdkFeature>)

  /** Registers the tracker that owns this device id; one tracker per id. */
  fun register(id: String, listener: ConnectionListener)

  /** Forgets the tracker for this device id, so its callbacks stop. */
  fun unregister(id: String)

  fun connect(id: String)

  fun disconnect(id: String)

  /**
   * Replaces the SDK instance, made again with the same features. A dropped session can be parked
   * until a scan hears the strap advertise, and a new instance is what clears that.
   */
  fun recreate()

  /**
   * The SDK's own log lines at or after [sinceElapsedMs], oldest first, from a ring that holds only
   * the recent ones. Empty when the seam keeps no log.
   */
  fun logSince(sinceElapsedMs: Long): List<String> = emptyList()

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

/**
 * The one live Polar SDK instance and the feature set it was made for. The features are fixed at
 * creation, so a driver that needs another set gets a new instance and the old one is shut down
 * first: there is never more than one alive.
 */
internal class PolarInstance<Api>(
    private val create: (Set<PolarBleSdkFeature>) -> Api,
    private val shutDown: (Api) -> Unit,
) {
  private val lock = Any()
  private var api: Api? = null
  private var features: Set<PolarBleSdkFeature>? = null

  /** The instance for [features]; one made for another set is shut down and replaced. */
  fun forFeatures(features: Set<PolarBleSdkFeature>): Api = synchronized(lock) {
    api?.takeIf { this.features == features } ?: createFor(features)
  }

  /** The live instance, whatever set it was made for, or a new one for [features] when none is. */
  fun live(features: Set<PolarBleSdkFeature>): Api = synchronized(lock) { api ?: createFor(features) }

  /** Shuts the live instance down and makes it again for the set it had. */
  fun recreate() {
    synchronized(lock) {
      val kept = features
      dispose()
      kept?.let { createFor(it) }
    }
  }

  private fun createFor(features: Set<PolarBleSdkFeature>): Api {
    dispose()
    return create(features).also {
      api = it
      this.features = features
    }
  }

  private fun dispose() {
    api?.let(shutDown)
    api = null
  }
}

/**
 * Scanning reads advertisements and needs no SDK feature, so it makes the smallest instance it
 * can; the driver that connects replaces it with the set it needs.
 */
internal val SCAN_FEATURES = emptySet<PolarBleSdkFeature>()

/** The Polar SDK, one instance for every Polar driver in the app. */
object PolarSdkSeam : PolarSeam {
  // One lock for the context, so a tracker created on any thread sees the same SDK rather than a
  // second one that would fight it for the radio.
  private val lock = Any()

  @Volatile private var context: Context? = null
  private val listeners = ConcurrentHashMap<String, PolarSeam.ConnectionListener>()

  private val sdk =
      PolarInstance<PolarBleApi>(
          create = { features -> newApi(requireContext(), features) },
          shutDown = { runCatching { it.shutDown() } },
      )

  /** What the SDK said recently; the connect watch reads it while a connect stays pending. */
  private val log = SdkLog()

  // The set the driver that last asked for the instance needs. A scan makes a minimal instance
  // before any driver does, and the driver's first call replaces it.
  @Volatile private var required: Set<PolarBleSdkFeature> = SCAN_FEATURES

  /** Keeps the app context the SDK needs and returns this seam. */
  fun install(context: Context): PolarSeam {
    synchronized(lock) { this.context = context.applicationContext }
    return this
  }

  override fun useFeatures(features: Set<PolarBleSdkFeature>) {
    required = features
    sdk.forFeatures(features)
  }

  override fun register(id: String, listener: PolarSeam.ConnectionListener) {
    listeners[id] = listener
  }

  override fun unregister(id: String) {
    listeners.remove(id)
  }

  private fun newApi(context: Context, features: Set<PolarBleSdkFeature>): PolarBleApi =
      PolarBleApiDefaultImpl.defaultImplementation(context.applicationContext, features)
          .also {
            // The SDK reconnects dropped links itself (it decides how to call connectGatt).
            it.setAutomaticReconnection(true)
            it.setApiCallback(callback)
            it.setApiLogger(
                object : PolarBleApi.PolarBleApiLogger {
                  override fun message(str: String) = log.add(str)
                }
            )
          }

  /** The instance for the features of the driver that last asked for it. */
  private fun live(): PolarBleApi = sdk.forFeatures(required)

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
            listeners[identifier]?.onFirmware(polarFirmware(value))
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
    if (context != null) live().connectToDevice(id)
  }

  override fun disconnect(id: String) {
    if (context != null) live().disconnectFromDevice(id)
  }

  override fun recreate() {
    sdk.recreate()
  }

  override fun logSince(sinceElapsedMs: Long): List<String> = log.since(sinceElapsedMs)

  override fun scan(context: Context): Flow<PolarDeviceInfo> {
    install(context)
    return sdk.live(SCAN_FEATURES).searchForDevice()
  }

  override fun startHr(id: String): Flow<PolarHrData> = live().startHrStreaming(id)

  override fun startEcg(id: String, settings: PolarSensorSetting): Flow<PolarEcgData> =
      live().startEcgStreaming(id, settings)

  override fun startPpg(id: String, settings: PolarSensorSetting): Flow<PolarPpgData> =
      live().startPpgStreaming(id, settings)

  override fun startPpi(id: String): Flow<PolarPpiData> = live().startPpiStreaming(id)

  override fun startAcc(id: String, settings: PolarSensorSetting): Flow<PolarAccelerometerData> =
      live().startAccStreaming(id, settings)

  override suspend fun streamSettings(
      id: String,
      type: PolarDeviceDataType,
  ): PolarSensorSetting = live().requestStreamSettings(id, type)

  override suspend fun setLocalTime(id: String, time: LocalDateTime) {
    live().setLocalTime(id, time)
  }

  override suspend fun getLocalTime(id: String): LocalDateTime =
      live().getLocalTime(id)

  override suspend fun startOffline(
      id: String,
      type: PolarDeviceDataType,
      settings: PolarSensorSetting,
  ) {
    live().startOfflineRecording(id, type, settings, null)
  }

  override suspend fun stopOffline(id: String, type: PolarDeviceDataType) {
    live().stopOfflineRecording(id, type)
  }

  override fun listOffline(id: String): Flow<PolarOfflineRecordingEntry> =
      live().listOfflineRecordings(id)

  override suspend fun readOffline(
      id: String,
      entry: PolarOfflineRecordingEntry,
  ): PolarOfflineRecordingData =
      live().getOfflineRecord(id, entry, null)

  override suspend fun removeOffline(id: String, entry: PolarOfflineRecordingEntry) {
    live().removeOfflineRecord(id, entry)
  }

  override suspend fun startExercise(
      id: String,
      exerciseId: String,
      sampleType: PolarH10OfflineExerciseApi.SampleType,
  ) {
    live()
        .startRecording(
            id,
            exerciseId,
            PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S,
            sampleType,
        )
  }

  override suspend fun stopExercise(id: String) {
    live().stopRecording(id)
  }

  override suspend fun exerciseOngoing(id: String): Boolean =
      live().requestRecordingStatus(id).ongoing

  override fun listExercises(id: String): Flow<PolarExerciseEntry> =
      live().listExercises(id)

  override suspend fun fetchExercise(id: String, entry: PolarExerciseEntry): PolarExerciseData =
      live().fetchExercise(id, entry)

  override suspend fun removeExercise(id: String, entry: PolarExerciseEntry) {
    live().removeExercise(id, entry)
  }

  private fun requireContext(): Context =
      checkNotNull(context) { "PolarSdkSeam.install was not called" }
}

/** The strap pads the revision string with a NUL; every driver sees the trimmed value. */
internal fun polarFirmware(value: String): String = value.trimEnd('\u0000').trim()

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
