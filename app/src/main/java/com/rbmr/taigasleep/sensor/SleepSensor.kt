package com.rbmr.taigasleep.sensor

import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow

/** Source of the two timestamps stored with every frame. */
interface Clock {
  /** Unix epoch nanoseconds (wall clock). */
  fun phoneNs(): Long

  /** Monotonic nanoseconds since boot. */
  fun elapsedNs(): Long
}

object SystemClockSource : Clock {
  override fun phoneNs(): Long = System.currentTimeMillis() * 1_000_000L

  override fun elapsedNs(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Both clocks, read when the sensor callback fired. */
data class Stamp(val phoneNs: Long, val elapsedNs: Long) {
  companion object {
    fun now(clock: Clock) = Stamp(clock.phoneNs(), clock.elapsedNs())
  }
}

enum class SensorKind(val wire: String) {
  POLAR_H10("polar-h10"),
  SIMULATED("simulated");

  companion object {
    fun fromWire(value: String?): SensorKind? = entries.firstOrNull { it.wire == value }
  }
}

enum class ConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
}

data class SensorCapabilities(
    val ecg: Boolean,
    val acc: Boolean,
    val rr: Boolean,
    /** False for sensors that only store on the device and are read out afterwards. */
    val live: Boolean = true,
    val battery: Boolean = true,
    val firmware: Boolean = true,
)

/** Which streams to run. HR is always on. */
data class StreamConfig(val ecg: Boolean, val acc: Boolean)

/** A `PolarHrData.PolarHrSample`, field for field. */
class HrSample(
    val hr: Int,
    val contactSupported: Boolean,
    val contact: Boolean,
    val rrAvailable: Boolean,
    val rrMs: IntArray,
)

/** One SDK ECG batch: Polar-epoch nanosecond timestamps and raw microvolts. */
class EcgBatch(val timestampsNs: LongArray, val voltageUv: IntArray)

/** One SDK ACC batch: Polar-epoch nanosecond timestamps and raw milli-g, interleaved x, y, z. */
class AccBatch(val timestampsNs: LongArray, val xyzMg: IntArray)

/** Called on a sensor-owned thread, in order per stream. */
interface SampleListener {
  fun onHr(stamp: Stamp, sample: HrSample)

  fun onEcg(stamp: Stamp, batch: EcgBatch)

  fun onAcc(stamp: Stamp, batch: AccBatch)

  /** A stream problem worth an `error` event (start failure, stream error). */
  fun onError(message: String)

  /** A notable sensor event for the session's events.jsonl (clock set, stream started). */
  fun onNote(type: String, message: String) {}
}

interface SleepSensor {
  val kind: SensorKind
  val id: String
  val name: String
  val capabilities: SensorCapabilities

  val connection: StateFlow<ConnectionState>

  /** Battery percent, null until known. */
  val battery: StateFlow<Int?>

  val firmware: StateFlow<String?>

  /** Starts connecting. Reconnection after a drop is handled by the sensor itself. */
  fun connect()

  fun disconnect()

  /**
   * Starts the configured streams, now if connected and otherwise as soon as the sensor connects.
   * The streams are started again after every reconnect until [stopStreams].
   */
  fun startStreams(config: StreamConfig, listener: SampleListener)

  /** Best effort; must not block. */
  fun stopStreams()
}
