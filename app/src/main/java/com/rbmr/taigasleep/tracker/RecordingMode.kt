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
