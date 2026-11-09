package com.rbmr.taigasleep.tracker

/**
 * How a tracker captures a recording.
 *
 * - [STREAM] sends the data to the phone while recording. The phone must stay awake, so the
 *   preventing-shutdown flow applies.
 * - [ON_DEVICE] stores the data on the tracker and the phone fetches it at the end, so the phone
 *   does not have to stay alive overnight.
 *
 * The mode follows from the tracker and the chosen streams; it is not a free-standing user
 * preference. See docs/architecture.md, "Recording mode".
 */
enum class RecordingMode(val wire: String) {
  STREAM("stream"),
  ON_DEVICE("on_device");

  companion object {
    fun fromWire(value: String?): RecordingMode? = entries.firstOrNull { it.wire == value }
  }
}

/** One recording the tracker stored, identified by the tracker's own id for it. */
data class TrackerEntry(
    /** The tracker's identifier; the phone imports each one once. */
    val id: String,
    /** UTC milliseconds when the exercise started, in the tracker's clock. */
    val startUtcMs: Long,
    /** The stored beat intervals in milliseconds, in order. A value of 0 or less is a gap. */
    val rrMs: List<Int>,
)

/**
 * A tracker that can hold a recording itself. The phone starts it, leaves it alone overnight, and
 * asks for the data at the end. Only [startStreams] style live work is part of [SleepSensor]; this
 * is the separate contract for the other mode.
 */
interface OnDeviceTracker {
  /**
   * Starts capturing on the tracker itself, as soon as it is connected. Data stays there until it is
   * fetched and removed. [onResult] is called once: null when the tracker is storing, else the
   * reason it is not. After that the phone does not need the tracker until the recording is stopped.
   */
  fun startOnDevice(config: StreamConfig, listener: SampleListener, onResult: (error: String?) -> Unit)

  /** Stops the tracker's recording, without fetching or removing anything. */
  fun stopOnDevice()

  /**
   * Reads what the tracker stored. Nothing is removed: the caller removes the entries once their
   * samples are safely on the phone.
   */
  fun fetchOnDevice(): List<TrackerEntry>

  /** Removes entries the phone has stored, keyed by their tracker ids. */
  fun removeFromDevice(entries: List<TrackerEntry>)

  /** Stops capturing and removes the data from the tracker without downloading it. */
  fun discardOnDevice()
}
