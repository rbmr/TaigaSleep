@file:Suppress("TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")

package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.StreamRange
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.tracker.SystemClockSource
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.TrackerCapabilities
import com.rbmr.taigasleep.tracker.TrackerDriver
import com.rbmr.taigasleep.tracker.TrackerEntry
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A Polar tracker that streams what the SDK offers and can record on itself. The Verity Sense and
 * the Loop share this implementation; their profiles differ only in the streams they offer and
 * whether they stream live.
 */
abstract class PolarGenericTracker(
    protected val context: Context,
    override val id: String,
    override val name: String,
    private val seam: PolarSeam,
    private val clock: Clock = SystemClockSource,
) : Tracker, OnDeviceTracker, PolarSeam.ConnectionListener {
  private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
  private val _battery = MutableStateFlow<Int?>(null)
  override val battery: StateFlow<Int?> = _battery.asStateFlow()
  private val _firmware = MutableStateFlow<String?>(null)
  override val firmware: StateFlow<String?> = _firmware.asStateFlow()
  override val capabilities = TrackerCapabilities(clock = true, battery = true, firmware = true)

  private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "polar-generic-$id") }
  private val jobs = HashMap<String, Job>()
  private var streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var listener: TrackerListener? = null
  @Volatile private var selected: Set<String> = emptySet()
  @Volatile private var onDeviceSelected: Set<String> = emptySet()
  @Volatile private var onDeviceResult: ((String?) -> Unit)? = null
  @Volatile private var userDisconnect = false

  protected abstract fun liveStreams(): List<OfferedStream>

  protected abstract fun offlineStreams(): List<OfferedStream>

  protected open fun live(): Boolean = true

  override val streams: List<OfferedStream>
    get() = if (live()) liveStreams() else offlineStreams()

  override fun recordingModes(selected: Set<String>): Set<RecordingMode> {
    val offered = streams.associateBy { it.id }
    if (selected.isEmpty() || selected.any { it !in offered }) return emptySet()
    val streamable = live() && selected.any { offered[it]?.id in liveStreams().map { s -> s.id } }
    val stored = offlineStreams().any { it.id in selected }
    return buildSet {
      if (streamable) add(RecordingMode.STREAM)
      if (stored) add(RecordingMode.ON_DEVICE)
    }
  }

  init {
    seam.register(id, this)
  }

  override fun connect() {
    userDisconnect = false
    _connection.value = ConnectionState.CONNECTING
    runCatching { seam.connect(id) }
        .onFailure { _connection.value = ConnectionState.DISCONNECTED }
  }

  override fun onConnected(address: String?) {
    _connection.value = ConnectionState.CONNECTED
    if (selected.isNotEmpty()) startLive()
  }

  override fun onDisconnected() {
    disposeStreams()
    _connection.value =
        if (userDisconnect) ConnectionState.DISCONNECTED else ConnectionState.CONNECTING
  }

  override fun onBattery(level: Int?) {
    _battery.value = level
  }

  override fun onFirmware(value: String?) {
    _firmware.value = value
  }

  override fun disconnect() {
    userDisconnect = true
    disposeStreams()
    runCatching { seam.disconnect(id) }
    _connection.value = ConnectionState.DISCONNECTED
  }

  override fun startStreams(selected: Set<String>, listener: TrackerListener) {
    this.selected = selected
    this.listener = listener
    if (_connection.value == ConnectionState.CONNECTED) startLive() else connect()
  }

  override fun stopStreams() {
    selected = emptySet()
    listener = null
    disposeStreams()
  }

  /** Stops the tracker's threads and its registration when the app forgets it. */
  override fun release() {
    seam.unregister(id)
    control.shutdownNow()
    streamScope.cancel()
  }

  override fun readClock(): Long? =
      runCatching {
            runBlocking {
              withTimeout(CLOCK_S) { seam.getLocalTime(id).toInstant(ZoneOffset.UTC).toEpochMilli() }
            }
          }
          .getOrNull()

  override fun setClock(utcMs: Long): Boolean =
      runCatching {
            runBlocking {
              withTimeout(CLOCK_S) {
                seam.setLocalTime(
                    id,
                    java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(utcMs),
                        ZoneOffset.UTC,
                    ),
                )
              }
            }
            true
          }
          .getOrDefault(false)

  private fun startLive() {
    val l = listener ?: return
    val wanted = selected
    if (wanted.any { it == "hr" || it == "rr" } && jobs["hr"] == null) {
      jobs["hr"] =
          streamScope.launch {
            try {
              l.onNegotiated("hr", 1.0, emptyMap())
              seam.startHr(id).collect { data ->
                for (frame in PolarFrames.heartRate(Stamp.now(clock), data.samples)) {
                  l.onFrame(frame)
                }
              }
            } catch (e: Exception) {
              if (isActive) l.onError("hr stream error: ${e.message ?: e.javaClass.simpleName}")
            }
          }
    }
    if ("ppi" in wanted && jobs["ppi"] == null) {
      jobs["ppi"] =
          streamScope.launch {
            try {
              l.onNegotiated("ppi", null, emptyMap())
              seam.startPpi(id).collect { data ->
                for (frame in PolarFrames.ppi(Stamp.now(clock), data.samples)) l.onFrame(frame)
              }
            } catch (e: Exception) {
              if (isActive) l.onError("ppi stream error: ${e.message ?: e.javaClass.simpleName}")
            }
          }
    }
    if ("ppg" in wanted && jobs["ppg"] == null) {
      jobs["ppg"] =
          streamScope.launch {
            try {
              val settings =
                  PolarSettings.negotiate(seam, id, PolarDeviceDataType.PPG, emptyMap(), l)
              seam.startPpg(id, settings).collect { data ->
                for (frame in PolarFrames.ppg(Stamp.now(clock), data.samples)) l.onFrame(frame)
              }
            } catch (e: Exception) {
              if (isActive) l.onError("ppg stream error: ${e.message ?: e.javaClass.simpleName}")
            }
          }
    }
    if ("acc" in wanted && jobs["acc"] == null) {
      jobs["acc"] =
          streamScope.launch {
            try {
              val settings =
                  PolarSettings.negotiate(seam, id, PolarDeviceDataType.ACC, emptyMap(), l)
              seam.startAcc(id, settings).collect { data ->
                for (frame in PolarFrames.accelerometer(Stamp.now(clock), data.samples)) {
                  l.onFrame(frame)
                }
              }
            } catch (e: Exception) {
              if (isActive) l.onError("acc stream error: ${e.message ?: e.javaClass.simpleName}")
            }
          }
    }
  }

  private fun disposeStreams() {
    streamScope.cancel()
    streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    jobs.clear()
  }

  // On-device recording.

  override fun startOnDevice(
      selected: Set<String>,
      listener: TrackerListener,
      onResult: (String?) -> Unit,
  ) {
    onDeviceSelected = selected
    onDeviceResult = onResult
    control.execute {
      try {
        ensureConnected()
        runBlocking {
          withTimeout(FETCH_S) {
            for (type in offlineTypes(selected)) {
              seam.startOffline(id, type, PolarSettings.best(seam, id, type))
            }
          }
        }
        listener.onNote("on_device_start", "the tracker is storing ${selected.joinToString()}")
        onDeviceResult?.invoke(null)
      } catch (e: Exception) {
        listener.onError("on-device recording did not start: ${e.message}")
        onDeviceResult?.invoke(e.message ?: e.javaClass.simpleName)
      } finally {
        onDeviceResult = null
      }
    }
  }

  override fun stopOnDevice() {
    val types = offlineTypes(onDeviceSelected.ifEmpty { selected })
    for (type in types) runCatching { runBlocking { seam.stopOffline(id, type) } }
  }

  override fun fetchOnDevice(): List<TrackerEntry> {
    val entries = runBlocking { withTimeout(FETCH_S) { seam.listOffline(id).collectToList() } }
    return entries.map { entry ->
      val data = runBlocking { withTimeout(FETCH_S) { seam.readOffline(id, entry) } }
      TrackerEntry(
          id = entry.path,
          startUtcMs = entry.date.toInstant(ZoneOffset.UTC).toEpochMilli(),
          frames = framesOf(data),
      )
    }
  }

  /**
   * Throws when the tracker keeps an entry, so the caller knows the removal is still to do instead
   * of clearing it and leaving data that keeps filling the tracker's storage.
   */
  override fun removeFromDevice(ids: List<String>) {
    val entries = runBlocking { withTimeout(FETCH_S) { seam.listOffline(id).collectToList() } }
    val failed = mutableListOf<String>()
    for (entry in entries.filter { it.path in ids }) {
      try {
        runBlocking { withTimeout(FETCH_S) { seam.removeOffline(id, entry) } }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        failed += entry.path
      }
    }
    if (failed.isNotEmpty()) throw IOException("the tracker still holds ${failed.joinToString()}")
  }

  override fun discardOnDevice() {
    stopOnDevice()
    val entries = runCatching {
          runBlocking { withTimeout(FETCH_S) { seam.listOffline(id).collectToList() } }
        }
        .getOrDefault(emptyList())
    for (entry in entries) runCatching { runBlocking { seam.removeOffline(id, entry) } }
  }

  private fun ensureConnected() {
    val deadline = System.currentTimeMillis() + CONNECT_WAIT_S * 1000
    if (_connection.value == ConnectionState.DISCONNECTED) connect()
    while (_connection.value != ConnectionState.CONNECTED && System.currentTimeMillis() < deadline) {
      Thread.sleep(100)
    }
  }

  private fun offlineTypes(selected: Set<String>): List<PolarDeviceDataType> =
      selected.mapNotNull {
        when (it) {
          "hr",
          "rr" -> PolarDeviceDataType.HR
          "ppi" -> PolarDeviceDataType.PPI
          "ppg" -> PolarDeviceDataType.PPG
          "acc" -> PolarDeviceDataType.ACC
          else -> null
        }
      }.distinct()

  /** Turns what the SDK read back into the same codec frames a live stream would have produced. */
  private fun framesOf(data: PolarOfflineRecordingData): List<TrackerFrame> {
    val startMs = data.startTime.toInstant(ZoneOffset.UTC).toEpochMilli()
    return when (data) {
      is PolarOfflineRecordingData.HrOfflineRecording ->
          PolarFrames.heartRateFromRecording(startMs, data.data.samples, clock)
      is PolarOfflineRecordingData.PpiOfflineRecording ->
          PolarFrames.ppiFromRecording(startMs, data.data.samples, clock.elapsedNs())
      is PolarOfflineRecordingData.PpgOfflineRecording ->
          PolarFrames.ppg(Stamp(startMs * 1_000_000L, clock.elapsedNs()), data.data.samples)
      is PolarOfflineRecordingData.AccOfflineRecording ->
          PolarFrames.accelerometer(
              Stamp(startMs * 1_000_000L, clock.elapsedNs()),
              data.data.samples,
          )
      else -> emptyList()
    }
  }

  private companion object {
    const val CLOCK_S = 5_000L
    const val CONNECT_WAIT_S = 60L
    const val FETCH_S = 120_000L
  }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectToList(): List<T> {
  val list = ArrayList<T>()
  collect { list += it }
  return list
}

/** Polar Verity Sense: live HR/RR, pulse intervals, PPG and ACC, and on-device recordings. */
class PolarVerityTracker(
    context: Context,
    id: String,
    name: String,
    seam: PolarSeam = PolarSdkSeam.install(context),
    clock: Clock = SystemClockSource,
) : PolarGenericTracker(context, id, name, seam, clock) {
  override val kind = SensorKind.POLAR_VERITY

  override fun liveStreams(): List<OfferedStream> =
      listOf(
          OfferedStream("hr", StreamType.HEART_RATE, Codec.POLAR_HR, "hr.log", listOf(1.0), order = 0),
          OfferedStream("rr", StreamType.RR_INTERVAL, Codec.POLAR_HR, "hr.log", order = 1),
          OfferedStream("ppi", StreamType.PULSE_INTERVAL, Codec.POLAR_PPI, "ppi.log", order = 2),
          OfferedStream("ppg", StreamType.PPG, Codec.POLAR_PPG, "ppg.log", listOf(55.0), order = 3),
          OfferedStream(
              "acc",
              StreamType.ACCELEROMETER,
              Codec.POLAR_ACC,
              "acc.log",
              listOf(52.0),
              listOf(StreamRange(-8.0, 8.0, "g")),
              order = 4,
          ),
      )

  override fun offlineStreams(): List<OfferedStream> =
      liveStreams().filter { it.id != "rr" }
}

/** Polar Loop: a screenless tracker that stores heart rate on itself. */
class PolarLoopTracker(
    context: Context,
    id: String,
    name: String,
    seam: PolarSeam = PolarSdkSeam.install(context),
    clock: Clock = SystemClockSource,
) : PolarGenericTracker(context, id, name, seam, clock) {
  override val kind = SensorKind.POLAR_LOOP

  override fun live(): Boolean = false

  override fun liveStreams(): List<OfferedStream> = emptyList()

  override fun offlineStreams(): List<OfferedStream> =
      listOf(
          OfferedStream("hr", StreamType.HEART_RATE, Codec.POLAR_HR, "hr.log", listOf(1.0), order = 0),
          OfferedStream("rr", StreamType.RR_INTERVAL, Codec.POLAR_HR, "hr.log", order = 1),
      )
}

class PolarVerityDriver : TrackerDriver {
  override val kind = SensorKind.POLAR_VERITY

  override fun matches(device: FoundSensor): Boolean =
      device.kind == kind || polarKindOf(device.name) == kind

  override fun create(context: Context, device: FoundSensor): Tracker =
      PolarVerityTracker(context, device.id, device.name)
}

class PolarLoopDriver : TrackerDriver {
  override val kind = SensorKind.POLAR_LOOP

  override fun matches(device: FoundSensor): Boolean =
      device.kind == kind || polarKindOf(device.name) == kind

  override fun create(context: Context, device: FoundSensor): Tracker =
      PolarLoopTracker(context, device.id, device.name)
}
