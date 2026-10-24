package com.rbmr.taigasleep.recording

/**
 * Immutable snapshot of one live recording. Owned and derived only by [RecordingMonitor]; the UI,
 * the notification and the repository only format it.
 *
 * Times are milliseconds. [Session.observedAtElapsedMs] and every `sinceElapsedMs`, `ageMs` and
 * [Session.elapsedMs] are on the monotonic elapsed-realtime clock; [Session.observedAtUtcMs] and
 * [Session.startUtcMs] are wall clock. Consumers must not compute ages from a mixture of the two.
 *
 * No Android types: a backend or a reader can serialise the same fields.
 */
data class RecordingState(
    val session: Session = Session(),
    val link: Link = Link(),
    val contact: Contact = Contact(),
    /** Only the streams enabled in the session config. */
    val streams: Map<StreamKind, Stream> = emptyMap(),
    val heart: Heart = Heart(),
    val quality: Quality = Quality(),
    val power: Power = Power(),
    val storage: Storage = Storage(),
    val overall: Overall = Overall(),
) {
  enum class Phase {
    STARTING,
    RECORDING,
    STOPPING,
  }

  data class Session(
      val id: String = "",
      val sensorId: String = "",
      val sensorName: String = "",
      val phase: Phase = Phase.STARTING,
      val startUtcMs: Long = 0,
      val elapsedMs: Long = 0,
      val observedAtElapsedMs: Long = 0,
      val observedAtUtcMs: Long = 0,
  )

  enum class LinkState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
  }

  data class Link(
      val state: LinkState = LinkState.DISCONNECTED,
      val sinceElapsedMs: Long = 0,
      val reconnects: Int = 0,
  )

  enum class ContactState {
    UNKNOWN,
    ON_BODY,
    OFF_BODY,
  }

  data class Contact(
      val state: ContactState = ContactState.UNKNOWN,
      val sinceElapsedMs: Long = 0,
      /** Time spent off the body so far, including the current off-body period. */
      val offBodyMs: Long = 0,
  )

  enum class StreamKind {
    HR,
    ECG,
    ACC,
  }

  enum class StreamStatus {
    WAITING,
    OK,
    STALE,
  }

  data class Stream(
      val kind: StreamKind,
      val status: StreamStatus = StreamStatus.WAITING,
      /** Measured over a sliding window; null until there is enough data. */
      val rateHz: Double? = null,
      val lastSampleAgeMs: Long? = null,
      val samples: Long = 0,
      /** Times a stalled stream came back. */
      val restarts: Int = 0,
  )

  data class Heart(
      val bpm: Int? = null,
      val contactSupported: Boolean = false,
      val rrAvailable: Boolean = false,
  )

  enum class EcgQuality {
    UNKNOWN,
    OK,
    POOR,
  }

  data class Quality(val ecg: EcgQuality = EcgQuality.UNKNOWN)

  enum class PowerState {
    UNKNOWN,
    OK,
    LOW,
  }

  data class Power(
      val percent: Int? = null,
      /** Age of the last battery reading, null when the sensor never reported one. */
      val ageMs: Long? = null,
      val state: PowerState = PowerState.UNKNOWN,
  )

  data class Storage(
      val bytesWritten: Long = 0,
      val lastError: String? = null,
      /** Time since the last successful fsync; null before the first one. */
      val sinceSyncMs: Long? = null,
  )

  enum class OverallState {
    OK,
    WARNING,
    ERROR,
  }

  enum class Reason {
    STORAGE_ERROR,
    LINK_LOST,
    OFF_BODY,
    STREAM_STALE,
    ECG_POOR,
    BATTERY_LOW,
  }

  data class Overall(
      val state: OverallState = OverallState.OK,
      val reason: Reason? = null,
  )
}
