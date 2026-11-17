@file:Suppress("TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")

package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarSensorSetting
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.PolarCodec
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
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
) : Tracker, OnDeviceTracker {
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

  override fun connect() {
    userDisconnect = false
    _connection.value = ConnectionState.CONNECTING
    runCatching { seam.connect(id) }
        .onFailure { _connection.value = ConnectionState.DISCONNECTED }
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
                val stamp = Stamp.now(clock)
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
                if (data.samples.isEmpty()) return@collect
                val stamp = Stamp.now(clock)
                val n = data.samples.size
                val enc =
                    PolarCodec.encodePpi(
                        stamp.phoneNs,
                        stamp.elapsedNs,
                        LongArray(n) { data.samples[it].timeStamp.toLong() },
                        IntArray(n) { data.samples[it].ppi },
                        IntArray(n) { data.samples[it].errorEstimate },
                        IntArray(n) { data.samples[it].hr },
                        IntArray(n) {
                          val s = data.samples[it]
                          (if (s.skinContactSupported) 1 else 0) or
                              (if (s.skinContactStatus) 2 else 0) or
                              (if (s.blockerBit) 4 else 0)
                        },
                    )
                l.onFrame(TrackerFrame("ppi", stamp, enc.bytes, enc.clamped))
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
              val settings = settingsFor(PolarDeviceDataType.PPG)
              l.onNegotiated(
                  "ppg",
                  settings.settings[PolarSensorSetting.SettingType.SAMPLE_RATE]?.max()?.toDouble(),
                  settings.settings.entries.associate { it.key.name.lowercase() to it.value.max().toString() },
              )
              seam.startPpg(id, settings).collect { data: PolarPpgData -> emitPpg(l, data) }
            } catch (e: Exception) {
              if (isActive) l.onError("ppg stream error: ${e.message ?: e.javaClass.simpleName}")
            }
          }
    }
    if ("acc" in wanted && jobs["acc"] == null) {
      jobs["acc"] =
          streamScope.launch {
            try {
              val settings = settingsFor(PolarDeviceDataType.ACC)
              l.onNegotiated(
                  "acc",
                  settings.settings[PolarSensorSetting.SettingType.SAMPLE_RATE]?.max()?.toDouble(),
                  settings.settings.entries.associate { it.key.name.lowercase() to it.value.max().toString() },
              )
              seam.startAcc(id, settings).collect { data: PolarAccelerometerData -> emitAcc(l, data) }
            } catch (e: Exception) {
              if (isActive) l.onError("acc stream error: ${e.message ?: e.javaClass.simpleName}")
            }
          }
    }
  }

  private suspend fun settingsFor(type: PolarDeviceDataType): PolarSensorSetting =
      seam.streamSettings(id, type).maxSettings()

  private fun emitPpg(l: TrackerListener, data: PolarPpgData) {
    val samples = data.samples
    if (samples.isEmpty()) return
    val stamp = Stamp.now(clock)
    // The first channel carries the raw optical signal; the worker treats it as PPi's source.
    val enc =
        PolarCodec.encodePpg(
            stamp.phoneNs,
            stamp.elapsedNs,
            LongArray(samples.size) { samples[it].timeStamp },
            IntArray(samples.size) { samples[it].channelSamples.firstOrNull() ?: 0 },
            IntArray(samples.size) { samples[it].statusBits.fold(0) { acc, bit -> acc or bit } },
        )
    l.onFrame(TrackerFrame("ppg", stamp, enc.bytes, enc.clamped))
  }

  private fun emitAcc(l: TrackerListener, data: PolarAccelerometerData) {
    val samples = data.samples
    if (samples.isEmpty()) return
    val stamp = Stamp.now(clock)
    val xyz = IntArray(samples.size * 3)
    for (i in samples.indices) {
      xyz[i * 3] = samples[i].x
      xyz[i * 3 + 1] = samples[i].y
      xyz[i * 3 + 2] = samples[i].z
    }
    val enc =
        PolarCodec.encodeAcc(
            stamp.phoneNs,
            stamp.elapsedNs,
            LongArray(samples.size) { samples[it].timeStamp },
            xyz,
        )
    l.onFrame(TrackerFrame("acc", stamp, enc.bytes, enc.clamped))
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
              seam.startOffline(id, type, settingsFor(type))
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

  override fun removeFromDevice(ids: List<String>) {
    val entries = runBlocking { withTimeout(FETCH_S) { seam.listOffline(id).collectToList() } }
    for (entry in entries.filter { it.path in ids }) {
      runCatching { runBlocking { seam.removeOffline(id, entry) } }
    }
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
          data.data.samples.mapIndexed { index, s ->
            val stamp = Stamp((startMs + index * 1000L) * 1_000_000L, clock.elapsedNs())
            val enc =
                PolarCodec.encodeHr(
                    stamp.phoneNs,
                    stamp.elapsedNs,
                    s.hr,
                    PolarCodec.hrFlags(s.contactStatusSupported, s.contactStatus, s.rrAvailable),
                    s.rrsMs.toIntArray(),
                )
            TrackerFrame("hr", stamp, enc.bytes, enc.clamped)
          }
      is PolarOfflineRecordingData.PpiOfflineRecording -> {
        val samples = data.data.samples
        val stamp = Stamp(startMs * 1_000_000L, clock.elapsedNs())
        val enc =
            PolarCodec.encodePpi(
                stamp.phoneNs,
                stamp.elapsedNs,
                LongArray(samples.size) { startMs * 1_000_000L + it * 1_000_000L },
                IntArray(samples.size) { samples[it].ppi },
                IntArray(samples.size) { samples[it].errorEstimate },
                IntArray(samples.size) { samples[it].hr },
                IntArray(samples.size) {
                  val s = samples[it]
                  (if (s.skinContactSupported) 1 else 0) or
                      (if (s.skinContactStatus) 2 else 0) or
                      (if (s.blockerBit) 4 else 0)
                },
            )
        listOf(TrackerFrame("ppi", stamp, enc.bytes, enc.clamped))
      }
      is PolarOfflineRecordingData.PpgOfflineRecording -> {
        val samples = data.data.samples
        val stamp = Stamp(startMs * 1_000_000L, clock.elapsedNs())
        val enc =
            PolarCodec.encodePpg(
                stamp.phoneNs,
                stamp.elapsedNs,
                LongArray(samples.size) { samples[it].timeStamp },
                IntArray(samples.size) { samples[it].channelSamples.firstOrNull() ?: 0 },
                IntArray(samples.size) { samples[it].statusBits.fold(0) { acc, b -> acc or b } },
            )
        listOf(TrackerFrame("ppg", stamp, enc.bytes, enc.clamped))
      }
      is PolarOfflineRecordingData.AccOfflineRecording -> {
        val samples = data.data.samples
        val stamp = Stamp(startMs * 1_000_000L, clock.elapsedNs())
        val xyz = IntArray(samples.size * 3)
        for (i in samples.indices) {
          xyz[i * 3] = samples[i].x
          xyz[i * 3 + 1] = samples[i].y
          xyz[i * 3 + 2] = samples[i].z
        }
        val enc =
            PolarCodec.encodeAcc(
                stamp.phoneNs,
                stamp.elapsedNs,
                LongArray(samples.size) { samples[it].timeStamp },
                xyz,
            )
        listOf(TrackerFrame("acc", stamp, enc.bytes, enc.clamped))
      }
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
      device.kind == SensorKind.POLAR_VERITY ||
          device.name.contains("Verity", ignoreCase = true) ||
          device.name.contains("Polar Sense", ignoreCase = true)

  override fun create(context: Context, device: FoundSensor): Tracker =
      PolarVerityTracker(context, device.id, device.name)
}

class PolarLoopDriver : TrackerDriver {
  override val kind = SensorKind.POLAR_LOOP

  override fun matches(device: FoundSensor): Boolean =
      device.kind == SensorKind.POLAR_LOOP || device.name.contains("Loop", ignoreCase = true)

  override fun create(context: Context, device: FoundSensor): Tracker =
      PolarLoopTracker(context, device.id, device.name)
}
