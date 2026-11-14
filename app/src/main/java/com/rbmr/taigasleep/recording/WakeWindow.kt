package com.rbmr.taigasleep.recording

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The smart alarm window the user sets before a live recording: a local time of day it opens at, how
 * many minutes it stays open and which signals may wake them inside it. Both signals off is the
 * same as no window at all.
 *
 * The window is a configuration, not a time: [bounds] resolves it to the wall-clock milliseconds of
 * the night it belongs to, and [WakeBounds] carries that resolved pair from then on, so a recording
 * that is resumed after a restart keeps the same window instead of computing a new one.
 */
data class WakeWindow(
    val startMinute: Int,
    val lengthMinutes: Int,
    val heartRate: Boolean = true,
    val movement: Boolean = false,
) {
  init {
    require(startMinute in MINUTES_OF_DAY) { "start minute $startMinute is not a time of day" }
    require(lengthMinutes in LENGTHS) { "window length $lengthMinutes is not supported" }
  }

  /** True when a signal may wake the user before the window ends; otherwise only the end remains. */
  val triggers: Boolean
    get() = heartRate || movement

  /** The time of day the window closes at; it may be on the next day. */
  val endMinute: Int
    get() = (startMinute + lengthMinutes) % MINUTES_PER_DAY

  /**
   * The window the night is heading for: the one that is open right now (today's, or yesterday's
   * when it crosses midnight), otherwise the next one to open.
   */
  fun bounds(nowUtcMs: Long, zone: ZoneId = ZoneId.systemDefault()): WakeBounds {
    val date = Instant.ofEpochMilli(nowUtcMs).atZone(zone).toLocalDate()
    val nights = listOf(date.minusDays(1), date, date.plusDays(1)).map { of(it, zone) }
    return nights.firstOrNull { nowUtcMs >= it.startUtcMs && nowUtcMs < it.endUtcMs }
        ?: nights.first { it.startUtcMs > nowUtcMs }
  }

  private fun of(date: LocalDate, zone: ZoneId): WakeBounds {
    val start = date.atTime(startMinute / 60, startMinute % 60).atZone(zone)
    return WakeBounds(
        start.toInstant().toEpochMilli(),
        start.plusMinutes(lengthMinutes.toLong()).toInstant().toEpochMilli(),
    )
  }

  companion object {
    const val MINUTES_PER_DAY = 24 * 60

    /** Every minute of a day, as a time of day counts them. */
    val MINUTES_OF_DAY = 0 until MINUTES_PER_DAY

    /** The window lengths a window may have, in minutes; windows saved earlier may be long. */
    val LENGTHS = 1 until MINUTES_PER_DAY

    /**
     * The window that opens at [startMinute] and closes at [endMinute] (the next day when that is
     * earlier), the way windows were stored before they had a length. Null when the two times are
     * the same or the span is not a window.
     */
    fun between(
        startMinute: Int,
        endMinute: Int,
        heartRate: Boolean = true,
        movement: Boolean = false,
    ): WakeWindow? {
      if (startMinute !in MINUTES_OF_DAY || endMinute !in MINUTES_OF_DAY) return null
      val length = Math.floorMod(endMinute - startMinute, MINUTES_PER_DAY)
      if (length !in LENGTHS) return null
      return WakeWindow(startMinute, length, heartRate, movement)
    }
  }
}

/** A [WakeWindow] resolved to the wall-clock milliseconds of one night. */
data class WakeBounds(val startUtcMs: Long, val endUtcMs: Long) {
  fun contains(atUtcMs: Long): Boolean = atUtcMs in startUtcMs..endUtcMs
}

/**
 * The wake-up window armed for one recording: the configuration, the night's resolved bounds and
 * the recording it belongs to. It is persisted while the recording runs, so a resumed recording
 * rings for the same night and a recording that already rang does not ring again.
 */
data class ArmedWake(
    val recordingId: String,
    val window: WakeWindow,
    val startUtcMs: Long,
    val endUtcMs: Long,
) {
  val bounds: WakeBounds
    get() = WakeBounds(startUtcMs, endUtcMs)
}
