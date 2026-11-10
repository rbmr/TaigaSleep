package com.rbmr.taigasleep.recording

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The wake-up window the user sets before a live recording: two local times of day and which
 * signals may wake them inside it. Both signals off is the same as no window at all.
 *
 * The window is a configuration, not a time: [bounds] resolves it to the wall-clock milliseconds of
 * the night it belongs to, and [WakeBounds] carries that resolved pair from then on, so a recording
 * that is resumed after a restart keeps the same window instead of computing a new one.
 */
data class WakeWindow(
    val startMinute: Int,
    val endMinute: Int,
    val heartRate: Boolean = true,
    val movement: Boolean = false,
) {
  init {
    require(startMinute in MINUTES_OF_DAY) { "start minute $startMinute is not a time of day" }
    require(endMinute in MINUTES_OF_DAY) { "end minute $endMinute is not a time of day" }
    require(startMinute != endMinute) { "a wake-up window needs two different times" }
  }

  /** True when a signal may wake the user before the window ends; otherwise only the end remains. */
  val triggers: Boolean
    get() = heartRate || movement

  /**
   * The window the night is heading for: the one that is open right now (today's, or yesterday's
   * when it crosses midnight), otherwise the next one to open. A window whose end is before its
   * start crosses midnight, and its end always follows its start.
   */
  fun bounds(nowUtcMs: Long, zone: ZoneId = ZoneId.systemDefault()): WakeBounds {
    val date = Instant.ofEpochMilli(nowUtcMs).atZone(zone).toLocalDate()
    val nights = listOf(date.minusDays(1), date, date.plusDays(1)).map { of(it, zone) }
    return nights.firstOrNull { nowUtcMs >= it.startUtcMs && nowUtcMs < it.endUtcMs }
        ?: nights.first { it.startUtcMs > nowUtcMs }
  }

  private fun of(date: LocalDate, zone: ZoneId): WakeBounds {
    val start = at(date, startMinute, zone)
    val end = at(date, endMinute, zone).let { if (it.isAfter(start)) it else it.plusDays(1) }
    return WakeBounds(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
  }

  private fun at(date: LocalDate, minute: Int, zone: ZoneId) =
      date.atTime(minute / 60, minute % 60).atZone(zone)

  companion object {
    /** Every minute of a day, as a time of day counts them. */
    val MINUTES_OF_DAY = 0 until 24 * 60
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
