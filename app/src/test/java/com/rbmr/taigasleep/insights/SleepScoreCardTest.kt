package com.rbmr.taigasleep.insights

import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.ResultItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The score card's plain state, on the JVM: a number when there is one, the reason when not. */
class SleepScoreCardTest {
  private fun result(contractVersion: Int, items: List<ResultItemDto>) =
      AnalysisResultDto(contract_version = contractVersion, items = items)

  @Test
  fun aResultWithASleepScoreShowsItsNumber() {
    val state =
        sleepScoreCardState(
            result(
                SLEEP_SCORE_GATE_CONTRACT_VERSION,
                listOf(
                    ResultItemDto(kind = "score", id = "data_quality", value = 88.0),
                    ResultItemDto(kind = "score", id = "sleep_quality", value = 82.4),
                ),
            )
        )

    assertEquals(SleepScoreCardState.Scored(82.4), state)
  }

  @Test
  fun aResultWhoseScoreTheWorkerWithheldShowsTheReason() {
    val state =
        sleepScoreCardState(
            result(
                SLEEP_SCORE_GATE_CONTRACT_VERSION,
                listOf(ResultItemDto(kind = "score", id = "data_quality", value = 20.0)),
            )
        )

    assertEquals(
        SleepScoreCardState.Unavailable(SleepScoreUnavailableReason.NOT_ENOUGH_GOOD_DATA),
        state,
    )
  }

  @Test
  fun aResultFromBeforeTheScoreGateSaysItIsOlder() {
    val state = sleepScoreCardState(result(SLEEP_SCORE_GATE_CONTRACT_VERSION - 1, emptyList()))

    assertTrue(state is SleepScoreCardState.Unavailable)
    assertEquals(
        SleepScoreUnavailableReason.OLDER_RESULT,
        (state as SleepScoreCardState.Unavailable).reason,
    )
  }
}
