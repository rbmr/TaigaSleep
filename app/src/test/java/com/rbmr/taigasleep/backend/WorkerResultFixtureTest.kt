package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.insights.applicableStages
import com.rbmr.taigasleep.insights.formatItemValue
import com.rbmr.taigasleep.insights.metricSpec
import com.rbmr.taigasleep.ui.components.hrSeriesPoints
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One real baseline worker result for the synthetic recording in
 * backend/worker/tests/analysis_fixture.py. It keeps the app's decoding and presentation rules
 * tied to the worker's actual output, not a hand-written approximation.
 */
class WorkerResultFixtureTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun result(): AnalysisResultDto {
    val resource =
        checkNotNull(javaClass.getResource("/analysis/baseline-result.json")) {
          "missing analysis fixture"
        }
    return json.decodeFromString(resource.readText())
  }

  @Test
  fun theFixtureIsTheWorkerContractNotAHandWrittenShape() {
    val result = result()

    assertEquals(6, result.contract_version)
    assertEquals("baseline", result.model)
    assertNotNull(result.provenance)
    assertTrue(result.stages?.count ?: 0 > 0)
  }

  @Test
  fun everySectionTheScreenDrawsHasAnItem() {
    val groups = result().items.filter { it.kind == "number" }.map { it.group }.toSet()

    assertTrue(groups.containsAll(setOf("sleep", "heart", "movement", "quality")))
  }

  @Test
  fun theSleepHeartAndMovementFiguresAreNumberItems() {
    val ids = result().items.filter { it.kind == "number" }.map { it.id }.toSet()

    assertTrue(
        ids.containsAll(
            setOf(
                "total_sleep_s",
                "sleep_efficiency",
                "hr_mean_bpm",
                "hrv_rmssd_ms",
                "movement_index",
                "scored_duration_s",
            )
        )
    )
  }

  @Test
  fun theHeartRateChartComesFromTheSeriesItem() {
    val result = result()
    val series = result.items.single { it.kind == "series" && it.id == "hr" }.series

    assertNotNull(series)
    assertEquals(result.stages?.count, series?.count)
    assertEquals(series?.count, series?.mean_bpm?.size)
  }

  @Test
  fun everyNumberBecomesAGridSpecAndTheSeriesBecomesAPlot() {
    val result = result()
    result.items.filter { it.kind == "number" }.forEach { item ->
      val spec = item.metricSpec()
      assertNotNull("no spec for ${item.id}", spec)
      assertNotNull("no value for ${item.id}", formatItemValue(spec!!.kind, spec.itemValue))
      assertTrue(item.label.isNotBlank())
      assertTrue(item.explanation.isNotBlank())
    }

    val series = result.items.single { it.kind == "series" }.series
    assertTrue(hrSeriesPoints(series).any { it != null })
  }

  @Test
  fun onlyTheStagesTheAlgorithmSupportsAreDrawn() {
    val result = result()
    val stages = applicableStages(result.stages!!.segments, result.capabilities)

    // Wake and sleep are supported, so both are drawn even though this night has no wake time.
    // Deep, light, REM and NREM are declared unsupported and are not drawn.
    assertEquals(listOf("wake", "sleep"), stages)
    assertTrue(stages.none { it in setOf("light", "deep", "rem", "nrem") })
  }

  @Test
  fun figuresTheAlgorithmCannotProduceAreAbsent() {
    val result = result()
    val scores = result.items.filter { it.kind == "score" }.associateBy { it.id }

    // Stage confidence is not a data-quality component at all, and the restorative share needs
    // deep and REM, which this algorithm does not report, so the scores drop the components
    // instead of inventing them.
    assertTrue(scores.getValue("data_quality").components.none { it.id == "mean_stage_confidence" })
    assertTrue(
        scores.getValue("sleep_quality").components.none { it.id == "restorative_sleep_share" }
    )
  }

  @Test
  fun theScoresCarryTheirValuesAndComponents() {
    val scores = result().items.filter { it.kind == "score" }.associateBy { it.id }

    assertEquals(setOf("data_quality", "sleep_quality"), scores.keys)
    scores.values.forEach { score ->
      val value = score.value
      assertNotNull(value)
      assertTrue(value!! in 0.0..100.0)
      assertTrue(score.components.isNotEmpty())
      score.components.forEach { component ->
        assertTrue(component.label.isNotBlank())
        assertTrue(component.value in 0.0..100.0)
        assertTrue(component.weight > 0.0)
      }
    }
  }
}
