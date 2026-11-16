package com.rbmr.taigasleep.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.rbmr.taigasleep.insights.Formats
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.testFormats
import com.rbmr.taigasleep.ui.theme.TaigaThemeWithFormats
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Personal details time pickers are the clock the Display setting chooses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TimeFormatDialogsTest {
  @get:Rule val compose = createComposeRule()

  private fun show(formats: Formats) {
    compose.setContent {
      TaigaThemeWithFormats(formats = formats) {
        TimeOfDayDialog(
            field = PersonalTime.BEDTIME,
            current = LocalTime.of(23, 15),
            formats = formats,
            onSave = {},
            onDismiss = {},
        )
      }
    }
  }

  @Test
  fun `a twelve hour setting gives a picker with an am and a pm`() {
    show(testFormats(timeFormat = TimeFormat.TWELVE_HOUR))

    compose.onNodeWithText("PM").assertExists()
    compose.onNodeWithText("AM").assertExists()
  }

  @Test
  fun `a twenty four hour setting gives a picker without them`() {
    show(testFormats(timeFormat = TimeFormat.TWENTY_FOUR_HOUR))

    compose.onNodeWithText("PM").assertDoesNotExist()
    compose.onNodeWithText("AM").assertDoesNotExist()
  }
}
