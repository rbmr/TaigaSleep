package com.rbmr.taigasleep.ui.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rbmr.taigasleep.insights.Formats
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.testFormats
import com.rbmr.taigasleep.ui.theme.TaigaThemeWithFormats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The smart alarm's start time is written and picked in the clock the Display setting chooses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AlarmTimeRowTest {
  @get:Rule val compose = createComposeRule()

  private fun show(formats: Formats) {
    val minute = 6 * 60 + 30
    compose.setContent {
      TaigaThemeWithFormats(formats = formats) {
        TimeRow(label = "Start time", minute = minute, enabled = true, onChange = {})
      }
    }
  }

  @Test
  fun `a twenty four hour setting writes and picks a padded clock`() {
    show(testFormats(timeFormat = TimeFormat.TWENTY_FOUR_HOUR))

    compose.onNodeWithText("06:30").assertExists()
    compose.onNodeWithText("Start time").performClick()
    compose.onNodeWithText("PM").assertDoesNotExist()
  }

  @Test
  fun `a twelve hour setting writes and picks an am or pm time`() {
    show(testFormats(timeFormat = TimeFormat.TWELVE_HOUR))

    compose.onNodeWithText("6:30 AM").assertExists()
    compose.onNodeWithText("Start time").performClick()
    compose.onNodeWithText("PM").assertExists()
  }
}
