package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.diagnostics.BluetoothPower
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.diagnostics.RecordingCost
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.Tracker
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One attached live session: the writer, the tracker and the live state derived from its samples.
 * [RecordingControl] creates it and ends it; [LiveLoop] keeps it running in between.
 */
@Suppress("LongParameterList")
internal class LiveSession(
    val writer: RecordingWriter,
    val sensor: Tracker,
    val mode: RecordingMode,
    val lifecycle: RecordingLifecycle,
    val fanout: SampleFanout,
    val wakeLock: RecordingWakeLock?,
    val monitor: RecordingMonitor,
) {
  var jobs = emptyList<Job>()

  /**
   * Set when the session is dropped. A control thread that was blocked inside a tracker call keeps
   * running once that call returns, and must not attach the dropped session again.
   */
  @Volatile var dropped = false

  /** Elapsed-realtime ms when the wake lock was last acquired, so it can be renewed in time. */
  var wakeLockSinceMs = 0L

  private val claimed = AtomicBoolean(false)

  /**
   * Only one path may end this session: the stop that closes it, a forced stop, or a restart that
   * drops it. A path that cannot claim leaves the session to the one that did.
   */
  fun claim(): Boolean = claimed.compareAndSet(false, true)
}

/**
 * The work one live session does while it stays attached: the connection and Bluetooth watches, the
 * tick loop and the recovery steps it asks for. The control flow attaches and ends the session; this
 * feeds the monitor and performs what the recovery policy decides, and a step never ends a recording.
 */
internal class LiveLoop(
    private val clock: Clock,
    private val host: RecordingHost,
    /** Whether [session] is still the one the control holds: only the live session ticks. */
    private val isLive: (LiveSession) -> Boolean,
) {
  /** The jobs that keep [session] going on [scope], from the moment it is attached. */
  fun jobs(scope: CoroutineScope, session: LiveSession): List<Job> =
      buildList {
        // One collector feeds both consumers, so the screen and the notification never disagree.
        add(
            scope.launch {
              session.monitor.state.collect { state ->
                if (!isLive(session)) return@collect
                RecordingRepository.update { it.copy(state = state) }
                host.onState(state)
              }
            }
        )
        add(scope.launch { watchConnection(session) })
        add(scope.launch { tickLoop(session) })
        // A streamed recording is the only one whose link can be lost with the radio.
        if (session.mode == RecordingMode.STREAM) add(scope.launch { watchBluetooth(session) })
      }

  private suspend fun watchConnection(session: LiveSession) {
    // The monitor owns the recorded connect/reconnect/disconnect events; these lines are only for
    // the diagnostics log.
    var everConnected = false
    var wasConnected = false
    session.sensor.connection.collect { state ->
      session.monitor.onLink(state)
      if (state == ConnectionState.CONNECTED) {
        Diagnostics.log(
            if (everConnected) "ble_reconnect" else "ble_connect",
            mapOf("sensor" to session.sensor.name),
        )
        everConnected = true
        wasConnected = true
      } else if (wasConnected) {
        Diagnostics.log("ble_disconnect", mapOf("state" to state.name.lowercase()))
        wasConnected = false
      }
    }
  }

  /** Watches the radio so a recording that lost Bluetooth reconnects as soon as it is back. */
  private suspend fun watchBluetooth(session: LiveSession) {
    BluetoothPower.enabled.collect { enabled ->
      if (enabled == null) return@collect
      for (step in session.monitor.onBluetooth(enabled)) applyRecovery(session, step)
    }
  }

  private suspend fun tickLoop(session: LiveSession) {
    var lastHeartbeat = 0L
    var lastReadinessCheck = 0L
    var readiness = host.readinessSummary()
    while (true) {
      // The recording may have been ended or rebuilt by another path: only the live session ticks.
      if (!isLive(session)) return
      RecordingCost.tick()
      val nowNs = clock.elapsedNs()
      val nowMs = nowNs / NS_PER_MS

      // The monitor derives everything; the control flow only feeds it the sensor's raw values and
      // performs the recovery steps it asks for. A step never ends the recording.
      session.writer.syncIfDue(nowMs)
      session.monitor.onBattery(session.sensor.battery.value)
      for (step in session.monitor.tick(nowNs)) applyRecovery(session, step)

      // One night is longer than one wake-lock timeout, so the lock is taken again before it lapses.
      val lock = session.wakeLock
      if (lock != null && RecordingRecovery.wakeLockNeedsRenewal(nowMs - session.wakeLockSinceMs)) {
        lock.acquire(RecordingRecovery.WAKE_LOCK_MS)
        session.wakeLockSinceMs = nowMs
        RecordingCost.wakeLockAcquired(nowMs)
      }

      if (nowMs - lastHeartbeat >= HEARTBEAT_MS) {
        lastHeartbeat = nowMs
        host.heartbeatMs = clock.phoneNs() / NS_PER_MS
      }
      if (nowMs - lastReadinessCheck >= READINESS_MS) {
        lastReadinessCheck = nowMs
        val now = host.readinessSummary()
        if (now != readiness) session.writer.event("readiness", now)
        readiness = now
      }
      delay(1000)
    }
  }

  /**
   * Performs the step the monitor's recovery policy asked for. A failure is recorded and never ends
   * the recording; the policy asks again on its next step.
   */
  private suspend fun applyRecovery(session: LiveSession, step: RecoveryStep) {
    if (!isLive(session)) return
    val selected = session.writer.meta.streams.map { it.id }.toSet()
    val target = RecoveryTarget(session.sensor, selected, session.fanout)
    try {
      executeRecovery(step, target, clock, session.writer::event)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      session.writer.event("error", "recovery step failed: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private companion object {
    const val NS_PER_MS = 1_000_000L
    const val HEARTBEAT_MS = 15_000L
    const val READINESS_MS = 10_000L
  }
}
