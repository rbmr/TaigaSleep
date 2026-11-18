package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.insights.numberItems
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every number a result carries belongs to one section of the recording screen, and the supporting
 * figures of the heart rate sit with the heart rate they explain.
 */
class RecordingItemsTest {
  private val items =
      listOf(
          number("total_sleep_s", "sleep"),
          number("hr_mean_bpm", "heart"),
          number("hrv_rmssd_ms", "heart"),
          number("hrv_rr_count", "heart_support"),
          number("movement_index", "movement"),
          number("scored_duration_s", "quality"),
          ResultItemDto(kind = "score", id = "data_quality", group = "score"),
      )

  @Test
  fun theSupportingHeartFiguresSitInTheHeartSection() {
    val heart = items.numberItems("heart", "heart_support")

    assertEquals(
        listOf("hr_mean_bpm", "hrv_rmssd_ms", "hrv_rr_count"),
        heart.map { it.id },
    )
  }

  @Test
  fun everyNumberItemBelongsToOneSection() {
    val sections =
        listOf(
            items.numberItems("sleep"),
            items.numberItems("heart", "heart_support"),
            items.numberItems("movement"),
            items.numberItems("quality"),
        )

    val ids = sections.flatten().map { it.id }
    assertEquals(items.filter { it.kind == "number" }.map { it.id }, ids)
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun aSectionTakesOnlyItsOwnGroups() {
    assertEquals(listOf("movement_index"), items.numberItems("movement").map { it.id })
    assertEquals(listOf("scored_duration_s"), items.numberItems("quality").map { it.id })
    assertEquals(emptyList<String>(), items.numberItems("score").map { it.id })
  }

  private fun number(id: String, group: String) =
      ResultItemDto(kind = "number", id = id, label = id, group = group, unit = "%", value = 1.0)
}
