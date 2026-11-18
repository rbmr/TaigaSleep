package com.rbmr.taigasleep.recording.store

import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.StreamType

/** What one offered stream becomes in a recording. */
internal fun OfferedStream.toRecordingStream(): RecordingStream =
    RecordingStream(
        id = id,
        type = type,
        codec = codec,
        file = file,
        rateHz = ratesHz.firstOrNull(),
        settings = emptyMap(),
    )

/** The old `config` object, kept in meta.json so an older reader still sees the three main streams. */
internal fun legacyConfig(streams: List<RecordingStream>): RecordingConfig {
  val types = streams.map { it.type }.toSet()
  return RecordingConfig(
      hr = StreamType.HEART_RATE in types,
      ecg = StreamType.ECG in types,
      acc = StreamType.ACCELEROMETER in types,
  )
}
