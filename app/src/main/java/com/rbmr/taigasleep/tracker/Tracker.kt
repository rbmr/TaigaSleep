package com.rbmr.taigasleep.tracker

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

/** Both clocks, read when the tracker callback fired. */
data class Stamp(val phoneNs: Long, val elapsedNs: Long) {
  companion object {
    fun now(clock: Clock) = Stamp(clock.phoneNs(), clock.elapsedNs())
  }
}

/**
 * A tracker family, as `meta.json` writes it. Drivers register a value of their own; the app no
 * longer keeps a closed list, so a new driver needs no change here.
 */
@JvmInline
value class SensorKind(val wire: String) {
  companion object {
    val POLAR_H10 = SensorKind("polar-h10")
    val POLAR_VERITY = SensorKind("polar-verity-sense")
    val POLAR_LOOP = SensorKind("polar-loop")
    val BLE_HRS = SensorKind("ble-hrs")
    val SIMULATED = SensorKind("simulated")

    fun fromWire(value: String?): SensorKind? =
        value?.takeIf { it.isNotBlank() }?.let(::SensorKind)
  }
}

enum class ConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
}

/** The data a stream carries. Streams are how the app, the format and the analysis name data. */
enum class StreamType(val wire: String) {
  HEART_RATE("heart_rate"),
  RR_INTERVAL("rr_interval"),
  ECG("ecg"),
  ACCELEROMETER("accelerometer"),
  PPG("ppg"),
  PULSE_INTERVAL("pulse_interval"),
  GYROSCOPE("gyroscope"),
  MAGNETOMETER("magnetometer");

  companion object {
    fun fromWire(value: String?): StreamType? = entries.firstOrNull { it.wire == value }
  }
}

/** The value range a stream supports, in the unit its codec stores. */
data class StreamRange(val min: Double, val max: Double, val unit: String)

/**
 * One stream a tracker can record. [id] is what the recorder and the driver use to select it;
 * [codec] names the payload layout in `docs/recording-format.md`; [file] is the log the frames go
 * to. Streams that share a file are multiplexed in one payload, such as heart rate and R-R.
 */
data class OfferedStream(
    val id: String,
    val type: StreamType,
    val codec: String,
    val file: String,
    /** Rates the tracker supports, in Hz; empty when the codec defines its own spacing. */
    val ratesHz: List<Double> = emptyList(),
    val ranges: List<StreamRange> = emptyList(),
    /** The score the Configure sheet uses to order streams; lower first. */
    val order: Int = type.ordinal,
)

/** What the tracker can do beyond streaming. Each one is optional and hidden when false. */
data class TrackerCapabilities(
    val clock: Boolean = false,
    val battery: Boolean = true,
    val firmware: Boolean = true,
)

/** One encoded frame from a tracker, ready for the recording's stream log. */
class TrackerFrame(
    val stream: String,
    val stamp: Stamp,
    val payload: ByteArray,
    /** Samples whose value had to be clamped to fit the codec, for the error event. */
    val clamped: Int = 0,
)

/** Called on a tracker-owned thread, in order per stream. */
interface TrackerListener {
  fun onFrame(frame: TrackerFrame)

  /**
   * What the tracker actually negotiated for one stream, after start. Settings the tracker did not
   * choose (because it has no choice) are simply absent.
   */
  fun onNegotiated(stream: String, rateHz: Double?, settings: Map<String, String>) {}

  /** A stream problem worth an `error` event (start failure, stream error). */
  fun onError(message: String)

  /** A notable tracker event for the recording's events.jsonl (clock set, stream started). */
  fun onNote(type: String, message: String) {}
}

/** A device seen while scanning, as every driver sees it. */
data class FoundSensor(val kind: SensorKind, val id: String, val name: String, val rssi: Int)

/**
 * A tracker the app can record with. It reports the streams it offers and hands the recorder raw
 * device bytes; it does not know the recording format beyond its codec.
 */
interface Tracker {
  val kind: SensorKind
  val id: String
  val name: String

  /** The tracker's Bluetooth address, learned from the transport; null until it is known. */
  val address: String?
    get() = null

  /** The streams this tracker offers, in the order the app shows them. */
  val streams: List<OfferedStream>

  val capabilities: TrackerCapabilities

  val connection: StateFlow<ConnectionState>

  /**
   * The driver's own log lines at or after [sinceElapsedMs], oldest first, for diagnostics. Empty
   * for a tracker that keeps no log.
   */
  fun logLinesSince(sinceElapsedMs: Long): List<String> = emptyList()

  /** Battery percent, null until known. */
  val battery: StateFlow<Int?>

  /** Firmware version, null until known or when the tracker does not report one. */
  val firmware: StateFlow<String?>

  /**
   * The recording modes this selection can use. Streaming sends data to the phone; on-device keeps
   * it on the tracker. A tracker that can do both narrows the set by the selection, for example the
   * H10 can store only HR/RR on the device.
   */
  fun recordingModes(selected: Set<String>): Set<RecordingMode> =
      if (streams.isNotEmpty()) setOf(RecordingMode.STREAM) else emptySet()

  /** Starts connecting. Reconnection after a drop is handled by the tracker itself. */
  fun connect()

  fun disconnect()

  /**
   * Starts the tracker's transport over, so the next [connect] cannot be answered from a stale
   * session. Only a tracker whose Bluetooth stack can park a dropped link needs this.
   */
  fun resetTransport() {}

  /**
   * Starts the selected streams, now if connected and otherwise as soon as the tracker connects.
   * The streams are started again after every reconnect until [stopStreams].
   */
  fun startStreams(selected: Set<String>, listener: TrackerListener)

  /** Best effort; must not block. */
  fun stopStreams()

  /**
   * Releases what the tracker owns outside a connection — threads, executors and subscriptions —
   * when the app drops it for another one. The tracker is not used afterwards.
   */
  fun release() {}

  /** Reads the tracker's clock, in Unix epoch milliseconds; null when it cannot be read. */
  fun readClock(): Long? = null

  /** Sets the tracker's clock to [utcMs]; returns false when the tracker refused. */
  fun setClock(utcMs: Long): Boolean = false
}

/** One recording the tracker stored, identified by the tracker's own id for it. */
data class TrackerEntry(
    /** The tracker's identifier; the phone imports each one once. */
    val id: String,
    /** UTC milliseconds when the entry started, in the tracker's clock. */
    val startUtcMs: Long,
    /** Encoded frames, already stamped by the driver, in the order the tracker stored them. */
    val frames: List<TrackerFrame>,
)

/**
 * A tracker that can hold a recording itself. The phone starts it, leaves it alone overnight, and
 * asks for the data at the end.
 */
interface OnDeviceTracker {
  /**
   * Starts capturing on the tracker itself, as soon as it is connected. [onResult] is called once:
   * null when the tracker is storing, else the reason it is not.
   */
  fun startOnDevice(
      selected: Set<String>,
      listener: TrackerListener,
      onResult: (error: String?) -> Unit,
  )

  /** Stops the tracker's recording, without fetching or removing anything. */
  fun stopOnDevice()

  /**
   * Reads what the tracker stored. Nothing is removed: the caller removes the entries once their
   * frames are safely on the phone.
   */
  fun fetchOnDevice(): List<TrackerEntry>

  /** Removes the entries with these tracker ids; one the tracker no longer has counts as removed. */
  fun removeFromDevice(ids: List<String>)

  /** Stops capturing and removes the data from the tracker without downloading it. */
  fun discardOnDevice()
}

/**
 * The three-stream choice the app used before trackers reported their own streams. Call sites that
 * name heart rate, ECG and movement read naturally; a driver receives the stream ids instead.
 */
data class StreamConfig(val ecg: Boolean = false, val acc: Boolean = false, val hr: Boolean = true) {
  fun toSelection(): Set<String> =
      buildSet {
        if (hr) add("hr")
        if (ecg) add("ecg")
        if (acc) add("acc")
      }
}
