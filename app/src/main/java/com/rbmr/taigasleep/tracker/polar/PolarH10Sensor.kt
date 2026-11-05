package com.rbmr.taigasleep.tracker.polar

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
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarRecordingSecret
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import com.rbmr.taigasleep.tracker.AccBatch
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.EcgBatch
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.SensorCapabilities
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.StreamConfig
import com.rbmr.taigasleep.tracker.SystemClockSource
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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

/**
 * The H10 can store HR and RR on the device, but not ECG or ACC. An HR-only configuration may
 * therefore stream or record on the strap; anything else has to stream.
 */
internal fun polarRecordingModes(config: StreamConfig): Set<RecordingMode> =
    if (config.ecg || config.acc) setOf(RecordingMode.STREAM)
    else setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE)

/** A Polar H10 over BLE: HR (with RR), ECG at 130 Hz and ACC at 50 Hz, ±8 G, raw SDK values. */
class PolarH10Sensor(
    context: Context,
    override val id: String,
    override val name: String,
    private val clock: Clock = SystemClockSource,
) : SleepSensor, OnDeviceTracker {
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
  // Pending "first sample" checks, one per stream; cancelled when the streams are torn down.
  private val watchdogs = HashMap<String, ScheduledFuture<*>>()
  private var wanted: StreamConfig? = null
  private var listener: SampleListener? = null
  private var userDisconnect = false

  // On-device recording: the strap stores HR/RR itself and the phone fetches it in the morning.
  private var onDeviceWanted: StreamConfig? = null
  private var onDeviceListener: SampleListener? = null
  private var onDeviceStartedAtMs = 0L
  private var onDeviceActive = false
  private val secrets = PolarRecordingSecrets(appContext)

  // The strap's clock is set once per recording, before its streams start, so the sample
  // timestamps are real time (an unset H10 clock was days off). Touched on the control thread.
  private var clockSynced = false
  private var clockSyncing = false
  private var clockWaiting = false
  private val gotData = ConcurrentHashMap.newKeySet<String>()
  // Incremented whenever the streams are torn down, so delayed retries from the old link give up.
  @Volatile private var streamGeneration = 0

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

  override fun recordingModes(config: StreamConfig): Set<RecordingMode> = polarRecordingModes(config)

  override fun startOnDevice(config: StreamConfig, listener: SampleListener) {
    control.execute {
      onDeviceWanted = config
      onDeviceListener = listener
      maybeBeginOnDevice()
    }
  }

  override fun fetchOnDevice(listener: SampleListener): Boolean =
      try {
        control.submit<Boolean> { fetchStoredRecording(listener) }.get(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS)
      } catch (e: Exception) {
        listener.onError("could not fetch the recording from the tracker: ${e.message ?: e.javaClass.simpleName}")
        false
      }

  override fun discardOnDevice() {
    control.execute {
      stopOnDevice()
      val since = onDeviceStartedAtMs
      runCatching {
        for (entry in hrEntries(since)) {
          api.removeOfflineRecord(id, entry).timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait()
        }
      }
      onDeviceWanted = null
      onDeviceListener = null
      onDeviceStartedAtMs = 0
    }
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
    control.execute {
      maybeBeginOnDevice()
      maybeBeginStreams()
    }
    // If readiness is never reported, try anyway after a while.
    control.schedule({ maybeBeginStreams(force = true) }, FEATURE_WAIT_S, TimeUnit.SECONDS)
    control.schedule({ maybeBeginOnDevice(force = true) }, FEATURE_WAIT_S, TimeUnit.SECONDS)
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
      maybeBeginOnDevice()
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

  // On-device recording. Everything below runs on the control thread.

  private fun maybeBeginOnDevice(force: Boolean = false) {
    val cfg = onDeviceWanted ?: return
    if (cfg.ecg || cfg.acc) return
    if (onDeviceActive) return
    if (_connection.value != ConnectionState.CONNECTED) return
    if (PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING !in ready && !force) return
    val l = onDeviceListener ?: return
    try {
      // A service restart can find the strap already recording from the previous instance. Do not
      // start a second file; the fetch at the end drains whatever is there.
      val active =
          api.getOfflineRecordingStatus(id)
              .timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS)
              .blockingGet()
      if (PolarDeviceDataType.HR in active) {
        onDeviceActive = true
        onDeviceStartedAtMs = System.currentTimeMillis()
        l.onNote("on_device_resume", "the tracker was already storing HR and RR")
        return
      }
      // HR is the only stream the H10 can keep on the device; the service only offers this mode
      // for an HR-only configuration.
      val settings =
          api.requestOfflineRecordingSettings(id, PolarDeviceDataType.HR)
              .timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS)
              .blockingGet()
      api.startOfflineRecording(id, PolarDeviceDataType.HR, settings, secrets.get())
          .timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS)
          .blockingAwait()
      onDeviceActive = true
      onDeviceStartedAtMs = System.currentTimeMillis()
      l.onNote("on_device_start", "the tracker is storing HR and RR on the device")
    } catch (e: Exception) {
      Log.e(TAG, "offline recording did not start", e)
      l.onError("on-device recording did not start: ${e.message ?: e.javaClass.simpleName}")
      onDeviceWanted = null
    }
  }

  private fun fetchStoredRecording(listener: SampleListener): Boolean {
    stopOnDevice()
    val since = onDeviceStartedAtMs
    var any = false
    try {
      for (entry in hrEntries(since)) {
        val data =
            api.getOfflineRecord(id, entry, secrets.get())
                .timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS)
                .blockingGet()
        if (data is PolarOfflineRecordingData.HrOfflineRecording) {
          val startMs = data.startTime.toInstant(ZoneOffset.UTC).toEpochMilli()
          data.data.samples.forEachIndexed { index, sample ->
            val phoneNs = (startMs + index * 1_000L) * 1_000_000L
            listener.onHr(
                Stamp(phoneNs, clock.elapsedNs()),
                HrSample(
                    sample.hr,
                    sample.contactStatusSupported,
                    sample.contactStatus,
                    sample.rrAvailable,
                    sample.rrsMs.map { it }.toIntArray(),
                ),
            )
          }
          any = true
        }
        // It is on the phone now; the strap can forget it.
        runCatching {
          api.removeOfflineRecord(id, entry).timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait()
        }
      }
    } catch (e: Exception) {
      listener.onError("could not read the tracker recording: ${e.message ?: e.javaClass.simpleName}")
    }
    onDeviceWanted = null
    onDeviceListener = null
    onDeviceStartedAtMs = 0
    return any
  }

  private fun hrEntries(sinceMs: Long): List<PolarOfflineRecordingEntry> {
    val entries =
        api.listOfflineRecordings(id)
            .timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS)
            .blockingIterable()
            .toList()
    return entries.filter {
      it.type == PolarDeviceDataType.HR &&
          (sinceMs <= 0 || it.date.toInstant(ZoneOffset.UTC).toEpochMilli() >= sinceMs - 60_000)
    }
  }

  private fun stopOnDevice() {
    if (!onDeviceActive) return
    onDeviceActive = false
    runCatching {
      api.stopOfflineRecording(id, PolarDeviceDataType.HR)
          .timeout(OFFLINE_TIMEOUT_S, TimeUnit.SECONDS)
          .blockingAwait()
    }
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
    streamGeneration++
    watchdogs.values.forEach { it.cancel(false) }
    watchdogs.clear()
    streams.values.forEach { it.dispose() }
    streams.clear()
    gotData.clear()
  }

  /** Notes the first sample of a stream, once per start. */
  private fun gotSample(name: String, l: SampleListener) {
    if (gotData.add(name)) l.onNote("stream", "$name streaming")
  }

  /**
   * Restarts a stream that never produced a sample; a silent start failure loses the night. The
   * check belongs to one stream start: cancel the previous one so a timer left over from before a
   * disconnect cannot fire after the reconnect and restart streams that just started.
   */
  private fun watch(name: String, l: SampleListener, restart: () -> Unit) {
    watchdogs.remove(name)?.cancel(false)
    watchdogs[name] =
        control.schedule(
            {
              if (name !in gotData &&
                  wanted != null &&
                  _connection.value == ConnectionState.CONNECTED) {
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
    val generation = streamGeneration
    control.schedule(
        {
          // A reconnect has already restarted the streams; do not restart them again.
          if (generation == streamGeneration &&
              wanted != null &&
              _connection.value == ConnectionState.CONNECTED) {
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
    private const val OFFLINE_TIMEOUT_S = 60L

    /** Devices in range, as the SDK finds them. */
    fun scan(context: Context): Flowable<PolarDeviceInfo> =
        PolarSdk.get(context).searchForDevice()
  }
}
