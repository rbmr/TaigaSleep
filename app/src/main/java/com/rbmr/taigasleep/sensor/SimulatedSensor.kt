package com.rbmr.taigasleep.sensor

import com.rbmr.taigasleep.storage.Payloads.POLAR_EPOCH_OFFSET_NS
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
) : SleepSensor {
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
    resetSchedule()
  }

  @Synchronized
  override fun stopStreams() {
    config = null
    listener = null
  }

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
          if (nowNs >= nextDropNs && disconnectEveryMs > 0) {
            // Simulated link loss: the streams stop and resume from "now" after the gap.
            dropUntilNs = nowNs + disconnectForMs * 1_000_000L
            nextDropNs = dropUntilNs + disconnectEveryMs * 1_000_000L
            _connection.value = ConnectionState.CONNECTING
            return
          }
    }
    val l = listener ?: return
    val c = config ?: return
    if (hrDue == 0L) {
      hrDue = nowNs + HR_PERIOD_NS
      ecgDue = nowNs + ECG_PERIOD_NS
      accDue = nowNs + ACC_PERIOD_NS
    }
    // Phone clock offset to elapsed time, so batches carry consistent timestamps.
    val phoneMinusElapsed = clock.phoneNs() - clock.elapsedNs()
    while (hrDue <= nowNs) {
      l.onHr(stamp(hrDue, phoneMinusElapsed), nextHr())
      hrDue += HR_PERIOD_NS
    }
    while (c.ecg && ecgDue <= nowNs) {
      l.onEcg(stamp(ecgDue, phoneMinusElapsed), nextEcg(ecgDue + phoneMinusElapsed))
      ecgDue += ECG_PERIOD_NS
    }
    while (c.acc && accDue <= nowNs) {
      l.onAcc(stamp(accDue, phoneMinusElapsed), nextAcc(accDue + phoneMinusElapsed))
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
