@file:Suppress("TooManyFunctions")

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
import com.polar.sdk.api.PolarH10OfflineExerciseApi
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarExerciseEntry
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.PolarCodec
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.SystemClockSource
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.TrackerCapabilities
import com.rbmr.taigasleep.tracker.TrackerEntry
import com.rbmr.taigasleep.tracker.TrackerDriver
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.tracker.FoundSensor
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * One process-wide Polar SDK instance. Its single callback is fanned out to the sensor objects by
 * device id, so a sensor can be replaced without re-creating the SDK, and the SDK can be re-created
 * without replacing the sensors: they keep their registration and their state flows.
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
                      PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                      PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                  ),
              )
              .also {
                // The SDK reconnects dropped links itself (it decides how to call connectGatt).
                it.setAutomaticReconnection(true)
                it.setApiCallback(callback)
                api = it
              }

  /**
   * Shuts the instance down and makes a fresh one. A dropped session can be parked until a scan
   * hears the strap advertise, and a strap still held by the stale link never advertises; a new
   * instance is what clears that. The callbacks reach the same sensor objects by device id.
   */
  @Synchronized
  fun recreate(context: Context): PolarBleApi {
    api?.let { runCatching { it.shutDown() } }
    api = null
    return get(context)
  }

  private val callback =
      object : PolarBleApiCallback() {
        override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
          sensors[polarDeviceInfo.deviceId]?.onConnecting(polarDeviceInfo.address)
        }

        override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
          sensors[polarDeviceInfo.deviceId]?.onConnected(polarDeviceInfo.address)
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
 * The H10 can store HR and RR on the device, but not ECG or ACC. An HR-only selection may
 * therefore stream or record on the strap; anything else has to stream.
 */
internal fun polarRecordingModes(selected: Set<String>): Set<RecordingMode> =
    if (selected.isEmpty() || selected.any { it != "hr" && it != "rr" }) {
      if (selected.isEmpty()) emptySet() else setOf(RecordingMode.STREAM)
    } else setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE)

/** Which of the H10's three stream sources a selection turns on. */
internal data class PolarSelection(val hr: Boolean, val ecg: Boolean, val acc: Boolean) {
  val any: Boolean
    get() = hr || ecg || acc

  companion object {
    fun from(selected: Set<String>) =
        PolarSelection(
            hr = "hr" in selected || "rr" in selected,
            ecg = "ecg" in selected,
            acc = "acc" in selected,
        )
  }
}

/** A Polar H10 over BLE: HR (with RR), ECG at 130 Hz and ACC at 50 Hz, ±8 G, raw SDK values. */
class PolarH10Sensor(
    context: Context,
    override val id: String,
    override val name: String,
    private val clock: Clock = SystemClockSource,
) : Tracker, OnDeviceTracker {
  override val kind = SensorKind.POLAR_H10
  override val streams =
      listOf(
          OfferedStream("hr", StreamType.HEART_RATE, Codec.POLAR_HR, "hr.log", listOf(1.0), order = 0),
          OfferedStream("rr", StreamType.RR_INTERVAL, Codec.POLAR_HR, "hr.log", order = 1),
          OfferedStream("ecg", StreamType.ECG, Codec.POLAR_ECG_V2, "ecg.log", listOf(130.0), order = 2),
          OfferedStream(
              "acc",
              StreamType.ACCELEROMETER,
              Codec.POLAR_ACC,
              "acc.log",
              listOf(50.0),
              listOf(com.rbmr.taigasleep.tracker.StreamRange(-8.0, 8.0, "g")),
              order = 3,
          ),
      )
  override val capabilities = TrackerCapabilities(clock = true, battery = true, firmware = true)

  // The SDK hands the address out with its connect callbacks; it is kept because the phone's own
  // Bluetooth view is asked for while the link may be down.
  @Volatile private var linkAddress: String? = null
  override val address: String?
    get() = linkAddress

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
  private val streamJobs = HashMap<String, Job>()
  // Collects the running streams. Every teardown replaces it, so nothing from the old link keeps
  // collecting after the streams were disposed.
  private var streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  // Calls that outlive one stream, like the clock sync a stream start waits for.
  private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  // Pending "first sample" checks, one per stream; cancelled when the streams are torn down.
  private val watchdogs = HashMap<String, ScheduledFuture<*>>()
  private var wanted: PolarSelection? = null
  private var listener: TrackerListener? = null
  private var userDisconnect = false

  // On-device recording: the strap stores HR/RR itself and the phone fetches it in the morning.
  private var onDeviceWanted: PolarSelection? = null
  private var onDeviceListener: TrackerListener? = null
  private var onDeviceResult: ((String?) -> Unit)? = null
  private var onDeviceActive = false
  // The exercise objects behind the entries last fetched, so removal can name what it removes.
  private val fetchedFromDevice = HashMap<String, PolarExerciseEntry>()

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

  /**
   * A dropped session can stay parked until a scan hears the strap advertise, and a strap still
   * held by the stale link never advertises. Replacing the SDK instance is the reset that clears
   * it; this sensor object, its flows and its registration by device id stay as they are.
   */
  override fun resetTransport() {
    awaitControl {
      // Any callback from the old instance is now an explicit teardown, not a drop to recover.
      userDisconnect = true
      disposeStreams()
      ready.clear()
      clockSynced = false
      clockSyncing = false
      clockWaiting = false
      PolarSdk.recreate(appContext)
    }
    _connection.value = ConnectionState.DISCONNECTED
    userDisconnect = false
  }

  override fun recordingModes(selected: Set<String>): Set<RecordingMode> =
      polarRecordingModes(selected)

  override fun startOnDevice(
      selected: Set<String>,
      listener: TrackerListener,
      onResult: (String?) -> Unit,
  ) {
    control.execute {
      onDeviceWanted = PolarSelection.from(selected)
      onDeviceListener = listener
      onDeviceResult = onResult
      maybeBeginOnDevice()
    }
  }

  override fun stopOnDevice() {
    awaitControl { stopTrackerRecording() }
  }

  override fun fetchOnDevice(): List<TrackerEntry> =
      awaitControl {
        // The link comes back by itself after the phone was off; wait for it, the outer fetch
        // timeout still governs how long this may take.
        awaitConnected()
        val entries = exerciseEntries()
        entries.map { entry ->
          val data = blockingCall(OFFLINE_TIMEOUT_S) { api.fetchExercise(id, entry) }
          fetchedFromDevice[entry.identifier] = entry
          TrackerEntry(
              id = entry.identifier,
              startUtcMs = entry.date.toInstant(ZoneOffset.UTC).toEpochMilli(),
              frames = rrFrames(entry.date.toInstant(ZoneOffset.UTC).toEpochMilli(), data.hrSamples),
          )
        }
      }

  /**
   * The strap stores beat intervals; each becomes one HR frame with the heart rate derived from it,
   * timed forward from the exercise's own start time. An interval of 0 or less produces no frame, so
   * the gap stays a gap.
   */
  private fun rrFrames(startUtcMs: Long, rrMs: List<Int>): List<TrackerFrame> {
    val frames = ArrayList<TrackerFrame>(rrMs.size)
    var timeMs = startUtcMs
    for (rr in rrMs) {
      if (rr <= 0) continue
      timeMs += rr
      val stamp = Stamp(timeMs * 1_000_000L, clock.elapsedNs())
      val payload =
          PolarCodec.encodeHr(
              stamp.phoneNs,
              stamp.elapsedNs,
              60_000 / rr,
              PolarCodec.hrFlags(contactSupported = false, contact = false, rrAvailable = true),
              intArrayOf(rr),
          )
      frames += TrackerFrame("hr", stamp, payload.bytes, payload.clamped)
    }
    return frames
  }

  override fun removeFromDevice(ids: List<String>) {
    if (ids.isEmpty()) return
    val removal =
        control.submit {
          try {
            blockingCall(REMOVE_TIMEOUT_S) { removeExercises(ids) }
          } catch (_: TimeoutCancellationException) {
            throw IOException("removing the tracker copy timed out")
          }
        }
    try {
      removal.get(REMOVE_TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      removal.cancel(true)
      throw e
    } catch (e: java.util.concurrent.ExecutionException) {
      throw e.cause ?: e
    } catch (_: java.util.concurrent.TimeoutException) {
      removal.cancel(true)
      throw IOException("removing the tracker copy timed out")
    }
  }

  /**
   * Removes [ids] from the strap. An entry fetched by an earlier process is not cached here, so the
   * strap's own list names it. An entry the strap no longer has is already gone.
   */
  private suspend fun removeExercises(ids: List<String>) {
    val stored = exerciseEntries().associateBy { it.identifier }
    val failed = mutableListOf<String>()
    for (entryId in ids) {
      val exercise = fetchedFromDevice[entryId] ?: stored[entryId] ?: continue
      try {
        withTimeout(TimeUnit.SECONDS.toMillis(OFFLINE_TIMEOUT_S)) {
          api.removeExercise(id, exercise)
        }
        fetchedFromDevice.remove(entryId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed += entryId
      }
    }
    if (failed.isNotEmpty()) {
      throw IOException("the tracker still holds ${failed.joinToString()}")
    }
  }

  /** Runs work on the sensor's serial control thread, cancelling it when the caller is interrupted. */
  private fun <T> awaitControl(work: () -> T): T {
    val future = control.submit<T> { work() }
    return try {
      future.get()
    } catch (e: InterruptedException) {
      future.cancel(true)
      throw e
    } catch (e: java.util.concurrent.ExecutionException) {
      throw e.cause ?: e
    }
  }

  /**
   * Runs one suspending SDK call to completion on the calling (control) thread, giving up after
   * [timeoutS]. The sensor's SDK calls stay on its control thread, in order, as before.
   */
  private fun <T> blockingCall(timeoutS: Long, call: suspend () -> T): T =
      runBlocking { withTimeout(TimeUnit.SECONDS.toMillis(timeoutS)) { call() } }

  override fun discardOnDevice() {
    control.execute {
      awaitConnected()
      stopTrackerRecording()
      runCatching {
        for (entry in exerciseEntries()) {
          blockingCall(OFFLINE_TIMEOUT_S) { api.removeExercise(id, entry) }
        }
      }
      onDeviceWanted = null
      onDeviceListener = null
      fetchedFromDevice.clear()
    }
  }

  override fun startStreams(selected: Set<String>, listener: TrackerListener) {
    control.execute {
      wanted = PolarSelection.from(selected)
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

  override fun readClock(): Long? =
      runCatching {
            awaitControl {
              val time = blockingCall(CLOCK_SYNC_S) { api.getLocalTime(id) }
              time.toInstant(ZoneOffset.UTC).toEpochMilli()
            }
          }
          .getOrNull()

  override fun setClock(utcMs: Long): Boolean =
      runCatching {
            awaitControl {
              blockingCall(CLOCK_SYNC_S) {
                api.setLocalTime(
                    id,
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(utcMs), ZoneOffset.UTC),
                )
              }
            }
            true
          }
          .getOrDefault(false)

  fun release() {
    PolarSdk.sensors.remove(id, this)
    streamScope.cancel()
    calls.cancel()
    control.shutdown()
    delivery.shutdown()
  }

  // Callbacks from the SDK.

  internal fun onConnecting(deviceAddress: String?) {
    noteAddress(deviceAddress)
    _connection.value = ConnectionState.CONNECTING
  }

  internal fun onConnected(deviceAddress: String?) {
    noteAddress(deviceAddress)
    _connection.value = ConnectionState.CONNECTED
    control.execute {
      maybeBeginOnDevice()
      maybeBeginStreams()
    }
    // If readiness is never reported, try anyway after a while.
    control.schedule({ maybeBeginStreams(force = true) }, FEATURE_WAIT_S, TimeUnit.SECONDS)
    control.schedule({ maybeBeginOnDevice(force = true) }, FEATURE_WAIT_S, TimeUnit.SECONDS)
  }

  private fun noteAddress(deviceAddress: String?) {
    if (!deviceAddress.isNullOrBlank()) linkAddress = deviceAddress
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
    if (cfg.ecg || cfg.acc || !cfg.hr) return
    if (onDeviceActive) return
    if (_connection.value != ConnectionState.CONNECTED) return
    if (PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING !in ready && !force) return
    val l = onDeviceListener ?: return
    try {
      // A service restart can find the strap already recording from the previous instance. Do not
      // start a second file; the fetch at the end drains whatever is there.
      val status = blockingCall(OFFLINE_TIMEOUT_S) { api.requestRecordingStatus(id) }
      if (status.ongoing) {
        onDeviceActive = true
        l.onNote("on_device_resume", "the tracker was already storing HR and RR")
        finishOnDeviceStart(null)
        return
      }
      // The exercise start time is read from the strap later, so set its clock first (the stream
      // path does the same). Recording matters more than the clock: carry on if it fails.
      setOnDeviceClock(l)
      // The H10 keeps one kind of sample per recording; beat intervals carry both HR and RR.
      blockingCall(OFFLINE_TIMEOUT_S) {
        api.startRecording(
            id,
            EXERCISE_ID,
            PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S,
            sampleType = PolarH10OfflineExerciseApi.SampleType.RR,
        )
      }
      onDeviceActive = true
      l.onNote("on_device_start", "the tracker is storing HR and RR on the device")
      finishOnDeviceStart(null)
    } catch (e: Exception) {
      Log.e(TAG, "on-device recording did not start", e)
      val reason = "on-device recording did not start: ${e.message ?: e.javaClass.simpleName}"
      l.onError(reason)
      onDeviceWanted = null
      finishOnDeviceStart(reason)
    }
  }

  private fun finishOnDeviceStart(error: String?) {
    val result = onDeviceResult ?: return
    onDeviceResult = null
    result(error)
  }

  /** Sets the strap's clock before an on-device recording starts, so its exercise start is real. */
  private fun setOnDeviceClock(l: TrackerListener) {
    try {
      blockingCall(CLOCK_SYNC_S) { api.setLocalTime(id, LocalDateTime.now(ZoneOffset.UTC)) }
      l.onNote("clock_sync", "strap clock set to the phone time (UTC)")
    } catch (e: Exception) {
      l.onError("could not set the strap clock: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun exerciseEntries(): List<PolarExerciseEntry> =
      blockingCall(OFFLINE_TIMEOUT_S) { api.listExercises(id).toList() }

  /** The phone may have been off all night: the link comes back by itself, so wait for it. */
  private fun awaitConnected() {
    val deadline = System.currentTimeMillis() + CONNECT_WAIT_S * 1_000
    while (_connection.value != ConnectionState.CONNECTED && System.currentTimeMillis() < deadline) {
      Thread.sleep(250)
    }
  }

  /** Always asks the strap: after an app restart this instance does not know it was recording. */
  private fun stopTrackerRecording() {
    onDeviceActive = false
    runCatching { blockingCall(OFFLINE_TIMEOUT_S) { api.stopRecording(id) } }
    onDeviceWanted = null
    onDeviceListener = null
    onDeviceResult = null
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
    if (streamJobs.isNotEmpty()) return
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
    if (cfg.hr) startHr(l)
    if (cfg.ecg) startEcg(l)
    if (cfg.acc) startAcc(l)
  }

  /** Sets the strap's clock to the phone's, then carries on starting the streams. */
  private fun syncClock(l: TrackerListener) {
    if (clockSyncing) return
    clockSyncing = true
    calls.launch {
      try {
        // UTC, so the strap's timestamps are UTC like the rest of the recording.
        withTimeout(TimeUnit.SECONDS.toMillis(CLOCK_SYNC_S)) {
          api.setLocalTime(id, LocalDateTime.now(ZoneOffset.UTC))
        }
        l.onNote("clock_sync", "strap clock set to the phone time (UTC)")
      } catch (e: CancellationException) {
        // Giving up on a slow strap is reported like any other failure; a teardown is not.
        if (e !is TimeoutCancellationException) throw e
        l.onError("could not set the strap clock: ${e.message}")
      } catch (e: Exception) {
        // Recording matters more than the clock: carry on, the phone time is in every batch.
        l.onError("could not set the strap clock: ${e.message}")
      } finally {
        control.execute {
          clockSyncing = false
          clockSynced = true
          maybeBeginStreams(force = true)
        }
      }
    }
  }

  private fun disposeStreams() {
    streamGeneration++
    watchdogs.values.forEach { it.cancel(false) }
    watchdogs.clear()
    streamScope.cancel()
    streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    streamJobs.clear()
    gotData.clear()
  }

  /** Notes the first sample of a stream, once per start. */
  private fun gotSample(name: String, l: TrackerListener) {
    if (gotData.add(name)) l.onNote("stream", "$name streaming")
  }

  /**
   * Restarts a stream that never produced a sample; a silent start failure loses the recording. The
   * check belongs to one stream start: cancel the previous one so a timer left over from before a
   * disconnect cannot fire after the reconnect and restart streams that just started.
   */
  private fun watch(name: String, l: TrackerListener, restart: () -> Unit) {
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

  private fun startHr(l: TrackerListener) {
    l.onNegotiated("hr", 1.0, emptyMap())
    stream("hr", l) {
      api.startHrStreaming(id).collect { data ->
        val stamp = Stamp.now(clock)
        gotSample("hr", l)
        delivery.execute {
          for (s in data.samples) {
            val enc =
                PolarCodec.encodeHr(
                    stamp.phoneNs,
                    stamp.elapsedNs,
                    s.hr,
                    PolarCodec.hrFlags(
                        s.contactStatusSupported,
                        s.contactStatus,
                        s.rrAvailable,
                    ),
                    s.rrsMs.toIntArray(),
                )
            l.onFrame(TrackerFrame("hr", stamp, enc.bytes, enc.clamped))
          }
        }
      }
    }
    watch("hr", l) { startHr(l) }
  }

  private fun startEcg(l: TrackerListener) {
    val wantedSettings = mapOf(SettingType.SAMPLE_RATE to 130, SettingType.RESOLUTION to 14)
    stream("ecg", l) {
      api.startEcgStreaming(id, settingsFor(PolarDeviceDataType.ECG, wantedSettings, l))
          .collect { data ->
            val stamp = Stamp.now(clock)
            gotSample("ecg", l)
            delivery.execute {
              val samples = data.samples.filterIsInstance<EcgSample>()
              if (samples.isNotEmpty()) {
                val t = LongArray(samples.size) { samples[it].timeStamp }
                val v = IntArray(samples.size) { samples[it].voltage }
                for (from in t.indices step PolarCodec.MAX_BATCH) {
                  val to = minOf(from + PolarCodec.MAX_BATCH, t.size)
                  val enc =
                      PolarCodec.encodeEcg(
                          stamp.phoneNs,
                          stamp.elapsedNs,
                          t.copyOfRange(from, to),
                          v.copyOfRange(from, to),
                      )
                  l.onFrame(TrackerFrame("ecg", stamp, enc.bytes, enc.clamped))
                }
              }
            }
          }
    }
    watch("ecg", l) { startEcg(l) }
  }

  private fun startAcc(l: TrackerListener) {
    val wantedSettings =
        mapOf(SettingType.SAMPLE_RATE to 50, SettingType.RESOLUTION to 16, SettingType.RANGE to 8)
    stream("acc", l) {
      api.startAccStreaming(id, settingsFor(PolarDeviceDataType.ACC, wantedSettings, l))
          .collect { data ->
            val stamp = Stamp.now(clock)
            gotSample("acc", l)
            delivery.execute {
              val s = data.samples
              if (s.isNotEmpty()) {
                val t = LongArray(s.size) { s[it].timeStamp }
                val xyz =
                    IntArray(s.size * 3) {
                      when (it % 3) {
                        0 -> s[it / 3].x
                        1 -> s[it / 3].y
                        else -> s[it / 3].z
                      }
                    }
                for (from in t.indices step PolarCodec.MAX_BATCH) {
                  val to = minOf(from + PolarCodec.MAX_BATCH, t.size)
                  val enc =
                      PolarCodec.encodeAcc(
                          stamp.phoneNs,
                          stamp.elapsedNs,
                          t.copyOfRange(from, to),
                          xyz.copyOfRange(from * 3, to * 3),
                      )
                  l.onFrame(TrackerFrame("acc", stamp, enc.bytes, enc.clamped))
                }
              }
            }
          }
    }
    watch("acc", l) { startAcc(l) }
  }

  /**
   * Picks the wanted setting value where the sensor offers it, else the highest it offers, and
   * reports a mismatch, because the format doc promises ECG 130 Hz and ACC 50 Hz ±8 G.
   */
  private suspend fun settingsFor(
      type: PolarDeviceDataType,
      wanted: Map<SettingType, Int>,
      l: TrackerListener,
  ): PolarSensorSetting {
    val available = api.requestStreamSettings(id, type)
    val chosen =
        available.settings.mapValues { (t, values) ->
          wanted[t]?.takeIf { it in values } ?: values.max()
        }
    if (chosen.any { (t, v) -> wanted[t] != null && wanted[t] != v }) {
      l.onError("$type settings differ from the wanted ones: $chosen")
    }
    val stream = if (type == PolarDeviceDataType.ECG) "ecg" else "acc"
    val rate = chosen[SettingType.SAMPLE_RATE]?.toDouble()
    l.onNegotiated(
        stream,
        rate,
        chosen.mapKeys { (t, _) -> settingName(t) }.mapValues { (_, value) -> value.toString() },
    )
    return PolarSensorSetting(chosen)
  }

  private fun settingName(type: SettingType): String =
      when (type) {
        SettingType.SAMPLE_RATE -> "rate_hz"
        SettingType.RESOLUTION -> "resolution_bits"
        SettingType.RANGE -> "range_g"
        else -> type.name.lowercase()
      }

  /**
   * Collects one stream on a coroutine of the stream scope: the teardown that ends the streams ends
   * it too, and one that fails while the link stays up is restarted as before.
   */
  private fun stream(name: String, l: TrackerListener, collect: suspend () -> Unit) {
    track(
        name,
        streamScope.launch {
          try {
            collect()
          } catch (e: Exception) {
            // One of our own cancellations, not a stream that stopped on its own.
            if (!isActive) throw e
            retryLater(name, l, e)
          }
        },
    )
  }

  private fun track(name: String, job: Job) {
    streamJobs.put(name, job)?.cancel()
  }

  private fun retryLater(name: String, l: TrackerListener, e: Throwable) {
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
    private const val REMOVE_TIMEOUT_S = 20L
    private const val CONNECT_WAIT_S = 60L
    private const val EXERCISE_ID = "TAIGA"

    /** Devices in range, as the SDK finds them. */
    fun scan(context: Context): Flow<PolarDeviceInfo> = PolarSdk.get(context).searchForDevice()
  }
}


/** Matches a Polar H10 and creates the H10 tracker. */
class PolarH10Driver : TrackerDriver {
  override val kind = SensorKind.POLAR_H10

  override fun matches(device: FoundSensor): Boolean =
      device.kind == SensorKind.POLAR_H10 || device.name.contains("H10", ignoreCase = true)

  override fun create(context: Context, device: FoundSensor): Tracker =
      PolarH10Sensor(context, device.id, device.name)
}
