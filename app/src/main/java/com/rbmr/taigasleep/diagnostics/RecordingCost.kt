package com.rbmr.taigasleep.diagnostics

import android.app.Application
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What one recording costs the phone, measured while it runs.
 *
 * The recording code only increments the counters below. The phone is read when the one-minute
 * heartbeat is written, so nothing here adds a timer, a wake-up, a permission or a file write of its
 * own. Every counter is a plain in-memory value: a process kill loses it, and the reading tool
 * measures the segment that ended that way from the heartbeats it left behind.
 *
 * All methods are safe from any thread and never throw.
 */
object RecordingCost {
  /**
   * The phone readings the cost needs. The app supplies [AndroidSources]; a test supplies its own,
   * so no test depends on a battery, a process or a traffic counter.
   */
  interface Sources {
    fun batteryPercent(): Int?

    /** Remaining charge in microamp-hours, or null where the phone cannot report it. */
    fun chargeCounterUah(): Long?

    fun currentNowUa(): Long?

    /** Remaining energy in nanowatt-hours, or null where the phone cannot report it. */
    fun energyNwh(): Long?

    fun charging(): Boolean?

    /** The app process's cumulative CPU time in milliseconds. */
    fun processCpuMs(): Long?

    /** The calling thread's cumulative CPU time in milliseconds, where cheaply known. */
    fun ownThreadCpuMs(): Long?

    fun gcCount(): Long?

    fun gcTimeMs(): Long?

    fun txBytes(): Long?

    fun rxBytes(): Long?

    /** The phone's thermal headroom as a fraction of the throttling threshold, or null. */
    fun thermalHeadroom(): Float?
  }

  /** What one declared stream received: notifications and their payload bytes. */
  private class StreamCost {
    val notifications = AtomicLong()
    val bytes = AtomicLong()
  }

  private const val HOUR_MS = 3_600_000.0

  private val lock = Any()

  @Volatile private var window: Window? = null
  @Volatile private var sources: Sources? = null
  @Volatile private var sink: (String, Map<String, Any?>) -> Unit = { type, fields ->
    Diagnostics.log(type, fields)
  }

  // The current recording's counters. They are reset by begin() and only read here.
  private val ticks = AtomicLong()
  private val timers = AtomicLong()
  private val heartbeats = AtomicLong()
  private val prefWrites = AtomicLong()
  private val recBytes = AtomicLong()
  private val recWrites = AtomicLong()
  private val eventBytes = AtomicLong()
  private val eventWrites = AtomicLong()
  private val diagBytes = AtomicLong()
  private val diagWrites = AtomicLong()
  private val fsyncs = AtomicLong()
  private val stalls = AtomicLong()
  private val reconnects = AtomicLong()
  private val restarts = AtomicLong()
  private val bleStreams = ConcurrentHashMap<String, StreamCost>()

  /** What the phone showed when a segment began. */
  private class Start(
      val batteryPct: Int?,
      val chargeUah: Long?,
      val cpuMs: Long?,
      val txBytes: Long?,
      val rxBytes: Long?,
  )

  /** The phone's state at one heartbeat, which is both the end of one interval and the next one. */
  private class Reading(
      val atMs: Long,
      val batteryPct: Int?,
      val chargeUah: Long?,
      val interactive: Boolean?,
      val doze: Boolean?,
      val charging: Boolean?,
  )

  /** What only the end of a segment needs, beyond its last [Reading]. */
  private class Extras(val cpuMs: Long?, val txBytes: Long?, val rxBytes: Long?)

  private class Window(
      val recordingId: String,
      val startedUtcMs: Long,
      val beganElapsedMs: Long,
      val start: Start,
  ) {
    var lastAtMs = beganElapsedMs
    var lastBatteryPct = start.batteryPct
    var lastChargeUah = start.chargeUah
    var lastInteractive: Boolean? = null
    var lastDoze: Boolean? = null
    var lastCharging: Boolean? = null
    var screenOnMs = 0L
    var dozeMs = 0L
    var chargingMs = 0L
    var drainableOnMs = 0L
    var drainableOffMs = 0L
    var drainOnPct = 0.0
    var drainOffPct = 0.0
    var drainOnUah = 0.0
    var drainOffUah = 0.0
    var wakeHeldMs = 0L
    var wakeSinceMs: Long? = null
    var wakeAcquires = 0L
  }

  /** Installed once with the application and replaced by a test with its fakes. */
  internal fun install(sources: Sources?, sink: ((String, Map<String, Any?>) -> Unit)? = null) {
    this.sources = sources
    if (sink != null) this.sink = sink
    synchronized(lock) { window = null }
  }

  internal fun installAndroid(application: Application) = install(AndroidSources(application))

  /**
   * Starts the cost of [recordingId], at [nowUtcMs] and [nowElapsedMs]. A recording this process
   * already measures keeps its window, so a watchdog restart does not lose the counters. [resumed]
   * is a recording picked up after the process that recorded it died: it writes the segment
   * boundary the reading tool needs, because the segment before it has no stop event.
   */
  fun begin(
      recordingId: String,
      startedUtcMs: Long,
      nowUtcMs: Long,
      nowElapsedMs: Long,
      resumed: Boolean,
  ) {
    val s = sources ?: return
    var recovered: Map<String, Any?>? = null
    synchronized(lock) {
      val current = window
      if (current != null && current.recordingId == recordingId) return
      resetCounters()
      val start =
          Start(
              batteryPct = safe { s.batteryPercent() },
              chargeUah = safe { s.chargeCounterUah() },
              cpuMs = safe { s.processCpuMs() },
              txBytes = safe { s.txBytes() },
              rxBytes = safe { s.rxBytes() },
          )
      val w = Window(recordingId, startedUtcMs, nowElapsedMs, start)
      window = w
      if (resumed) recovered = recoveredFields(w, nowUtcMs)
    }
    recovered?.let { sink("recording_cost", it) }
  }

  /**
   * The cost block of the heartbeat, or null when no recording runs in this process. The values the
   * heartbeat already read are passed in, so the phone is not asked twice for its battery state.
   */
  fun heartbeatFields(
      nowElapsedMs: Long,
      batteryPct: Int?,
      interactive: Boolean?,
      doze: Boolean?,
      charging: Boolean?,
  ): Map<String, Any?>? {
    val s = sources
    val w = window
    if (s == null || w == null) return null
    return synchronized(lock) {
      if (window !== w) return@synchronized null
      val collected = System.nanoTime()
      val charge = safe { s.chargeCounterUah() }
      val current = safe { s.currentNowUa() }
      val energy = safe { s.energyNwh() }
      val cpu = safe { s.processCpuMs() }
      val ownCpu = safe { s.ownThreadCpuMs() }
      val gcCount = safe { s.gcCount() }
      val gcTime = safe { s.gcTimeMs() }
      val tx = safe { s.txBytes() }
      val rx = safe { s.rxBytes() }
      val headroom = safe { s.thermalHeadroom() }
      accumulate(w, Reading(nowElapsedMs, batteryPct, charge, interactive, doze, charging))
      heartbeats.incrementAndGet()
      val fields = LinkedHashMap<String, Any?>()
      if (charge != null) fields["c"] = charge
      if (current != null) fields["i"] = current
      if (energy != null) fields["e"] = energy
      if (cpu != null) fields["p"] = listOfNotNull(cpu, ownCpu)
      fields["w"] = listOf(heldMs(w, nowElapsedMs), w.wakeAcquires)
      fields["t"] = listOf(ticks.get(), timers.get(), heartbeats.get(), prefWrites.get())
      fields["o"] =
          listOf(
              recBytes.get(),
              recWrites.get(),
              eventBytes.get(),
              eventWrites.get(),
              diagBytes.get(),
              diagWrites.get(),
              fsyncs.get(),
          )
      fields["b"] = bleFields()
      traffic(w, tx, rx)?.let { fields["n"] = it }
      if (gcCount != null || gcTime != null) fields["g"] = listOfNotNull(gcCount, gcTime)
      fields["m"] = listOf(w.screenOnMs, w.dozeMs)
      if (headroom != null && !headroom.isNaN()) fields["h"] = round(headroom.toDouble(), 2)
      fields["u"] = ((System.nanoTime() - collected) / 1_000).coerceAtLeast(0)
      fields
    }
  }

  /**
   * Ends the cost of the running recording with a `recording_cost` event. [reason] is why it ended
   * (`stopped`, `forced_stop`, `interrupted`, `discarded`, `fetch_pending`); [nowUtcMs] and
   * [nowElapsedMs] close the duration and the counters.
   */
  fun end(reason: String, nowUtcMs: Long, nowElapsedMs: Long) {
    val s = sources ?: return
    val w: Window
    synchronized(lock) {
      w = window ?: return
      window = null
    }
    val charging = safe { s.charging() }
    val reading =
        Reading(
            nowElapsedMs,
            safe { s.batteryPercent() },
            safe { s.chargeCounterUah() },
            null,
            null,
            charging,
        )
    val extras = Extras(safe { s.processCpuMs() }, safe { s.txBytes() }, safe { s.rxBytes() })
    // The last interval belongs to the recording too: count it with the state the last heartbeat saw.
    synchronized(lock) { accumulate(w, reading) }
    val fields = stopFields(w, reason, nowUtcMs, reading, extras)
    if (charging != null) fields["charging_end"] = charging
    sink("recording_cost", fields)
  }

  /** Forgets the running recording without an event: its counters die with the process. */
  fun clear() {
    synchronized(lock) { window = null }
  }

  fun wakeLockAcquired(nowMs: Long) {
    synchronized(lock) {
      val w = window ?: return
      w.wakeAcquires++
      if (w.wakeSinceMs == null) w.wakeSinceMs = nowMs
    }
  }

  fun wakeLockReleased(nowMs: Long) {
    synchronized(lock) {
      val w = window ?: return
      val since = w.wakeSinceMs ?: return
      w.wakeHeldMs += (nowMs - since).coerceAtLeast(0)
      w.wakeSinceMs = null
    }
  }

  fun tick() = ticks.incrementAndGet()

  fun timerFired() = timers.incrementAndGet()

  fun preferenceWrite() = prefWrites.incrementAndGet()

  /** A write of [bytes] to a recording's own files (a frame, meta.json). */
  fun recordingWrite(bytes: Long) {
    if (bytes <= 0) return
    recBytes.addAndGet(bytes)
    recWrites.incrementAndGet()
  }

  fun eventWrite(bytes: Long) {
    if (bytes <= 0) return
    eventBytes.addAndGet(bytes)
    eventWrites.incrementAndGet()
  }

  fun diagnosticsWrite(bytes: Long) {
    if (bytes <= 0) return
    diagBytes.addAndGet(bytes)
    diagWrites.incrementAndGet()
  }

  fun fsync() = fsyncs.incrementAndGet()

  /** One notification of the declared stream [stream], carrying [bytes] of encoded payload. */
  fun ble(stream: String, bytes: Long) {
    val cost = bleStreams[stream] ?: bleStreams.computeIfAbsent(stream) { StreamCost() }
    cost.notifications.incrementAndGet()
    cost.bytes.addAndGet(bytes.coerceAtLeast(0))
  }

  fun streamStalled() = stalls.incrementAndGet()

  fun reconnected() = reconnects.incrementAndGet()

  fun watchdogRestart() = restarts.incrementAndGet()

  // Interval bookkeeping. Everything below runs under the lock.

  /**
   * Adds the time since the previous heartbeat to the screen, doze, charging and drain buckets. The
   * state of the interval is the one the previous heartbeat saw, because that is when the interval
   * began; drain during a charging interval is not drain and is left out.
   */
  private fun accumulate(w: Window, now: Reading) {
    val dt = (now.atMs - w.lastAtMs).coerceAtLeast(0)
    if (w.lastAtMs > 0 && dt > 0) countInterval(w, dt, now)
    w.lastAtMs = now.atMs
    w.lastBatteryPct = now.batteryPct
    w.lastChargeUah = now.chargeUah
    w.lastInteractive = now.interactive
    w.lastDoze = now.doze
    w.lastCharging = now.charging
  }

  private fun countInterval(w: Window, dt: Long, now: Reading) {
    val on = w.lastInteractive == true
    if (on) w.screenOnMs += dt
    if (w.lastDoze == true) w.dozeMs += dt
    if (w.lastCharging == true) {
      w.chargingMs += dt
      return
    }
    if (on) w.drainableOnMs += dt else w.drainableOffMs += dt
    val pctDrop = drop(w.lastBatteryPct, now.batteryPct)
    if (pctDrop > 0) if (on) w.drainOnPct += pctDrop else w.drainOffPct += pctDrop
    val chargeDrop = drop(w.lastChargeUah, now.chargeUah)
    if (chargeDrop > 0) if (on) w.drainOnUah += chargeDrop else w.drainOffUah += chargeDrop
  }

  private fun drop(before: Int?, after: Int?): Double =
      if (before != null && after != null) (before - after).coerceAtLeast(0).toDouble() else 0.0

  private fun drop(before: Long?, after: Long?): Double =
      if (before != null && after != null) (before - after).coerceAtLeast(0).toDouble() else 0.0

  private fun heldMs(w: Window, nowMs: Long): Long =
      w.wakeHeldMs + (w.wakeSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)

  /** Notifications and payload bytes of every stream that delivered something, by stream id. */
  private fun bleFields(): Map<String, List<Long>> {
    val out = LinkedHashMap<String, List<Long>>()
    for (id in bleStreams.keys.sorted()) {
      val cost = bleStreams[id] ?: continue
      out[id] = listOf(cost.notifications.get(), cost.bytes.get())
    }
    return out
  }

  private fun traffic(w: Window, tx: Long?, rx: Long?): List<Long>? {
    val up = delta(w.start.txBytes, tx)
    val down = delta(w.start.rxBytes, rx)
    return if (up == null && down == null) null else listOf(up ?: 0, down ?: 0)
  }

  /** Traffic since the window began; a counter that went backwards (a reset) starts again at zero. */
  private fun delta(start: Long?, now: Long?): Long? {
    if (now == null) return null
    return if (start == null || now < start) now else now - start
  }

  private fun recoveredFields(w: Window, nowUtcMs: Long): Map<String, Any?> =
      linkedMapOf<String, Any?>(
          "recording" to w.recordingId,
          "reason" to "recovered",
          "started_utc_ms" to w.startedUtcMs,
          "duration_s" to (nowUtcMs - w.startedUtcMs).coerceAtLeast(0) / 1000,
          "segment_s" to 0L,
          "battery_pct_start" to w.start.batteryPct,
          "battery_pct_end" to w.start.batteryPct,
          "charge_uah_start" to w.start.chargeUah,
          "charge_uah_end" to w.start.chargeUah,
      )

  private fun stopFields(
      w: Window,
      reason: String,
      nowUtcMs: Long,
      now: Reading,
      extras: Extras,
  ): LinkedHashMap<String, Any?> {
    val segmentMs = (now.atMs - w.beganElapsedMs).coerceAtLeast(0)
    val durationS = (nowUtcMs - w.startedUtcMs).coerceAtLeast(0) / 1000
    val cpuMs =
        if (extras.cpuMs != null && w.start.cpuMs != null) {
          (extras.cpuMs - w.start.cpuMs).coerceAtLeast(0)
        } else {
          null
        }
    val fields =
        linkedMapOf<String, Any?>(
            "recording" to w.recordingId,
            "reason" to reason,
            "started_utc_ms" to w.startedUtcMs,
            "duration_s" to durationS,
            "segment_s" to segmentMs / 1000,
            "battery_pct_start" to w.start.batteryPct,
            "battery_pct_end" to now.batteryPct,
            "charge_uah_start" to w.start.chargeUah,
            "charge_uah_end" to now.chargeUah,
            "charging_s" to w.chargingMs / 1000,
            "screen_on_s" to w.screenOnMs / 1000,
            "screen_off_s" to (segmentMs - w.screenOnMs).coerceAtLeast(0) / 1000,
            "doze_s" to w.dozeMs / 1000,
            "wake_lock_ms" to heldMs(w, now.atMs),
            "wake_lock_n" to w.wakeAcquires,
            "cpu_ms" to cpuMs,
            "cpu_ms_per_h" to cpuMs?.let { perHour(it, segmentMs) },
            "bytes_written" to recBytes.get(),
            "event_bytes" to eventBytes.get(),
            "diag_bytes" to diagBytes.get(),
            "fsyncs" to fsyncs.get(),
            "stream_stalls" to stalls.get(),
            "reconnects" to reconnects.get(),
            "watchdog_restarts" to restarts.get(),
            "drain_pct_per_h_on" to perHour(w.drainOnPct, w.drainableOnMs),
            "drain_pct_per_h_off" to perHour(w.drainOffPct, w.drainableOffMs),
            "drain_uah_per_h_on" to perHour(w.drainOnUah, w.drainableOnMs),
            "drain_uah_per_h_off" to perHour(w.drainOffUah, w.drainableOffMs),
            "ticks" to ticks.get(),
            "timers" to timers.get(),
            "heartbeats" to heartbeats.get(),
        )
    traffic(w, extras.txBytes, extras.rxBytes)?.let { fields["net"] = it }
    return fields
  }

  /** [amount] grown to one hour, or null when nothing was measured in [ms]. */
  private fun perHour(amount: Double, ms: Long): Double? =
      if (ms <= 0 || amount <= 0) null else round(amount * HOUR_MS / ms, 1)

  private fun perHour(amount: Long, ms: Long): Double? =
      if (ms <= 0 || amount <= 0) null else round(amount.toDouble() * HOUR_MS / ms, 1)

  private fun round(value: Double, decimals: Int): Double {
    var scale = 1.0
    repeat(decimals) { scale *= 10 }
    return Math.round(value * scale) / scale
  }

  private fun resetCounters() {
    ticks.set(0)
    timers.set(0)
    heartbeats.set(0)
    prefWrites.set(0)
    recBytes.set(0)
    recWrites.set(0)
    eventBytes.set(0)
    eventWrites.set(0)
    diagBytes.set(0)
    diagWrites.set(0)
    fsyncs.set(0)
    stalls.set(0)
    reconnects.set(0)
    restarts.set(0)
    bleStreams.clear()
  }

  private inline fun <T> safe(block: () -> T?): T? =
      try {
        block()
      } catch (_: Exception) {
        null
      }

  /**
   * The phone behind [Sources]. Every read is guarded: a phone that does not report a value says
   * null and the field is left out, instead of losing the line.
   */
  private class AndroidSources(private val context: Application) : Sources {
    private val battery: BatteryManager? by lazy {
      context.getSystemService(BatteryManager::class.java)
    }
    private val power: PowerManager? by lazy { context.getSystemService(PowerManager::class.java) }
    private val uid = Process.myUid()

    override fun batteryPercent(): Int? {
      val value = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
      return value?.takeIf { it in 0..100 }
    }

    override fun chargeCounterUah(): Long? =
        battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf {
          it != Long.MIN_VALUE
        }

    override fun currentNowUa(): Long? =
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf {
          it != Int.MIN_VALUE
        }?.toLong()

    override fun energyNwh(): Long? =
        battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.takeIf {
          it != Long.MIN_VALUE
        }

    override fun charging(): Boolean? =
        try {
          battery?.isCharging
        } catch (_: Exception) {
          null
        }

    override fun processCpuMs(): Long = Process.getElapsedCpuTime()

    override fun ownThreadCpuMs(): Long? =
        Debug.threadCpuTimeNanos().takeIf { it >= 0 }?.let { it / 1_000_000 }

    override fun gcCount(): Long? = runtimeStat("art.gc.gc-count")

    override fun gcTimeMs(): Long? = runtimeStat("art.gc.gc-time")

    override fun txBytes(): Long? = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 }

    override fun rxBytes(): Long? = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 }

    override fun thermalHeadroom(): Float? =
        try {
          power?.getThermalHeadroom(0)
        } catch (_: Exception) {
          null
        }

    private fun runtimeStat(name: String): Long? = Debug.getRuntimeStat(name)?.toLongOrNull()
  }
}
