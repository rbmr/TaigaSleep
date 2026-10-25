package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.storage.SessionConfig

/** Best-to-worst order used when more than one stager is available. */
val StagerPreference = listOf("wav2sleep", "sleepecg", "baseline")

data class StagerChoice(val id: String, val version: String)

/**
 * Pick the best installed stager that the recording can actually feed. The backend still has the
 * final say: it requires an explicit model and version, and the job waits for a worker that has it.
 */
fun selectStager(config: SessionConfig, algorithms: List<AlgorithmDto>): StagerChoice {
  val byId = algorithms.groupBy { it.id }
  for (preferred in StagerPreference) {
    val candidate =
        byId[preferred]
            ?.filter { it.version.isNotBlank() }
            ?.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    if (candidate != null && compatible(candidate, config)) {
      return StagerChoice(candidate.id, candidate.version)
    }
  }
  // baseline is built into the worker and is the safe fallback when the algorithm list is empty,
  // the worker has not reported yet, or nothing reported can run on this recording.
  return StagerChoice("baseline", "1.0.0")
}

fun compatible(algorithm: AlgorithmDto, config: SessionConfig): Boolean =
    algorithm.needs
        .filter { it.required }
        .all { need ->
          when (need.name) {
            "hr" -> config.hr
            "ecg" -> config.ecg && rateInRange(config.ecgRateHz, need.min_rate_hz, need.max_rate_hz)
            "acc" -> config.acc && rateInRange(config.accRateHz, need.min_rate_hz, need.max_rate_hz)
            else -> false
          }
        }

private fun rateInRange(rate: Int, min: Double?, max: Double?): Boolean {
  val value = rate.toDouble()
  return (min == null || value >= min) && (max == null || value <= max)
}

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
