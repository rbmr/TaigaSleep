package com.rbmr.taigasleep.insights

/**
 * The one way the app writes a length of time: `7h 26m`, `2h`, `45m` or `46s`. Under a minute it
 * counts seconds, so a short stage or a fresh recording still reads as something. A negative
 * length counts as zero.
 */
fun formatDuration(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  if (totalSeconds < 60) return "${totalSeconds}s"
  val totalMinutes = totalSeconds / 60
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return when {
    hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
    hours > 0 -> "${hours}h"
    else -> "${minutes}m"
  }
}
