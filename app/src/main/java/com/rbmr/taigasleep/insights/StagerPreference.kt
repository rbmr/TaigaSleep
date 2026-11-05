package com.rbmr.taigasleep.insights

/**
 * Best-to-worst order used when more than one stager is available. wav2sleep is the default sleep
 * classifier; SleepECG is the best HR-only option; baseline remains the last-resort fallback when
 * no worker has reported yet.
 */
val StagerPreference = listOf("wav2sleep", "sleep-ecg", "baseline")

fun compareVersions(a: String, b: String): Int {
  val left = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
  val right = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
  val size = maxOf(left.size, right.size)
  for (index in 0 until size) {
    val l = left.getOrElse(index) { 0 }
    val r = right.getOrElse(index) { 0 }
    if (l != r) return l.compareTo(r)
  }
  return a.compareTo(b)
}
