package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingStream
import com.rbmr.taigasleep.recording.store.SoundPayloads
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.StreamType
import java.io.File

/** Free space on the phone. Injectable so a test can fill the disk without filling a disk. */
fun interface SpaceProvider {
  fun availableBytes(): Long
}

/** The free space the phone reports for the storage [dir] lives on. */
class PhoneSpace(private val dir: File) : SpaceProvider {
  // Usable space, not allocatable space: the recorder must fit inside what is free right now. Had
  // the system already agreed to clear caches for us, that is its decision and not a reservation.
  @Suppress("UsableSpace")
  override fun availableBytes(): Long = dir.usableSpace
}

/**
 * How much free space a recording needs, derived from the frame sizes of the v1 format described in
 * `docs/recording-format.md`.
 *
 * A recording starts only when the phone can hold a whole night with the chosen streams. While it
 * runs, less than [LOW_SPACE_HOURS] of stream time left is a storage problem, not yet a failure.
 */
object StorageSpace {
  /** The length of a typical night, the space a recording must have to start. */
  const val NIGHT_HOURS = 8L

  /** Below this many hours of the chosen streams, a running recording warns that it is filling up. */
  const val LOW_SPACE_HOURS = 1L

  private const val SECONDS_PER_HOUR = 3600L

  // Bytes one second of a stream appends at most, frame header included. HR is one sample per
  // second; ECG and ACC follow the configured rates, plus their batch headers and timestamps.
  // ECG is stored as 16-bit samples unless a batch holds a value that does not fit.
  private const val HR_BYTES_PER_SECOND = 30L
  private const val ECG_BYTES_PER_SAMPLE = 2L
  private const val ACC_BYTES_PER_SAMPLE = 6L
  private const val PPG_BYTES_PER_SAMPLE = 14L
  private const val PPI_BYTES_PER_SECOND = 20L
  private const val VECTOR_BYTES_PER_SAMPLE = 12L
  private const val BATCH_OVERHEAD_PER_SECOND = 100L
  // A sound frame is one second of audio: its frame and payload headers, and the Opus packet
  // lengths. Uncompressed is two bytes per 16 kHz sample; compressed follows the encoder's bit rate.
  private const val SOUND_BYTES_PER_SAMPLE = 2L
  private const val SOUND_FRAME_OVERHEAD_PER_SECOND = 140L

  fun bytesPerSecond(config: RecordingConfig): Long = bytesPerSecond(config.asStreams())

  /** Bytes one second of the chosen streams appends at most, from what each stream says it holds. */
  @Suppress("CyclomaticComplexMethod")
  fun bytesPerSecond(streams: List<RecordingStream>): Long {
    var bytes = 0L
    var heart = false
    for (stream in streams) {
      when (stream.type) {
        StreamType.HEART_RATE,
        StreamType.RR_INTERVAL -> if (!heart) {
          bytes += HR_BYTES_PER_SECOND
          heart = true
        }
        StreamType.ECG ->
            bytes += (stream.rateHz ?: 130.0).toLong() * ECG_BYTES_PER_SAMPLE +
                BATCH_OVERHEAD_PER_SECOND
        StreamType.ACCELEROMETER ->
            bytes += (stream.rateHz ?: 50.0).toLong() * ACC_BYTES_PER_SAMPLE +
                BATCH_OVERHEAD_PER_SECOND
        StreamType.PPG ->
            bytes += (stream.rateHz ?: 55.0).toLong() * PPG_BYTES_PER_SAMPLE +
                BATCH_OVERHEAD_PER_SECOND
        StreamType.PULSE_INTERVAL -> bytes += PPI_BYTES_PER_SECOND
        StreamType.GYROSCOPE,
        StreamType.MAGNETOMETER ->
            bytes += (stream.rateHz ?: 50.0).toLong() * VECTOR_BYTES_PER_SAMPLE +
                BATCH_OVERHEAD_PER_SECOND
        StreamType.SOUND ->
            bytes +=
                when (stream.codec) {
                  // A codec this version does not know is estimated as uncompressed, the largest.
                  Codec.SOUND_OPUS -> SoundPayloads.OPUS_BIT_RATE_BPS / 8L
                  else ->
                      (stream.rateHz ?: SoundPayloads.RATE_HZ.toDouble()).toLong() *
                          SOUND_BYTES_PER_SAMPLE
                } + SOUND_FRAME_OVERHEAD_PER_SECOND
      }
    }
    return bytes
  }

  /** Free space needed to record a typical night with [config]. */
  fun requiredBytes(config: RecordingConfig): Long = requiredBytes(config.asStreams())

  fun requiredBytes(streams: List<RecordingStream>): Long =
      bytesPerSecond(streams) * SECONDS_PER_HOUR * NIGHT_HOURS

  /** Free space below which a running recording raises a storage problem. */
  fun lowBytes(config: RecordingConfig): Long = lowBytes(config.asStreams())

  fun lowBytes(streams: List<RecordingStream>): Long =
      bytesPerSecond(streams) * SECONDS_PER_HOUR * LOW_SPACE_HOURS

  fun canStart(available: Long, config: RecordingConfig): Boolean = available >= requiredBytes(config)

  fun canStart(available: Long, streams: List<RecordingStream>): Boolean =
      available >= requiredBytes(streams)

  fun isLow(available: Long, config: RecordingConfig): Boolean = available < lowBytes(config)

  fun isLow(available: Long, streams: List<RecordingStream>): Boolean = available < lowBytes(streams)
}
