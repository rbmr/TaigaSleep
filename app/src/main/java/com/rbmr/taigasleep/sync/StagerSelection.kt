package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.insights.StagerPreference
import com.rbmr.taigasleep.insights.compareVersions
import com.rbmr.taigasleep.recording.store.SessionConfig

data class StagerChoice(val id: String, val version: String)

/**
 * Pick the best installed stager that the recording can actually feed. The backend still has the
 * final say: it refuses an algorithm or version that no worker offers.
 */
fun selectStager(
    config: SessionConfig,
    algorithms: List<AlgorithmDto>,
    preference: List<String> = StagerPreference,
): StagerChoice {
  for (preferred in preference) {
    selectOne(preferred, config, algorithms)?.let {
      return it
    }
  }
  // baseline is built into the worker and is the safe fallback when the algorithm list is empty,
  // the worker has not reported yet, or nothing reported can run on this recording.
  return StagerChoice("baseline", "1.1.0")
}

/**
 * Pick the latest installed version of each requested stager that this recording can feed. This is
 * the manual counterpart of [selectStager]: unknown and incompatible ids are skipped, and the
 * order of [ids] is preserved.
 */
fun selectStagers(
    config: SessionConfig,
    algorithms: List<AlgorithmDto>,
    ids: List<String>,
): List<StagerChoice> =
    ids.distinct().mapNotNull { selectOne(it, config, algorithms) }

private fun selectOne(
    id: String,
    config: SessionConfig,
    algorithms: List<AlgorithmDto>,
): StagerChoice? {
  val candidate =
      algorithms
          .filter { it.id == id && it.version.isNotBlank() }
          .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
  return candidate?.takeIf { compatible(it, config) }?.let { StagerChoice(it.id, it.version) }
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
