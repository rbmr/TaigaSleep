package com.rbmr.taigasleep.recording

import android.os.SystemClock
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Records a tracker connect that does not come up. Nothing is written while connecting works; a
 * connect that has stayed pending for [PENDING_AFTER_MS] writes one `connect_pending` line with
 * the phone's view of the strap and the driver's own log, again at most every [REPORT_EVERY_MS],
 * and `connect_done` with the total time when it finally connects.
 *
 * The watch sits where every connect passes — the one tracker the sensor hub owns — so a connect
 * from Home, from the picker and from a recording are all covered by the same one.
 */
internal class ConnectWatch(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val log: (String, Map<String, Any?>) -> Unit = { type, fields ->
      Diagnostics.log(type, fields)
    },
) {
  @Volatile private var job: Job? = null

  fun watch(
      scope: CoroutineScope,
      tracker: Tracker,
      strapView: () -> String?,
      bluetoothOn: () -> Boolean?,
  ) {
    job?.cancel()
    job =
        scope.launch {
          var startedAt: Long? = null
          var reported = false
          tracker.connection.collectLatest { state ->
            when (state) {
              ConnectionState.CONNECTING -> {
                if (startedAt == null) {
                  startedAt = nowMs()
                  reported = false
                }
                val since = startedAt ?: return@collectLatest
                pending(tracker, since, strapView, bluetoothOn) { reported = true }
              }
              ConnectionState.CONNECTED -> {
                val since = startedAt
                startedAt = null
                if (reported && since != null) {
                  log(
                      "connect_done",
                      mapOf(
                          "kind" to tracker.kind.wire,
                          "sensor_id" to tracker.id,
                          "after_ms" to nowMs() - since,
                      ),
                  )
                }
                reported = false
              }
              ConnectionState.DISCONNECTED -> {
                startedAt = null
                reported = false
              }
            }
          }
        }
  }

  fun stop() {
    job?.cancel()
    job = null
  }

  private suspend fun pending(
      tracker: Tracker,
      startedAt: Long,
      strapView: () -> String?,
      bluetoothOn: () -> Boolean?,
      onReport: () -> Unit,
  ) {
    delay(PENDING_AFTER_MS)
    while (true) {
      onReport()
      log(
          "connect_pending",
          mapOf(
              "kind" to tracker.kind.wire,
              "sensor_id" to tracker.id,
              "pending_ms" to nowMs() - startedAt,
              "bluetooth_on" to bluetoothOn(),
              "strap" to strapView(),
              "sdk" to bounded(tracker.logLinesSince(startedAt)),
          ),
      )
      delay(REPORT_EVERY_MS)
    }
  }

  /** The newest of [lines] that fit one diagnostics line; the ring itself may hold more. */
  private fun bounded(lines: List<String>): List<String> {
    var used = 0
    val kept = ArrayDeque<String>()
    for (line in lines.asReversed()) {
      if (used + line.length > MAX_SDK_CHARS) break
      used += line.length
      kept.addFirst(line)
    }
    return kept.toList()
  }

  internal companion object {
    const val PENDING_AFTER_MS = 30_000L
    const val REPORT_EVERY_MS = 5 * 60_000L

    /** How much of the driver's log one `connect_pending` line carries. */
    const val MAX_SDK_CHARS = 4_000
  }
}
