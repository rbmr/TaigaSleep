package com.rbmr.taigasleep.insights

import com.rbmr.taigasleep.backend.AnalysisResultDto

/**
 * The analysis contract that withholds a sleep score when the data quality is too low. A result of
 * this contract or later without a score item has had its score withheld; an older result never
 * had one to show.
 */
const val SLEEP_SCORE_GATE_CONTRACT_VERSION = 5

/** Why a result that finished has no sleep score to show. */
enum class SleepScoreUnavailableReason {
  /** The worker withheld the score because the data quality is below its threshold. */
  NOT_ENOUGH_GOOD_DATA,

  /** The result predates sleep scores and has to be analysed again. */
  OLDER_RESULT,
}

/** What the sleep-score card shows: the number, or why the result has none. */
sealed interface SleepScoreCardState {
  data class Scored(val score: Double) : SleepScoreCardState

  data class Unavailable(val reason: SleepScoreUnavailableReason) : SleepScoreCardState
}

/**
 * The score card's state from one result payload. Absent payloads are not this function's
 * business: a screen without the payload shows that it has no result rather than a reason.
 */
fun sleepScoreCardState(result: AnalysisResultDto): SleepScoreCardState {
  val score = result.items.firstOrNull { it.id == "sleep_quality" }?.value
  if (score != null) return SleepScoreCardState.Scored(score)
  return SleepScoreCardState.Unavailable(
      if (result.contract_version >= SLEEP_SCORE_GATE_CONTRACT_VERSION) {
        SleepScoreUnavailableReason.NOT_ENOUGH_GOOD_DATA
      } else {
        SleepScoreUnavailableReason.OLDER_RESULT
      }
  )
}
