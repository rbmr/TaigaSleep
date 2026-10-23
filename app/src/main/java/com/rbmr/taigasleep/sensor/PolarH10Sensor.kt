package com.rbmr.taigasleep.sensor

import android.content.Context
import android.util.Log
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.BleDisClient
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One process-wide Polar SDK instance. Its single callback is fanned out to the sensor objects by
 * device id, so a sensor can be replaced without re-creating the SDK.
 */
private object PolarSdk {
  private var api: PolarBleApi? = null
  val sensors = ConcurrentHashMap<String, PolarH10Sensor>()

  @Synchronized
  fun get(context: Context): PolarBleApi =
      api
          ?: PolarBleApiDefaultImpl.defaultImplementation(
                  context.applicationContext,
                  setOf(
                      PolarBleSdkFeature.FEATURE_HR,
                      PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                      PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                      PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                      PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                  ),
              )
              .also {
                // The SDK reconnects dropped links itself (it decides how to call connectGatt).
                it.setAutomaticReconnection(true)
                it.setApiCallback(callback)
                api = it
              }

  private val callback =
      object : PolarBleApiCallback() {
        override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
          sensors[polarDeviceInfo.deviceId]?.onConnecting()
        }

        override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
          sensors[polarDeviceInfo.deviceId]?.onConnected()
        }

        override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
          sensors[polarDeviceInfo.deviceId]?.onDisconnected()
        }

        override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
          sensors[identifier]?.onFeatureReady(feature)
        }

        override fun batteryLevelReceived(identifier: String, level: Int) {
          sensors[identifier]?.onBattery(level)
        }

        override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
          if (uuid == BleDisClient.SOFTWARE_REVISION_STRING) sensors[identifier]?.onFirmware(value)
        }

        // The SDK's callback interface requires these two; we do not use them.
        override fun disInformationReceived(identifier: String, disInfo: DisInfo) {}

        override fun htsNotificationReceived(
            identifier: String,
            data: PolarHealthThermometerData,
        ) {}
      }
}

/** A Polar H10 over BLE: HR (with RR), ECG at 130 Hz and ACC at 50 Hz, ±8 G, raw SDK values. */
class PolarH10Sensor(
    context: Context,
    override val id: String,
    override val name: String,
    private val clock: Clock = SystemClockSource,
) : SleepSensor {
  override val kind = SensorKind.POLAR_H10
  override val capabilities = SensorCapabilities(ecg = true, acc = true, rr = true)

  private val appContext = context.applicationContext
  private val api: PolarBleApi
    get() = PolarSdk.get(appContext)

  private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

  private val _battery = MutableStateFlow<Int?>(null)
  override val battery: StateFlow<Int?> = _battery.asStateFlow()

  private val _firmware = MutableStateFlow<String?>(null)
  override val firmware: StateFlow<String?> = _firmware.asStateFlow()

  // Sample delivery and stream (re)starts each get one thread, so the SDK threads never wait on
  // disk and samples of a stream stay in order.
  private val delivery = Executors.newSingleThreadExecutor { Thread(it, "polar-delivery-$id") }
  private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "polar-control-$id") }

  private val ready = HashSet<PolarBleSdkFeature>()
  private val streams = HashMap<String, Disposable>()
  private var wanted: StreamConfig? = null
  private var listener: SampleListener? = null
  private var userDisconnect = false

  // The strap's clock is set once per recording, before its streams start, so the sample
  // timestamps are real time (an unset H10 clock was days off). Touched on the control thread.
  private var clockSynced = false
  private var clockSyncing = false
  private var clockWaiting = false
  private val gotData = ConcurrentHashMap.newKeySet<String>()

  init {
    PolarSdk.sensors[id] = this
  }

  override fun connect() {
    userDisconnect = false
    _connection.value = ConnectionState.CONNECTING
    try {
      api.connectToDevice(id)
    } catch (e: Exception) {
      // Missing Bluetooth permission or an invalid id.
      Log.e(TAG, "connect failed", e)
      _connection.value = ConnectionState.DISCONNECTED
    }
  }

  override fun disconnect() {
    userDisconnect = true
    control.execute { disposeStreams() }
    try {
      api.disconnectFromDevice(id)
    } catch (e: Exception) {
      Log.w(TAG, "disconnect failed", e)
    }
    _connection.value = ConnectionState.DISCONNECTED
  }

  override fun startStreams(config: StreamConfig, listener: SampleListener) {
    control.execute {
      wanted = config
      this.listener = listener
      clockSynced = false
      maybeBeginStreams()
    }
  }

  override fun stopStreams() {
    control.execute {
      wanted = null
      listener = null
      disposeStreams()
    }
  }

  fun release() {
    PolarSdk.sensors.remove(id, this)
    control.shutdown()
    delivery.shutdown()
  }

  // Callbacks from the SDK.

  internal fun onConnecting() {
    _connection.value = ConnectionState.CONNECTING
  }

  internal fun onConnected() {
    _connection.value = ConnectionState.CONNECTED
    control.execute { maybeBeginStreams() }
    // If readiness is never reported, try anyway after a while.
    control.schedule({ maybeBeginStreams(force = true) }, FEATURE_WAIT_S, TimeUnit.SECONDS)
  }

  internal fun onDisconnected() {
    control.execute {
      ready.clear()
      disposeStreams()
      // A strap that lost its battery comes back with its clock reset, so set it again on every
      // reconnect, before the streams restart.
      clockSynced = false
      clockSyncing = false
      clockWaiting = false
    }
    // Unless we asked for it the SDK keeps trying to reconnect.
    _connection.value =
        if (userDisconnect) ConnectionState.DISCONNECTED else ConnectionState.CONNECTING
  }

  internal fun onFeatureReady(feature: PolarBleSdkFeature) {
    control.execute {
      ready += feature
      maybeBeginStreams()
    }
  }

  internal fun onBattery(level: Int) {
    _battery.value = level
  }

  internal fun onFirmware(value: String) {
    // The strap pads the revision string with a NUL.
    _firmware.value = value.trimEnd('\u0000').trim()
  }

  // Streaming. Everything below runs on the control thread.

  private fun maybeBeginStreams(force: Boolean = false) {
    val cfg = wanted ?: return
    val l = listener ?: return
    if (_connection.value != ConnectionState.CONNECTED) return
    val streaming =
        PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING in ready &&
            PolarBleSdkFeature.FEATURE_HR in ready
    if (!streaming && !force) return
    if (streams.isNotEmpty()) return
    if (!clockSynced) {
      if (PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP in ready) {
        syncClock(l)
      } else if (!clockWaiting) {
        // Give the strap a moment to report the feature; if it never does, record without it.
        clockWaiting = true
        control.schedule(
            {
              clockWaiting = false
              if (!clockSynced && !clockSyncing && wanted != null) {
                l.onError("strap clock not set: the sensor did not offer time setup")
                clockSynced = true
                maybeBeginStreams(force = true)
              }
            },
            CLOCK_WAIT_S,
            TimeUnit.SECONDS,
        )
      }
      return
    }
    startHr(l)
    if (cfg.ecg) startEcg(l)
    if (cfg.acc) startAcc(l)
  }

  /** Sets the strap's clock to the phone's, then carries on starting the streams. */
  private fun syncClock(l: SampleListener) {
    if (clockSyncing) return
    clockSyncing = true
    val done = {
      control.execute {
        clockSyncing = false
        clockSynced = true
        maybeBeginStreams(force = true)
      }
    }
    try {
      // UTC, so the strap's timestamps are UTC like the rest of the recording.
      api.setLocalTime(id, LocalDateTime.now(ZoneOffset.UTC))
          .timeout(CLOCK_SYNC_S, TimeUnit.SECONDS)
          .subscribe(
              {
                l.onNote("clock_sync", "strap clock set to the phone time (UTC)")
                done()
              },
              {
                // Recording matters more than the clock: carry on, the phone time is in every batch.
                l.onError("could not set the strap clock: ${it.message}")
                done()
              },
          )
    } catch (e: Exception) {
      l.onError("could not set the strap clock: ${e.message}")
      done()
    }
  }

  private fun disposeStreams() {
    streams.values.forEach { it.dispose() }
    streams.clear()
    gotData.clear()
  }

  /** Notes the first sample of a stream, once per start. */
  private fun gotSample(name: String, l: SampleListener) {
    if (gotData.add(name)) l.onNote("stream", "$name streaming")
  }

  /** Restarts a stream that never produced a sample; a silent start failure loses the night. */
  private fun watch(name: String, l: SampleListener, restart: () -> Unit) {
    control.schedule(
        {
          if (name !in gotData && wanted != null && _connection.value == ConnectionState.CONNECTED) {
            l.onError("$name stream: no data after $FIRST_DATA_S s, restarting it")
            restart()
          }
        },
        FIRST_DATA_S,
        TimeUnit.SECONDS,
    )
  }

  private fun startHr(l: SampleListener) {
    track(
        "hr",
        api.startHrStreaming(id).subscribe(
            { data ->
              val stamp = Stamp.now(clock)
              gotSample("hr", l)
              delivery.execute {
                for (s in data.samples) {
                  l.onHr(
                      stamp,
                      HrSample(
                          s.hr,
                          s.contactStatusSupported,
                          s.contactStatus,
                          s.rrAvailable,
                          s.rrsMs.toIntArray(),
                      ),
                  )
                }
              }
            },
            { retryLater("hr", l, it) },
        ),
    )
    watch("hr", l) { startHr(l) }
  }

  private fun startEcg(l: SampleListener) {
    val wantedSettings = mapOf(SettingType.SAMPLE_RATE to 130, SettingType.RESOLUTION to 14)
    track(
        "ecg",
        settingsFor(PolarDeviceDataType.ECG, wantedSettings, l)
            .flatMapPublisher { api.startEcgStreaming(id, it) }
            .subscribe(
                { data ->
                  val stamp = Stamp.now(clock)
                  gotSample("ecg", l)
                  delivery.execute {
                    val samples = data.samples.filterIsInstance<EcgSample>()
                    if (samples.isNotEmpty()) {
                      l.onEcg(
                          stamp,
                          EcgBatch(
                              LongArray(samples.size) { samples[it].timeStamp },
                              IntArray(samples.size) { samples[it].voltage },
                          ),
                      )
                    }
                  }
                },
                { retryLater("ecg", l, it) },
            ),
    )
    watch("ecg", l) { startEcg(l) }
  }

  private fun startAcc(l: SampleListener) {
    val wantedSettings =
        mapOf(SettingType.SAMPLE_RATE to 50, SettingType.RESOLUTION to 16, SettingType.RANGE to 8)
    track(
        "acc",
        settingsFor(PolarDeviceDataType.ACC, wantedSettings, l)
            .flatMapPublisher { api.startAccStreaming(id, it) }
            .subscribe(
                { data ->
                  val stamp = Stamp.now(clock)
                  gotSample("acc", l)
                  delivery.execute {
                    val s = data.samples
                    if (s.isNotEmpty()) {
                      l.onAcc(
                          stamp,
                          AccBatch(
                              LongArray(s.size) { s[it].timeStamp },
                              IntArray(s.size * 3) {
                                when (it % 3) {
                                  0 -> s[it / 3].x
                                  1 -> s[it / 3].y
                                  else -> s[it / 3].z
                                }
                              },
                          ),
                      )
                    }
                  }
                },
                { retryLater("acc", l, it) },
            ),
    )
    watch("acc", l) { startAcc(l) }
  }

  /**
   * Picks the wanted setting value where the sensor offers it, else the highest it offers, and
   * reports a mismatch, because the format doc promises ECG 130 Hz and ACC 50 Hz ±8 G.
   */
  private fun settingsFor(
      type: PolarDeviceDataType,
      wanted: Map<SettingType, Int>,
      l: SampleListener,
  ) =
      api.requestStreamSettings(id, type).map { available ->
        val chosen =
            available.settings.mapValues { (t, values) ->
              wanted[t]?.takeIf { it in values } ?: values.max()
            }
        if (chosen.any { (t, v) -> wanted[t] != null && wanted[t] != v }) {
          l.onError("$type settings differ from the wanted ones: $chosen")
        }
        PolarSensorSetting(chosen)
      }

  private fun track(name: String, d: Disposable) {
    streams.put(name, d)?.dispose()
  }

  private fun retryLater(name: String, l: SampleListener, e: Throwable) {
    l.onError("$name stream error: ${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
    control.schedule(
        {
          if (wanted != null && _connection.value == ConnectionState.CONNECTED) {
            when (name) {
              "hr" -> startHr(l)
              "ecg" -> startEcg(l)
              "acc" -> startAcc(l)
            }
          }
        },
        RETRY_S,
        TimeUnit.SECONDS,
    )
  }

  companion object {
    private const val TAG = "PolarH10Sensor"
    private const val FEATURE_WAIT_S = 10L
    private const val RETRY_S = 5L
    private const val CLOCK_SYNC_S = 5L
    private const val CLOCK_WAIT_S = 3L
    private const val FIRST_DATA_S = 15L

    /** Devices in range, as the SDK finds them. */
    fun scan(context: Context): Flowable<PolarDeviceInfo> =
        PolarSdk.get(context).searchForDevice()
  }
}
