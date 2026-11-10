package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingConfig
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
  private const val HR_BYTES_PER_SECOND = 30L
  private const val ECG_BYTES_PER_SAMPLE = 4L
  private const val ACC_BYTES_PER_SAMPLE = 6L
  private const val BATCH_OVERHEAD_PER_SECOND = 100L

  fun bytesPerSecond(config: RecordingConfig): Long {
    var bytes = 0L
    if (config.hr) bytes += HR_BYTES_PER_SECOND
    if (config.ecg) bytes += config.ecgRateHz * ECG_BYTES_PER_SAMPLE + BATCH_OVERHEAD_PER_SECOND
    if (config.acc) bytes += config.accRateHz * ACC_BYTES_PER_SAMPLE + BATCH_OVERHEAD_PER_SECOND
    return bytes
  }

  /** Free space needed to record a typical night with [config]. */
  fun requiredBytes(config: RecordingConfig): Long =
      bytesPerSecond(config) * SECONDS_PER_HOUR * NIGHT_HOURS

  /** Free space below which a running recording raises a storage problem. */
  fun lowBytes(config: RecordingConfig): Long =
      bytesPerSecond(config) * SECONDS_PER_HOUR * LOW_SPACE_HOURS

  fun canStart(available: Long, config: RecordingConfig): Boolean = available >= requiredBytes(config)

  fun isLow(available: Long, config: RecordingConfig): Boolean = available < lowBytes(config)
}
