package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.sensor.AccBatch
import com.rbmr.taigasleep.sensor.Clock
import com.rbmr.taigasleep.sensor.ConnectionState
import com.rbmr.taigasleep.sensor.EcgBatch
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.Stamp
import com.rbmr.taigasleep.storage.SessionMeta
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The sample callbacks the monitor needs. [RecordingMonitor] implements it. */
interface SampleMonitor {
  fun onHr(stamp: Stamp, sample: HrSample)

  fun onEcg(stamp: Stamp, batch: EcgBatch)

  fun onAcc(stamp: Stamp, batch: AccBatch)
}

/**
 * The only owner of derived live metrics: contact, link, stream rates/liveness, ECG quality,
 * battery and storage health. It never writes frames or parses the recording; it publishes one
 * immutable [RecordingState] that the UI and the notification format.
 *
 * Samples arrive on the sensor's delivery thread, [tick] and the link/battery updates on the
 * service thread, so every mutation is taken under one short lock. Samples only update counters;
 * they never publish or write events. Events and a 1 Hz publish happen in [tick], plus immediately
 * on link, battery and phase changes.
 */
class RecordingMonitor(
    private val meta: SessionMeta,
    private val clock: Clock,
    /** The writer's `event`; transition events are rare, samples never log. */
    private val event: (type: String, message: String) -> Unit,
    /** The writer's storage health; read at 1 Hz, never on the sample path. */
    private val storageHealth: () -> StorageHealth,
) : SampleMonitor {
  private val lock = Any()
  private val _state = MutableStateFlow(RecordingState())
  val state: StateFlow<RecordingState> = _state.asStateFlow()

  private val startedElapsedMs = clock.elapsedNs() / NS_PER_MS
  private val elapsedBaseMs = (clock.phoneNs() / NS_PER_MS - meta.startUtcMs).coerceAtLeast(0)

  private var phase = RecordingState.Phase.STARTING
  private var link = RecordingState.LinkState.DISCONNECTED
  private var linkSinceMs = startedElapsedMs
  private var everConnected = false
  private var reconnects = 0

  // The raw HR contact flag, plus the committed state after hysteresis.
  private var rawContact: Boolean? = null
  private var rawContactSinceNs = 0L
  private var contact = RecordingState.ContactState.UNKNOWN
  private var contactSinceMs = startedElapsedMs
  private var offBodyCompletedMs = 0L

  private val streams = LinkedHashMap<RecordingState.StreamKind, StreamAcc>()
  private var hrBpm: Int? = null
  private var hrContactSupported = false
  private var rrAvailable = false

  private var ecg = RecordingState.EcgQuality.UNKNOWN
  private var ecgPoor = false
  private val clipClipped = IntArray(CLIP_BATCHES)
  private val clipTotal = IntArray(CLIP_BATCHES)
  private var clipNext = 0
  private var clipCount = 0

  private var batteryPercent: Int? = meta.sensorBatteryAtStart
  private var batteryAtUtcMs = meta.startUtcMs
  private var batteryObserved = false
  private var battery = powerState(meta.sensorBatteryAtStart)

  private var storageBytes = 0L
  private var storageError: String? = null
  private var sinceSyncMs: Long? = null

  init {
    if (meta.config.hr) streams[RecordingState.StreamKind.HR] = StreamAcc(RecordingState.StreamKind.HR)
    if (meta.config.ecg) streams[RecordingState.StreamKind.ECG] = StreamAcc(RecordingState.StreamKind.ECG)
    if (meta.config.acc) streams[RecordingState.StreamKind.ACC] = StreamAcc(RecordingState.StreamKind.ACC)
    synchronized(lock) { publishLocked(startedElapsedMs) }
  }

  // Samples. No events and no publishing here: the 1 Hz tick coalesces them.

  override fun onHr(stamp: Stamp, sample: HrSample) {
    var contactEvent: Pair<String, String>? = null
    synchronized(lock) {
      hrBpm = sample.hr
      hrContactSupported = sample.contactSupported
      rrAvailable = sample.rrAvailable
      if (sample.contactSupported) {
        if (rawContact != sample.contact) {
          val before = rawContact
          rawContact = sample.contact
          rawContactSinceNs = stamp.elapsedNs
          // Log the raw change immediately, as the raw recording always did. The live state
          // (and therefore the notification) applies the hysteresis below.
          if (before != null || !sample.contact) {
            contactEvent =
                "contact" to
                    if (sample.contact) "skin contact restored" else "no skin contact"
          }
        }
      } else {
        // The sensor cannot tell; keep the last committed state rather than inventing one.
        rawContact = null
      }
      recordStream(RecordingState.StreamKind.HR, stamp.elapsedNs, 1)
    }
    contactEvent?.let { emit(listOf(it)) }
  }

  override fun onEcg(stamp: Stamp, batch: EcgBatch) {
    if (batch.timestampsNs.isEmpty()) return
    synchronized(lock) {
      recordStream(RecordingState.StreamKind.ECG, stamp.elapsedNs, batch.timestampsNs.size)
      noteEcgQuality(batch.voltageUv)
    }
  }

  override fun onAcc(stamp: Stamp, batch: AccBatch) {
    if (batch.timestampsNs.isEmpty()) return
    synchronized(lock) {
      recordStream(RecordingState.StreamKind.ACC, stamp.elapsedNs, batch.timestampsNs.size)
    }
  }

  // Link, power and phase: immediate publish.

  fun onLink(state: ConnectionState) {
    val next =
        when (state) {
          ConnectionState.CONNECTED -> RecordingState.LinkState.CONNECTED
          ConnectionState.CONNECTING -> RecordingState.LinkState.CONNECTING
          ConnectionState.DISCONNECTED -> RecordingState.LinkState.DISCONNECTED
        }
    val nowMs = clock.elapsedNs() / NS_PER_MS
    val events = ArrayList<Pair<String, String>>(1)
    synchronized(lock) {
      if (next == link) return
      val previous = link
      val first = !everConnected
      if (next == RecordingState.LinkState.CONNECTED) {
        everConnected = true
        if (!first) reconnects++
      }
      link = next
      linkSinceMs = nowMs
      when {
        next == RecordingState.LinkState.CONNECTED ->
            events += (if (first) "connect" else "reconnect") to meta.sensorName
        previous == RecordingState.LinkState.CONNECTED ->
            events += "disconnect" to "disconnected"
      }
      publishLocked(nowMs)
    }
    emit(events)
  }

  /** The sensor's latest battery reading; [percent] is null until the sensor reports one. */
  fun onBattery(percent: Int?) {
    val nowUtcMs = clock.phoneNs() / NS_PER_MS
    val nowMs = clock.elapsedNs() / NS_PER_MS
    val events = ArrayList<Pair<String, String>>(1)
    synchronized(lock) {
      val before = battery
      var changed = false
      if (percent != null && (!batteryObserved || percent != batteryPercent)) {
        // The first poll after attach is a fresh reading even if it equals the start value.
        batteryAtUtcMs = nowUtcMs
        batteryObserved = true
      }
      if (percent != batteryPercent) {
        batteryPercent = percent
        changed = true
      }
      battery = powerState(percent)
      when {
        battery == RecordingState.PowerState.LOW && before != RecordingState.PowerState.LOW -> {
          events += "battery" to "sensor battery low: ${percent ?: "?"}%"
          changed = true
        }
        before == RecordingState.PowerState.LOW && battery == RecordingState.PowerState.OK -> {
          events += "battery" to "sensor battery ok: $percent%"
          changed = true
        }
      }
      // The age of the reading changes every tick; only a new reading publishes now.
      if (changed) publishLocked(nowMs)
    }
    emit(events)
  }

  fun onPhase(phase: RecordingState.Phase) {
    val nowMs = clock.elapsedNs() / NS_PER_MS
    synchronized(lock) {
      if (phase == this.phase) return
      this.phase = phase
      publishLocked(nowMs)
    }
  }

  /** Evaluates contact hysteresis, stream staleness, storage health and publishes at 1 Hz. */
  fun tick(nowNs: Long) {
    val nowMs = nowNs / NS_PER_MS
    val events = ArrayList<Pair<String, String>>(2)
    synchronized(lock) {
      evaluateContact(nowMs)
      evaluateStreams(nowNs, events)
      evaluateQuality(events)
      evaluateStorage(nowNs, events)
      publishLocked(nowMs)
    }
    emit(events)
  }

  // Derivation. Everything below runs under the lock.

  private fun recordStream(kind: RecordingState.StreamKind, stampNs: Long, count: Int) {
    val s = streams[kind] ?: return
    s.samples += count
    s.lastSampleNs = stampNs
    s.window.add(stampNs, s.samples)
  }

  private fun noteEcgQuality(voltages: IntArray) {
    if (voltages.isEmpty()) return
    clipClipped[clipNext] = voltages.count { abs(it) >= ECG_CLIP_UV }
    clipTotal[clipNext] = voltages.size
    clipNext = (clipNext + 1) % CLIP_BATCHES
    if (clipCount < CLIP_BATCHES) clipCount++
    var clipped = 0
    var total = 0
    for (i in 0 until clipCount) {
      clipped += clipClipped[i]
      total += clipTotal[i]
    }
    val fraction = if (total == 0) 0.0 else clipped.toDouble() / total
    // Hysteresis, so one clean batch does not clear a saturated signal (and vice versa).
    ecgPoor = if (ecgPoor) fraction >= CLIP_RECOVER else fraction >= CLIP_POOR
  }

  private fun evaluateContact(nowMs: Long) {
    val raw = rawContact ?: return
    val heldMs = (nowMs - rawContactSinceNs / NS_PER_MS).coerceAtLeast(0)
    when (contact) {
      RecordingState.ContactState.UNKNOWN -> {
        if (raw) commitContact(RecordingState.ContactState.ON_BODY, nowMs)
        else if (heldMs >= OFF_BODY_AFTER_MS) commitContact(RecordingState.ContactState.OFF_BODY, nowMs)
      }
      RecordingState.ContactState.ON_BODY -> {
        if (!raw && heldMs >= OFF_BODY_AFTER_MS) {
          commitContact(RecordingState.ContactState.OFF_BODY, nowMs)
        }
      }
      RecordingState.ContactState.OFF_BODY -> {
        if (raw && heldMs >= ON_BODY_AFTER_MS) {
          commitContact(RecordingState.ContactState.ON_BODY, nowMs)
        }
      }
    }
  }

  private fun commitContact(next: RecordingState.ContactState, nowMs: Long) {
    if (next == contact) return
    if (contact == RecordingState.ContactState.OFF_BODY) {
      offBodyCompletedMs += (nowMs - contactSinceMs).coerceAtLeast(0)
    }
    contact = next
    contactSinceMs = nowMs
  }

  private fun evaluateStreams(nowNs: Long, events: MutableList<Pair<String, String>>) {
    for (s in streams.values) {
      s.rateHz = s.window.rate(nowNs)
      s.ageMs = if (s.samples == 0L) null else ((nowNs - s.lastSampleNs) / NS_PER_MS).coerceAtLeast(0)
      val next =
          when {
            s.samples == 0L -> RecordingState.StreamStatus.WAITING
            s.ageMs != null && s.ageMs!! > STALE_MS -> RecordingState.StreamStatus.STALE
            else -> RecordingState.StreamStatus.OK
          }
      if (next != s.status) {
        when {
          next == RecordingState.StreamStatus.STALE -> {
            // Do not let samples from before the gap skew the rate when the stream comes back.
            s.window.reset()
            s.rateHz = null
            events +=
                "stream_stalled" to
                    "${s.kind.name.lowercase()} stream stalled: no data for ${s.ageMs!! / 1000} s"
          }
          s.status == RecordingState.StreamStatus.STALE -> {
            s.restarts++
            events += "stream_recovered" to "${s.kind.name.lowercase()} stream recovered"
          }
        }
        s.status = next
      }
    }
  }

  private fun evaluateQuality(events: MutableList<Pair<String, String>>) {
    if (!meta.config.ecg) return
    val ecgStream = streams[RecordingState.StreamKind.ECG]
    val next =
        when {
          ecgPoor -> RecordingState.EcgQuality.POOR
          ecgStream != null && ecgStream.samples > 0 -> RecordingState.EcgQuality.OK
          else -> RecordingState.EcgQuality.UNKNOWN
        }
    if (next == ecg) return
    when {
      next == RecordingState.EcgQuality.POOR ->
          events += "ecg_signal" to "poor signal (ECG pinned at its limits)"
      ecg == RecordingState.EcgQuality.POOR ->
          events += "ecg_signal" to "signal recovered"
    }
    ecg = next
  }

  private fun evaluateStorage(nowNs: Long, events: MutableList<Pair<String, String>>) {
    val health = storageHealth()
    storageBytes = health.bytesWritten
    sinceSyncMs = if (health.lastSyncNs > 0) (nowNs - health.lastSyncNs).coerceAtLeast(0) / NS_PER_MS else null
    val error = health.lastError
    if (error != null && error != storageError) {
      storageError = error
      events += "storage_error" to "writing to the phone failed: $error"
    }
  }

  private fun publishLocked(nowMs: Long) {
    val nowUtcMs = clock.phoneNs() / NS_PER_MS
    val streamStates = streams.values.associate { it.kind to it.snapshot() }
    val power = RecordingState.Power(batteryPercent, batteryAgeMs(nowUtcMs), battery)
    val storage =
        RecordingState.Storage(bytesWritten = storageBytes, lastError = storageError, sinceSyncMs = sinceSyncMs)
    val contactState =
        RecordingState.Contact(
            state = contact,
            sinceElapsedMs = contactSinceMs,
            offBodyMs =
                offBodyCompletedMs +
                    if (contact == RecordingState.ContactState.OFF_BODY) {
                      (nowMs - contactSinceMs).coerceAtLeast(0)
                    } else {
                      0
                    },
        )
    _state.value =
        RecordingState(
            session =
                RecordingState.Session(
                    id = meta.sessionId,
                    sensorId = meta.sensorId,
                    sensorName = meta.sensorName,
                    phase = phase,
                    startUtcMs = meta.startUtcMs,
                    elapsedMs = elapsedBaseMs + (nowMs - startedElapsedMs).coerceAtLeast(0),
                    observedAtElapsedMs = nowMs,
                    observedAtUtcMs = nowUtcMs,
                ),
            link = RecordingState.Link(link, linkSinceMs, reconnects),
            contact = contactState,
            streams = streamStates,
            heart =
                RecordingState.Heart(
                    bpm = hrBpm,
                    contactSupported = hrContactSupported,
                    rrAvailable = rrAvailable,
                ),
            quality = RecordingState.Quality(ecg),
            power = power,
            storage = storage,
            overall = overallOf(storageError, link, contact, streamStates.values, ecg, battery),
        )
  }

  private fun batteryAgeMs(nowUtcMs: Long): Long? =
      if (batteryPercent == null) null else (nowUtcMs - batteryAtUtcMs).coerceAtLeast(0)

  private fun emit(events: List<Pair<String, String>>) {
    for ((type, message) in events) {
      // An event write must never take the monitor (or the raw recording) down.
      runCatching { event(type, message) }
    }
  }

  private inner class StreamAcc(val kind: RecordingState.StreamKind) {
    var samples = 0L
    var lastSampleNs = 0L
    var status = RecordingState.StreamStatus.WAITING
    var rateHz: Double? = null
    var ageMs: Long? = null
    var restarts = 0
    val window = RateWindow(RATE_WINDOW_NS)

    fun snapshot() =
        RecordingState.Stream(
            kind = kind,
            status = status,
            rateHz = rateHz,
            lastSampleAgeMs = ageMs,
            samples = samples,
            restarts = restarts,
        )
  }

  companion object {
    private const val NS_PER_MS = 1_000_000L
    // A stream that delivered nothing for this long has stalled, even if the link says connected.
    const val STALE_MS = 3_000L
    // Brief contact flicker must not change the notification.
    const val OFF_BODY_AFTER_MS = 60_000L
    const val ON_BODY_AFTER_MS = 5_000L
    const val LOW_BATTERY_PERCENT = 15
    // The H10 saturates at about +-18.4 mV when nothing is picking up a heartbeat.
    private const val ECG_CLIP_UV = 18_000
    private const val CLIP_BATCHES = 8
    private const val CLIP_POOR = 0.25
    private const val CLIP_RECOVER = 0.05
    private const val RATE_WINDOW_NS = 10_000_000_000L
    private const val RATE_MIN_SPAN_NS = 2_000_000_000L
  }

  /**
   * Sliding-window rate: a bounded ring of (time, cumulative sample count) entries. It is the only
   * per-stream state and is allocated once, so a sample allocates nothing.
   */
  private class RateWindow(private val windowNs: Long) {
    private val times = LongArray(CAPACITY)
    private val totals = LongArray(CAPACITY)
    private var head = 0
    private var size = 0

    fun add(nowNs: Long, total: Long) {
      if (size == CAPACITY) {
        times[head] = nowNs
        totals[head] = total
        head = (head + 1) % CAPACITY
      } else {
        val index = (head + size) % CAPACITY
        times[index] = nowNs
        totals[index] = total
        size++
      }
      trim(nowNs)
    }

    fun rate(nowNs: Long): Double? {
      trim(nowNs)
      if (size < 2) return null
      val last = (head + size - 1) % CAPACITY
      val spanNs = times[last] - times[head]
      if (spanNs < RATE_MIN_SPAN_NS) return null
      return (totals[last] - totals[head]) * 1_000_000_000.0 / spanNs
    }

    fun reset() {
      head = 0
      size = 0
    }

    /** Drops entries older than the window, keeping one baseline entry just outside it. */
    private fun trim(nowNs: Long) {
      while (size > 1) {
        val next = (head + 1) % CAPACITY
        if (nowNs - times[head] > windowNs && nowNs - times[next] > windowNs) {
          head = next
          size--
        } else {
          break
        }
      }
    }

    private companion object {
      // Several times more than the window needs at ECG/ACC batch rates.
      const val CAPACITY = 64
    }
  }
}

private fun powerState(percent: Int?): RecordingState.PowerState =
    when {
      percent == null -> RecordingState.PowerState.UNKNOWN
      percent < RecordingMonitor.LOW_BATTERY_PERCENT -> RecordingState.PowerState.LOW
      else -> RecordingState.PowerState.OK
    }

/**
 * The fixed overall headline every consumer shows. Priority:
 * storage error > link lost > off-body > stale stream > poor ECG > low battery.
 */
internal fun overallOf(
    storageError: String?,
    link: RecordingState.LinkState,
    contact: RecordingState.ContactState,
    streams: Collection<RecordingState.Stream>,
    ecg: RecordingState.EcgQuality,
    power: RecordingState.PowerState,
): RecordingState.Overall {
  val reason =
      when {
        storageError != null -> RecordingState.Reason.STORAGE_ERROR
        link != RecordingState.LinkState.CONNECTED -> RecordingState.Reason.LINK_LOST
        contact == RecordingState.ContactState.OFF_BODY -> RecordingState.Reason.OFF_BODY
        streams.any { it.status == RecordingState.StreamStatus.STALE } ->
            RecordingState.Reason.STREAM_STALE
        ecg == RecordingState.EcgQuality.POOR -> RecordingState.Reason.ECG_POOR
        power == RecordingState.PowerState.LOW -> RecordingState.Reason.BATTERY_LOW
        else -> null
      }
  val state =
      when (reason) {
        null -> RecordingState.OverallState.OK
        RecordingState.Reason.STORAGE_ERROR,
        RecordingState.Reason.LINK_LOST,
        RecordingState.Reason.STREAM_STALE -> RecordingState.OverallState.ERROR
        RecordingState.Reason.OFF_BODY,
        RecordingState.Reason.ECG_POOR,
        RecordingState.Reason.BATTERY_LOW -> RecordingState.OverallState.WARNING
      }
  return RecordingState.Overall(state, reason)
}
