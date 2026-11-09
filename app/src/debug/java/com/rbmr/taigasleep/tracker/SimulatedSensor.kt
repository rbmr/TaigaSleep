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
 * A sensor that needs no hardware: HR at 1 Hz with RR, ECG at 130 Hz in 73-sample batches and ACC
 * at 50 Hz in 36-sample batches, stamped like the Polar SDK does (Polar epoch, last sample is
 * "now"). Every [disconnectEveryMs] it drops for [disconnectForMs] to exercise reconnect.
 *
 * With [autoRun] the sensor drives itself from a timer; tests set it to false and call [pump] with
 * a fake clock.
 */
class SimulatedSensor(
    private val clock: Clock = SystemClockSource,
    private val autoRun: Boolean = true,
    private val disconnectEveryMs: Long = 3 * 60_000L,
    private val disconnectForMs: Long = 8_000L,
) : SleepSensor, OnDeviceTracker {
  override val kind = SensorKind.SIMULATED
  override val id = "SIM00001"
  override val name = "Simulated sensor"
  override val capabilities = SensorCapabilities(ecg = true, acc = true, rr = true)

  private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

  private val _battery = MutableStateFlow<Int?>(87)
  override val battery: StateFlow<Int?> = _battery.asStateFlow()

  private val _firmware = MutableStateFlow<String?>("sim-1.0")
  override val firmware: StateFlow<String?> = _firmware.asStateFlow()

  private var executor: ScheduledExecutorService? = null
  private var config: StreamConfig? = null
  private var listener: SampleListener? = null
  private var onDevice = false

  /** One recording the simulated tracker keeps on itself, keyed by [id]. */
  private class StoredEntry(val id: String, val startUtcMs: Long, val rrMs: MutableList<Int>)

  private val entries = mutableListOf<StoredEntry>()
  private var entryCounter = 0
  private var currentEntry: StoredEntry? = null

  /** How long [fetchOnDevice] waits before answering, to exercise the fetch timeout. */
  var fetchDelayMs: Long = 0

  /** When set, [fetchOnDevice] throws after this many entries, like a broken tracker read. */
  var failFetchAfterEntries: Int? = null

  /** False while the simulated strap keeps the link but sends nothing, until the streams restart. */
  private var emitting = true

  /**
   * The link stays up but no samples are delivered until the streams are started again, like a strap
   * whose notifications silently stopped. Lets a test drive the stream recovery of a live recording.
   */
  @Synchronized
  fun stopEmittingUntilRestarted() {
    emitting = false
    resetSchedule()
  }

  override fun recordingModes(config: StreamConfig): Set<RecordingMode> =
      setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE)

  // Next batch due, in elapsed-realtime ns; 0 means "start from the next pump".
  private var hrDue = 0L
  private var ecgDue = 0L
  private var accDue = 0L
  private var sampleIndexEcg = 0L
  private var sampleIndexAcc = 0L
  private var beat = 0L
  private var dropUntilNs = 0L
  private var nextDropNs = 0L

  @Synchronized
  override fun connect() {
    if (_connection.value != ConnectionState.DISCONNECTED) return
    _connection.value = ConnectionState.CONNECTING
    if (autoRun) {
      executor =
          Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sim-sensor") }
              .also { it.scheduleWithFixedDelay({ pump(clock.elapsedNs()) }, 0, 100, TimeUnit.MILLISECONDS) }
    }
    // Connecting takes a moment on real hardware; the first pump completes it.
  }

  @Synchronized
  override fun disconnect() {
    executor?.shutdownNow()
    executor = null
    _connection.value = ConnectionState.DISCONNECTED
  }

  @Synchronized
  override fun startStreams(config: StreamConfig, listener: SampleListener) {
    this.config = config
    this.listener = listener
    emitting = true
    resetSchedule()
  }

  @Synchronized
  override fun stopStreams() {
    config = null
    this.listener = null
    onDevice = false
    currentEntry = null
  }

  @Synchronized
  override fun startOnDevice(
      config: StreamConfig,
      listener: SampleListener,
      onResult: (String?) -> Unit,
  ) {
    this.config = config
    this.listener = listener
    onDevice = true
    // Each start is one exercise on the tracker; the service starts one, tests may add more.
    val entry =
        StoredEntry("sim-entry-${++entryCounter}", clock.phoneNs() / 1_000_000L, mutableListOf())
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
      TrackerEntry(entry.id, entry.startUtcMs, entry.rrMs.toList())
    }
  }

  @Synchronized
  override fun removeFromDevice(entries: List<TrackerEntry>) {
    val ids = entries.map { it.id }.toSet()
    this.entries.removeAll { it.id in ids }
    if (this.entries.isEmpty()) {
      onDevice = false
      currentEntry = null
    }
  }

  @Synchronized
  override fun discardOnDevice() {
    entries.clear()
    onDevice = false
    currentEntry = null
    config = null
    this.listener = null
  }

  /** The entries still stored on the simulated tracker, for tests. */
  @Synchronized fun storedEntries(): List<TrackerEntry> =
      entries.map { TrackerEntry(it.id, it.startUtcMs, it.rrMs.toList()) }

  @Synchronized fun storedEntryCount(): Int = entries.size

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
            // Simulated link loss: the streams stop and resume from "now" after the gap.
            dropUntilNs = nowNs + disconnectForMs * 1_000_000L
            nextDropNs = dropUntilNs + disconnectEveryMs * 1_000_000L
            _connection.value = ConnectionState.CONNECTING
            return
          }
    }
    val l = listener ?: return
    val c = config ?: return
    if (!emitting) return
    if (hrDue == 0L) {
      hrDue = nowNs + HR_PERIOD_NS
      ecgDue = nowNs + ECG_PERIOD_NS
      accDue = nowNs + ACC_PERIOD_NS
    }
    // Phone clock offset to elapsed time, so batches carry consistent timestamps.
    val phoneMinusElapsed = clock.phoneNs() - clock.elapsedNs()
    while (hrDue <= nowNs) {
      val stamp = stamp(hrDue, phoneMinusElapsed)
      val sample = nextHr()
      if (onDevice) currentEntry?.rrMs?.addAll(sample.rrMs.toList()) else l.onHr(stamp, sample)
      hrDue += HR_PERIOD_NS
    }
    while (c.ecg && ecgDue <= nowNs) {
      val stamp = stamp(ecgDue, phoneMinusElapsed)
      val batch = nextEcg(ecgDue + phoneMinusElapsed)
      if (!onDevice) l.onEcg(stamp, batch)
      ecgDue += ECG_PERIOD_NS
    }
    while (c.acc && accDue <= nowNs) {
      val stamp = stamp(accDue, phoneMinusElapsed)
      val batch = nextAcc(accDue + phoneMinusElapsed)
      if (!onDevice) l.onAcc(stamp, batch)
      accDue += ACC_PERIOD_NS
    }
  }

  override fun toString() = "SimulatedSensor"

  private fun stamp(elapsedNs: Long, offset: Long) = Stamp(elapsedNs + offset, elapsedNs)

  private fun nextHr(): HrSample {
    val t = beat++
    val hr = (58 + 6 * sin(2 * PI * t / 300.0)).roundToInt()
    val rr = (60_000.0 / hr + 20 * sin(2 * PI * t / 7.0)).roundToInt()
    return HrSample(hr, contactSupported = true, contact = true, rrAvailable = true, rrMs = intArrayOf(rr))
  }

  /** The last sample of the batch gets [endPhoneNs]; the others are spaced back from it. */
  private fun nextEcg(endPhoneNs: Long): EcgBatch {
    val end = endPhoneNs - POLAR_EPOCH_OFFSET_NS
    val first = end - ((ECG_BATCH - 1) * 1_000_000_000L) / ECG_HZ
    // 1e9 / 130 does not divide evenly, so these exercise the explicit timestamp mode.
    val t = LongArray(ECG_BATCH) { first + (it * 1_000_000_000L) / ECG_HZ }
    val v = IntArray(ECG_BATCH) { ecgMicrovolts(sampleIndexEcg++) }
    return EcgBatch(t, v)
  }

  private fun nextAcc(endPhoneNs: Long): AccBatch {
    val end = endPhoneNs - POLAR_EPOCH_OFFSET_NS
    val step = 1_000_000_000L / ACC_HZ
    // 20 ms spacing divides evenly, so these exercise the linear timestamp mode.
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

  /** A crude beat-shaped waveform at the simulated heart rate, in microvolts. */
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
