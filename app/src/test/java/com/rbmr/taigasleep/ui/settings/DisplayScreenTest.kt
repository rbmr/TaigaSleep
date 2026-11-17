package com.rbmr.taigasleep.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.insights.Units
import com.rbmr.taigasleep.ui.theme.AnimationMode
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import com.rbmr.taigasleep.ui.theme.TextSize
import com.rbmr.taigasleep.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Display screen: one section per display setting, each choice reported on its own, and a
 * sample line that is drawn in the text size the theme provides.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class DisplayScreenTest {
  @get:Rule val compose = createComposeRule()

  private var change: DisplayChange? = null

  @Test
  fun `the screen shows the theme, the text size and the animations`() {
    compose.setContent { Screen() }

    compose.onNodeWithText("Theme").assertIsDisplayed()
    compose.onNodeWithText("Text size").assertIsDisplayed()
    compose.onNodeWithText("Animations").assertIsDisplayed()
    compose.onNodeWithText("Time format").assertIsDisplayed()
    compose.onNodeWithText("Units").assertIsDisplayed()
    compose.onNodeWithText("This is how text looks.").assertIsDisplayed()
  }

  @Test
  fun `every choice reports only the setting it belongs to`() {
    compose.setContent { Screen() }

    compose.onNodeWithText("Dark").performClick()
    assertEquals(DisplayChange.Theme(ThemeMode.DARK), change)

    compose.onNodeWithText("Large").performClick()
    assertEquals(DisplayChange.Text(TextSize.LARGE), change)

    compose.onNodeWithText("Off").performClick()
    assertEquals(DisplayChange.Motion(AnimationMode.OFF), change)

    compose.onNodeWithText("12-hour").performClick()
    assertEquals(DisplayChange.Time(TimeFormat.TWELVE_HOUR), change)

    compose.onNodeWithText("Imperial").performClick()
    assertEquals(DisplayChange.Measurement(Units.IMPERIAL), change)
  }

  @Test
  fun `the sample line grows with the text size`() {
    var size by mutableStateOf(TextSize.SMALL)
    compose.setContent {
      TaigaTheme(textSize = size) {
        DisplayContent(state = DisplayUiState(textSize = size), onChange = {}, onBack = {})
      }
    }
    val small = sampleHeight()

    compose.runOnIdle { size = TextSize.LARGE }
    val large = sampleHeight()

    assertTrue("the sample stayed $small dp at both sizes", large > small)
  }

  @androidx.compose.runtime.Composable
  private fun Screen() {
    TaigaTheme {
      DisplayContent(state = DisplayUiState(), onChange = { change = it }, onBack = {})
    }
  }

  private fun sampleHeight(): Float {
    compose.waitForIdle()
    val node = compose.onNodeWithText("This is how text looks.").fetchSemanticsNode()
    return with(compose.density) { node.size.height.toDp().value }
  }
}
