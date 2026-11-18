@file:Suppress("ReturnCount", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "TooManyFunctions")

package com.rbmr.taigasleep.tracker

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tracker that needs no hardware and can be made to behave badly. With [autoRun] it drives itself
 * from a timer; a test sets [autoRun] to false and calls [pump] with its own clock.
 *
 * It streams H10-shaped data by default: HR at 1 Hz with RR, ECG at 130 Hz in 73-sample batches and
 * ACC at 50 Hz in 36-sample batches, stamped like the Polar SDK does. [drop], [stall],
 * [mismatchedSettings], [batteryLevel] and [failFetchAfterEntries] cover the failures a test needs.
 */
class FakeTracker(
    private val clock: Clock = SystemClockSource,
    private val autoRun: Boolean = true,
    private val disconnectEveryMs: Long = 3 * 60_000L,
    private val disconnectForMs: Long = 8_000L,
    override val kind: SensorKind = SensorKind.SIMULATED,
    override val id: String = "FAKE0001",
    override val name: String = "Fake tracker",
) : Tracker, OnDeviceTracker {
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
              listOf(StreamRange(-8.0, 8.0, "g")),
              order = 3,
          ),
      )
  override val capabilities = TrackerCapabilities(clock = true, battery = true, firmware = true)

  private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
  private val _battery = MutableStateFlow<Int?>(87)
  override val battery: StateFlow<Int?> = _battery.asStateFlow()
  private val _firmware = MutableStateFlow<String?>("fake-1.0")
  override val firmware: StateFlow<String?> = _firmware.asStateFlow()

  private var executor: ScheduledExecutorService? = null
  private var selected: Set<String> = emptySet()
  private var listener: TrackerListener? = null
  private var onDevice = false

  /** The names of the streams a test wants to report as negotiated. */
  var negotiatedRate: Double? = null
  var negotiatedSettings: Map<String, String> = emptyMap()

  /** True while the link stays up but no frames arrive, until the streams are started again. */
  private var emitting = true

  /** When set, the fetch throws after this many entries, like a broken tracker read. */
  var failFetchAfterEntries: Int? = null

  /** How long the fetch waits before answering, to exercise the fetch timeout. */
  var fetchDelayMs: Long = 0

  private class StoredEntry(
      val id: String,
      val startUtcMs: Long,
      val frames: MutableList<TrackerFrame>,
  )

  private val entries = mutableListOf<StoredEntry>()
  private var entryCounter = 0
  private var currentEntry: StoredEntry? = null

  @Synchronized
  fun setBattery(percent: Int?) {
    _battery.value = percent
  }

  @Synchronized
  fun setFirmware(value: String?) {
    _firmware.value = value
  }

  /** A drop that the tracker recovers from by itself, like a lost link. */
  @Synchronized
  fun drop(forMs: Long = 5_000) {
    dropUntilNs = clock.elapsedNs() + forMs * 1_000_000L
    nextDropNs = dropUntilNs + disconnectEveryMs * 1_000_000L
    _connection.value = ConnectionState.CONNECTING
    resetSchedule()
  }

  /** The link stays up but no frames are delivered until the streams are started again. */
  @Synchronized
  fun stall() {
    emitting = false
    resetSchedule()
  }

  /** Kept for the simulated source's name: [stall]. */
  fun stopEmittingUntilRestarted() = stall()

  @Synchronized
  override fun connect() {
    if (_connection.value != ConnectionState.DISCONNECTED) return
    _connection.value = ConnectionState.CONNECTING
    if (autoRun) {
      executor =
          Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-tracker") }
              .also {
                it.scheduleWithFixedDelay({ pump(clock.elapsedNs()) }, 0, 100, TimeUnit.MILLISECONDS)
              }
    }
  }

  @Synchronized
  override fun disconnect() {
    executor?.shutdownNow()
    executor = null
    _connection.value = ConnectionState.DISCONNECTED
  }

  override fun recordingModes(selected: Set<String>): Set<RecordingMode> =
      when {
        selected.isEmpty() -> emptySet()
        selected.any { it != "hr" && it != "rr" } -> setOf(RecordingMode.STREAM)
        else -> setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE)
      }

  fun startStreams(config: StreamConfig, listener: TrackerListener) =
      startStreams(config.toSelection(), listener)

  @Synchronized
  override fun startStreams(selected: Set<String>, listener: TrackerListener) {
    this.selected = selected
    this.listener = listener
    emitting = true
    resetSchedule()
    for (id in selected) {
      listener.onNegotiated(id, negotiatedRate, negotiatedSettings)
    }
  }

  @Synchronized
  override fun stopStreams() {
    selected = emptySet()
    listener = null
    onDevice = false
    currentEntry = null
  }

  /** Stops the simulation timer when the app forgets this tracker. */
  @Synchronized
  override fun release() {
    disconnect()
  }

  fun startOnDevice(
      config: StreamConfig,
      listener: TrackerListener,
      onResult: (String?) -> Unit,
  ) = startOnDevice(config.toSelection(), listener, onResult)

  @Synchronized
  override fun startOnDevice(
      selected: Set<String>,
      listener: TrackerListener,
      onResult: (String?) -> Unit,
  ) {
    this.selected = selected
    this.listener = listener
    onDevice = true
    val entry = StoredEntry("fake-entry-${++entryCounter}", clock.phoneNs() / 1_000_000L, mutableListOf())
    entries += entry
    currentEntry = entry
    resetSchedule()
    onResult(null)
  }

  @Synchronized
  override fun stopOnDevice() {
    onDevice = false
    currentEntry = null
  }

  @Synchronized
  override fun fetchOnDevice(): List<TrackerEntry> {
    val delay = fetchDelayMs
    if (delay > 0) Thread.sleep(delay)
    val limit = failFetchAfterEntries
    return entries.mapIndexed { index, entry ->
      if (limit != null && index >= limit) {
        throw IOException("simulated tracker read failure after $limit entries")
      }
      TrackerEntry(entry.id, entry.startUtcMs, entry.frames.toList())
    }
  }

  @Synchronized
  override fun removeFromDevice(ids: List<String>) {
    val wanted = ids.toSet()
    entries.removeAll { it.id in wanted }
    if (entries.isEmpty()) {
      onDevice = false
      currentEntry = null
    }
  }

  @Synchronized
  override fun discardOnDevice() {
    entries.clear()
    onDevice = false
    currentEntry = null
    selected = emptySet()
    listener = null
  }

  @Synchronized fun storedEntries(): List<TrackerEntry> =
      entries.map { TrackerEntry(it.id, it.startUtcMs, it.frames.toList()) }

  @Synchronized fun storedEntryCount(): Int = entries.size

  override fun readClock(): Long? = clock.phoneNs() / 1_000_000L

  override fun setClock(utcMs: Long): Boolean = true

  private var hrDue = 0L
  private var ecgDue = 0L
  private var accDue = 0L
  private var sampleIndexEcg = 0L
  private var sampleIndexAcc = 0L
  private var beat = 0L
  private var dropUntilNs = 0L
  private var nextDropNs = 0L

  private fun resetSchedule() {
    hrDue = 0
    ecgDue = 0
    accDue = 0
  }

  /** Advances the simulation to [nowNs] (elapsed-realtime ns), emitting whatever is due. */
  @Synchronized
  fun pump(nowNs: Long) {
    when (_connection.value) {
      ConnectionState.DISCONNECTED -> return
      ConnectionState.CONNECTING -> {
        if (nowNs < dropUntilNs) return
        _connection.value = ConnectionState.CONNECTED
        nextDropNs = nowNs + disconnectEveryMs * 1_000_000L
        resetSchedule()
      }
      ConnectionState.CONNECTED ->
          if (!onDevice && nowNs >= nextDropNs && disconnectEveryMs > 0) {
            dropUntilNs = nowNs + disconnectForMs * 1_000_000L
            nextDropNs = dropUntilNs + disconnectEveryMs * 1_000_000L
            _connection.value = ConnectionState.CONNECTING
            return
          }
    }
    val l = listener ?: return
    if (selected.isEmpty()) return
    if (!emitting) return
    if (hrDue == 0L) {
      hrDue = nowNs + HR_PERIOD_NS
      ecgDue = nowNs + ECG_PERIOD_NS
      accDue = nowNs + ACC_PERIOD_NS
    }
    val phoneMinusElapsed = clock.phoneNs() - clock.elapsedNs()
    while (hrDue <= nowNs) {
      if (!onDevice && ("hr" in selected || "rr" in selected)) {
        val stamp = stamp(hrDue, phoneMinusElapsed)
        val sample = nextHr()
        val enc =
            PolarCodec.encodeHr(
                stamp.phoneNs,
                stamp.elapsedNs,
                sample.hr,
                PolarCodec.hrFlags(
                    sample.contactSupported,
                    sample.contact,
                    sample.rrAvailable,
                ),
                sample.rrMs,
            )
        l.onFrame(TrackerFrame("hr", stamp, enc.bytes, enc.clamped))
      }
      if (onDevice) {
        val stamp = stamp(hrDue, phoneMinusElapsed)
        val sample = nextHr()
        val enc =
            PolarCodec.encodeHr(
                stamp.phoneNs,
                stamp.elapsedNs,
                sample.hr,
                PolarCodec.hrFlags(false, false, true),
                sample.rrMs,
            )
        currentEntry?.frames?.add(TrackerFrame("hr", stamp, enc.bytes, enc.clamped))
      }
      hrDue += HR_PERIOD_NS
    }
    while ("ecg" in selected && ecgDue <= nowNs) {
      val stamp = stamp(ecgDue, phoneMinusElapsed)
      val batch = nextEcg(ecgDue + phoneMinusElapsed)
      val enc = PolarCodec.encodeEcg(stamp.phoneNs, stamp.elapsedNs, batch.timestampsNs, batch.voltageUv)
      if (!onDevice) l.onFrame(TrackerFrame("ecg", stamp, enc.bytes, enc.clamped))
      ecgDue += ECG_PERIOD_NS
    }
    while ("acc" in selected && accDue <= nowNs) {
      val stamp = stamp(accDue, phoneMinusElapsed)
      val batch = nextAcc(accDue + phoneMinusElapsed)
      val enc = PolarCodec.encodeAcc(stamp.phoneNs, stamp.elapsedNs, batch.timestampsNs, batch.xyzMg)
      if (!onDevice) l.onFrame(TrackerFrame("acc", stamp, enc.bytes, enc.clamped))
      accDue += ACC_PERIOD_NS
    }
  }

  override fun toString() = "FakeTracker"

  private fun stamp(elapsedNs: Long, offset: Long) = Stamp(elapsedNs + offset, elapsedNs)

  private fun nextHr(): HrSample {
    val t = beat++
    val hr = (58 + 6 * sin(2 * PI * t / 300.0)).roundToInt()
    val rr = (60_000.0 / hr + 20 * sin(2 * PI * t / 7.0)).roundToInt()
    return HrSample(hr, contactSupported = true, contact = true, rrAvailable = true, rrMs = intArrayOf(rr))
  }

  private fun nextEcg(endPhoneNs: Long): EcgBatch {
    val end = endPhoneNs - POLAR_EPOCH_OFFSET_NS
    val first = end - ((ECG_BATCH - 1) * 1_000_000_000L) / ECG_HZ
    val t = LongArray(ECG_BATCH) { first + (it * 1_000_000_000L) / ECG_HZ }
    val v = IntArray(ECG_BATCH) { ecgMicrovolts(sampleIndexEcg++) }
    return EcgBatch(t, v)
  }

  private fun nextAcc(endPhoneNs: Long): AccBatch {
    val end = endPhoneNs - POLAR_EPOCH_OFFSET_NS
    val step = 1_000_000_000L / ACC_HZ
    val t = LongArray(ACC_BATCH) { end - (ACC_BATCH - 1 - it) * step }
    val xyz = IntArray(ACC_BATCH * 3)
    for (i in 0 until ACC_BATCH) {
      val e = sampleIndexAcc++ / ACC_HZ.toDouble()
      xyz[i * 3] = (980 * sin(2 * PI * e / 1.7)).roundToInt()
      xyz[i * 3 + 1] = (60 * sin(2 * PI * e / 3.1)).roundToInt()
      xyz[i * 3 + 2] = (1000 + 20 * sin(2 * PI * e / 0.8)).roundToInt()
    }
    return AccBatch(t, xyz)
  }

  private fun ecgMicrovolts(i: Long): Int {
    val phase = (i % (ECG_HZ * 60 / 58)) / ECG_HZ.toDouble()
    val qrs = 1200 * exp(-((phase - 0.2) * (phase - 0.2)) / 0.00012)
    val tWave = 300 * exp(-((phase - 0.45) * (phase - 0.45)) / 0.004)
    return (qrs + tWave).roundToInt()
  }

  private companion object {
    const val ECG_HZ = 130L
    const val ECG_BATCH = 73
    const val ACC_HZ = 50L
    const val ACC_BATCH = 36
    const val HR_PERIOD_NS = 1_000_000_000L
    const val ECG_PERIOD_NS = ECG_BATCH * 1_000_000_000L / ECG_HZ
    const val ACC_PERIOD_NS = ACC_BATCH * 1_000_000_000L / ACC_HZ
  }
}


/** The debug build's old name for the scriptable fake tracker. */
typealias SimulatedSensor = FakeTracker
