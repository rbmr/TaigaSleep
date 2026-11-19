package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingStream
import com.rbmr.taigasleep.recording.store.SoundPayloads
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.StreamType

/**
 * Whether the phone's microphone records with a tracker, and how its frames are stored. Off unless
 * the user turns it on; turning it on starts at [COMPRESSED].
 *
 * The choice is per tracker and stored with its other stream choices, so the recording service reads
 * it when a recording starts.
 */
enum class SoundChoice(val wire: String) {
  OFF("off"),
  COMPRESSED("compressed"),
  UNCOMPRESSED("uncompressed");

  /** The payload codec of `docs/recording-format.md`, or null when sound is off. */
  val codec: String?
    get() =
        when (this) {
          OFF -> null
          COMPRESSED -> Codec.SOUND_OPUS
          UNCOMPRESSED -> Codec.SOUND_PCM
        }

  /** The `sound` stream this choice adds to a streamed recording, or null when it is off. */
  fun stream(): RecordingStream? {
    val codec = codec ?: return null
    return RecordingStream(
        id = SOUND_STREAM_ID,
        type = StreamType.SOUND,
        codec = codec,
        file = RecordingWriter.SOUND_LOG,
        rateHz = SoundPayloads.RATE_HZ.toDouble(),
    )
  }

  companion object {
    /** The stream id the writer, the meta and the uploader know the microphone by. */
    const val SOUND_STREAM_ID = "sound"

    fun fromWire(value: String?): SoundChoice = entries.firstOrNull { it.wire == value } ?: OFF

    /** The choice that stores this stream's codec, or off for a recording without sound. */
    fun fromCodec(codec: String?): SoundChoice =
        when (codec) {
          Codec.SOUND_OPUS -> COMPRESSED
          Codec.SOUND_PCM -> UNCOMPRESSED
          else -> OFF
        }
  }
}
