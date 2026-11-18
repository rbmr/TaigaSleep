package com.rbmr.taigasleep.ui.history

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.testFormats
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * History with a year of nights: the rows are items of a lazy list, so a phone draws the rows on
 * screen and never the whole year.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class HistoryLazyTest {
  @get:Rule val rule = createComposeRule()

  private val zone: ZoneId = ZoneId.systemDefault()

  /** The formatter History draws its dates with. */
  private val formats = testFormats(zone = zone)

  private val stages =
      listOf(
          StageSegmentDto(stage = "wake", count = 12),
          StageSegmentDto(stage = "rem", count = 40),
          StageSegmentDto(stage = "light", count = 60),
          StageSegmentDto(stage = "deep", count = 30),
      )

  /** The start of a night [daysAgo] nights ago, at 23:12 local time. */
  private fun nightStart(daysAgo: Long): Long =
      LocalDate.now(zone)
          .minusDays(daysAgo)
          .atTime(23, 12)
          .atZone(zone)
          .toInstant()
          .toEpochMilli()

  private fun night(key: String, daysAgo: Long): RecordingView {
    val start = nightStart(daysAgo)
    val end = start + 8 * 3_600_000L
    val analysis =
        Analysis(
            id = "analysis-$key",
            algorithm = "wav2sleep",
            version = "1.2",
            status = AnalysisStatus.DONE,
            segments = stages,
            sleepScore = 78.0,
        )
    return RecordingView(
        key = key,
        localId = key,
        remoteId = "remote-$key",
        startUtcMs = start,
        endUtcMs = end,
        durationMs = end - start,
        device = "Polar H10",
        status = RecordingStatus.READY,
        progress = 1f,
        failure = null,
        errorDetail = null,
        warning = null,
        analyses = listOf(analysis),
        selectedAnalysisId = analysis.id,
        backup = BackupState(),
        local = null,
        remote = null,
        resultDownloaded = true,
    )
  }

  private val year = (0L until 365L).map { daysAgo -> night("night-$daysAgo", daysAgo) }

  /** The rows History composed: the one button each of them is. */
  private fun composedRows(): Int =
      rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
          .fetchSemanticsNodes()
          .size

  @Test
  fun `a year of nights composes only the rows in the viewport`() {
    showYear()

    val composed = composedRows()

    assertTrue("History composed $composed of ${year.size} rows", composed in 1..20)
    assertTrue(
        "the first row on screen is the newest night",
        rule.onAllNodesWithText(formats.recordingDate(year.first().startUtcMs))
            .fetchSemanticsNodes()
            .isNotEmpty(),
    )
    assertTrue(
        "a night far below the screen is not composed",
        rule.onAllNodesWithText(formats.recordingDate(year.last().startUtcMs))
            .fetchSemanticsNodes()
            .isEmpty(),
    )
  }

  @Test
  fun `scrolling through the year composes the rows that come into view`() {
    showYear()

    rule.onNode(hasScrollAction()).performScrollToIndex(year.lastIndex)
    rule.waitForIdle()

    rule.onNodeWithText(formats.recordingDate(year.last().startUtcMs)).assertExists()
    val composed = composedRows()
    assertTrue("History composed $composed rows after scrolling", composed in 1..20)
  }

  private fun showYear() {
    rule.setContent {
      TaigaTheme {
        HistoryScreen(
            state = HistoryUiState(items = year),
            onOpenRecording = { _, _, _ -> },
            onOpenServer = {},
        )
      }
    }
    rule.waitForIdle()
  }
}
