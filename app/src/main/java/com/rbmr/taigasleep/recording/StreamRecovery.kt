package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.SampleListener
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import kotlinx.coroutines.delay

/** The device work one recovery step asks for. */
enum class RecoveryStepKind {
  /** Nothing can be done until the radio is back; only the reason is recorded. */
  WAIT,

  /** Stop the streams and start them again on the same link. */
  RESTART_STREAMS,

  /** Drop the link, connect again and start the streams on the new link. */
  RECONNECT,
}

/** One recovery step and why it is taken. The reason is what events.jsonl keeps. */
data class RecoveryStep(val kind: RecoveryStepKind, val reason: String)

/**
 * What a live stream does when it stops delivering while the link still says connected.
 *
 * A strap can keep the BLE link up and stop sending notifications. The SDK reports no error then,
 * so the silence is the only evidence. The policy turns that silence into one step per call:
 *
 * - HR delivered nothing for [HR_STALE_MS]: restart the streams.
 * - Still nothing [RECONNECT_AFTER_MS] after that: disconnect and reconnect.
 * - Still nothing: reconnect again after [FIRST_RETRY_MS], [SECOND_RETRY_MS], [THIRD_RETRY_MS] and
 *   then every [LATER_RETRY_MS] for the rest of the recording. It never ends the recording.
 * - Bluetooth comes back on: reconnect at once and start the streams again.
 *
 * An HR sample ends the escalation, so the next stall starts at the restart step with the first
 * retry delay again. A link that is down before any recovery started is not a stalled stream: the
 * sensor reconnects on its own, and the timers of the current stage start again when it is back.
 * Once a recovery is under way, however, it does not wait for the link: a reconnect attempt whose
 * link never comes back is followed by the next attempt on the same backoff ladder, until samples
 * arrive or the recording ends. A reconnect that does not bring samples back therefore continues
 * the ladder instead of starting it over.
 *
 * The policy holds no Android types and is handed the elapsed clock, so it is unit-tested with a
 * fake clock. [RecordingMonitor] reports every HR sample and link change; the service performs the
 * steps [evaluate] and [onBluetooth] return.
 */
class StreamRecoveryPolicy(startedAtMs: Long) {
  private enum class Stage {
    /** Delivering, not yet stalled, or waiting for the link. */
    CLEAR,

    /** The streams were restarted and the reconnect clock is running. */
    RESTARTED,

    /** Reconnecting a stalled stream, waiting for the next attempt. */
    RECONNECTING,
  }

  private var lastSampleAtMs = startedAtMs
  private var connected = false
  private var stage = Stage.CLEAR
  private var restartedAtMs = 0L
  private var nextReconnectAtMs = 0L
  private var reconnects = 0
  private var bluetoothDown = false

  /** An HR sample arrived: whatever was being recovered is delivering again, so start over. */
  fun onHrSample(nowMs: Long) {
    lastSampleAtMs = nowMs
    stage = Stage.CLEAR
    reconnects = 0
    nextReconnectAtMs = 0L
  }

  /**
   * The link came up or went down. A link that is down is left to the sensor's own reconnection; when
   * it is back, the streams start again with it, so the timers of the current stage are re-anchored
   * there instead of firing on time spent without a link.
   */
  fun onLink(connected: Boolean, nowMs: Long) {
    if (connected == this.connected) return
    this.connected = connected
    if (!connected) return
    lastSampleAtMs = nowMs
    when (stage) {
      Stage.CLEAR -> Unit
      // The streams were started with the link: give them a fresh stall window.
      Stage.RESTARTED -> restartedAtMs = nowMs
      // A new link deserves its delay before it is dropped again.
      Stage.RECONNECTING -> nextReconnectAtMs = nowMs + retryDelayMs(reconnects)
    }
  }

  /** The steps a stalled stream needs now, usually none. */
  fun evaluate(nowMs: Long): List<RecoveryStep> {
    if (bluetoothDown) return emptyList()
    return when (stage) {
      Stage.CLEAR -> {
        // Nothing to recover is not a stall: only a link the sensor says is up can be silent.
        if (!connected) return emptyList()
        if (nowMs - lastSampleAtMs < HR_STALE_MS) return emptyList()
        stage = Stage.RESTARTED
        restartedAtMs = nowMs
        listOf(
            RecoveryStep(
                RecoveryStepKind.RESTART_STREAMS,
                "hr stream delivered nothing for ${HR_STALE_MS / 1_000} s while connected; " +
                    "restarted the streams",
            )
        )
      }
      Stage.RESTARTED -> {
        // The restart may itself have lost the link; that does not pause the ladder, or a link that
        // never comes back would leave the recording with no further attempt at all.
        if (nowMs - restartedAtMs < RECONNECT_AFTER_MS) return emptyList()
        stage = Stage.RECONNECTING
        reconnects++
        nextReconnectAtMs = nowMs + retryDelayMs(reconnects)
        listOf(
            RecoveryStep(
                RecoveryStepKind.RECONNECT,
                "hr stream still stalled ${RECONNECT_AFTER_MS / 1_000} s after the restart; " +
                    "reconnecting",
            )
        )
      }
      Stage.RECONNECTING -> {
        if (nowMs < nextReconnectAtMs) return emptyList()
        reconnects++
        val waitMs = retryDelayMs(reconnects)
        nextReconnectAtMs = nowMs + waitMs
        listOf(
            RecoveryStep(
                RecoveryStepKind.RECONNECT,
                "hr stream still stalled; reconnect attempt $reconnects, next in " +
                    "${waitMs / 1_000} s",
            )
        )
      }
    }
  }

  /**
   * The phone's Bluetooth radio went off or came back. While it is off nothing can be reconnected;
   * when it comes back the link and the streams are made again at once.
   */
  fun onBluetooth(enabled: Boolean, nowMs: Long): List<RecoveryStep> {
    if (!enabled) {
      if (bluetoothDown) return emptyList()
      bluetoothDown = true
      return listOf(
          RecoveryStep(
              RecoveryStepKind.WAIT,
              "bluetooth turned off; recovery waits for it to come back",
          )
      )
    }
    if (!bluetoothDown) return emptyList()
    bluetoothDown = false
    lastSampleAtMs = nowMs
    stage = Stage.RESTARTED
    restartedAtMs = nowMs
    reconnects = 0
    nextReconnectAtMs = 0L
    return listOf(
        RecoveryStep(
            RecoveryStepKind.RECONNECT,
            "bluetooth came back on; reconnecting and restarting the streams",
        )
    )
  }

  /** After the first reconnect: 5 s, 15 s, 30 s, then every 60 s for the rest of the recording. */
  private fun retryDelayMs(attempt: Int): Long =
      when {
        attempt <= 1 -> FIRST_RETRY_MS
        attempt == 2 -> SECOND_RETRY_MS
        attempt == 3 -> THIRD_RETRY_MS
        else -> LATER_RETRY_MS
      }

  companion object {
    /** HR silence this long, with the link up, means the stream has stalled. */
    const val HR_STALE_MS = 30_000L

    /** Still silent this long after the streams were restarted: the link itself is the problem. */
    const val RECONNECT_AFTER_MS = 30_000L

    const val FIRST_RETRY_MS = 5_000L
    const val SECOND_RETRY_MS = 15_000L
    const val THIRD_RETRY_MS = 30_000L
    const val LATER_RETRY_MS = 60_000L

    /** A fresh connection needs the old one gone; this pause lets the stack drop it first. */
    const val RECONNECT_PAUSE_MS = 1_000L
  }
}

/**
 * Performs one step on the sensor. The recording service is the only caller in the app, and the
 * simulated-sensor test uses the same function, so a strap and a test cannot drift apart.
 */
suspend fun executeRecovery(
    step: RecoveryStep,
    sensor: SleepSensor,
    config: StreamConfig,
    listener: SampleListener,
    reconnectPauseMs: Long = StreamRecoveryPolicy.RECONNECT_PAUSE_MS,
) {
  when (step.kind) {
    RecoveryStepKind.WAIT -> Unit
    RecoveryStepKind.RESTART_STREAMS -> {
      sensor.stopStreams()
      sensor.startStreams(config, listener)
    }
    RecoveryStepKind.RECONNECT -> {
      sensor.disconnect()
      delay(reconnectPauseMs)
      sensor.connect()
      sensor.startStreams(config, listener)
    }
  }
}
