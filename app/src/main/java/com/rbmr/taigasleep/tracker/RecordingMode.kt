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

/**
 * A tracker that can hold a recording itself. The phone starts it, leaves it alone overnight, and
 * asks for the data at the end. Only [startStreams] style live work is part of [SleepSensor]; this
 * is the separate contract for the other mode.
 */
interface OnDeviceTracker {
  /**
   * Starts capturing on the tracker itself, as soon as it is connected. Data stays there until
   * [fetchOnDevice]. [onResult] is called once: null when the tracker is storing, else the reason
   * it is not. After that the phone does not need the tracker until the recording is stopped.
   */
  fun startOnDevice(config: StreamConfig, listener: SampleListener, onResult: (error: String?) -> Unit)

  /**
   * Replays everything captured since [startOnDevice] into [listener], as if it arrived live.
   * Returns false when the tracker has no data.
   */
  fun fetchOnDevice(listener: SampleListener): Boolean

  /** Stops capturing and removes the data from the tracker without downloading it. */
  fun discardOnDevice()
}
