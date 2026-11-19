package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.allLabels
import com.rbmr.taigasleep.ui.clippedLabels
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import com.rbmr.taigasleep.ui.theme.taiga
import com.rbmr.taigasleep.ui.wrappedLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The controls that used to have one fixed height keep it at the default font scale and grow with
 * their label at a large one, so nothing moves for people who keep the default text size and nothing
 * is cut off for people who do not.
 *
 * The sizes measured here are the visible ones: the invisible 48 dp touch target of the kit is
 * switched off, so a control is measured as it is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LargeTextComponentsTest {
  @get:Rule val compose = createComposeRule()

  private class Measured {
    var size = IntSize.Zero
  }

  /** Draws [content] and answers the height it was measured at, in dp. */
  private fun heightOf(content: @Composable () -> Unit): Float {
    val measured = Measured()
    compose.setContent {
      TaigaTheme {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
          Box(Modifier.onSizeChanged { measured.size = it }) { content() }
        }
      }
    }
    compose.waitForIdle()
    return with(compose.density) { measured.size.height.toDp().value }
  }

  @Test
  fun `the switch between views is 48 dp tall at the default font scale`() {
    val height = heightOf { Switcher() }

    assertEquals(48f, height, 0.5f)
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  /** The appearance choice has three labels, which have to wrap on a screen this narrow at 2.0. */
  @Config(qualifiers = "w240dp-h891dp-xhdpi", fontScale = 2f)
  @Test
  fun `the switch between views grows with wrapped labels`() {
    val height = heightOf { AppearanceChoice() }

    assertTrue("the switch is $height dp tall", height > 48f)
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  /** An equal third of a 360 dp row is narrower than this label, so the segments share by label. */
  @Config(qualifiers = "w360dp-h891dp-xhdpi")
  @Test
  fun `a long label in a full-width switch stays on one line`() {
    compose.setContent {
      TaigaTheme {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
          SegmentedControl(
              options = listOf("System default", "Metric", "Imperial"),
              selectedIndex = 0,
              onSelect = {},
              modifier = Modifier.fillMaxWidth(),
              fillWidth = true,
          )
        }
      }
    }

    val labels = compose.allLabels()
    assertTrue(
        "the long label was not drawn: $labels",
        labels.containsAll(listOf("System default", "Metric", "Imperial")),
    )
    assertEquals(emptyList<String>(), compose.wrappedLabels())
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  @Test
  fun `a choice chip is 32 dp tall at the default font scale`() {
    val height = heightOf { ChoiceChip(label = "7 days", selected = true, onClick = {}) }

    assertEquals(32f, height, 0.5f)
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  @Config(fontScale = 2f)
  @Test
  fun `a choice chip grows with its label`() {
    val height = heightOf { ChoiceChip(label = "7 days", selected = true, onClick = {}) }

    assertTrue("the chip is $height dp tall", height > 32f)
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  @Test
  fun `the bottom bar is 76 dp tall at the default font scale`() {
    val height = heightOf { BottomBar() }

    assertEquals(76f, height, 0.5f)
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  @Config(fontScale = 3f)
  @Test
  fun `the bottom bar grows rather than cutting its labels off`() {
    val height = heightOf { BottomBar() }

    assertTrue("the bar is $height dp tall", height > 76f)
    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  @Composable
  private fun Switcher() {
    SegmentedControl(
        options = listOf("Recordings", "Trends"),
        selectedIndex = 0,
        onSelect = {},
    )
  }

  @Composable
  private fun AppearanceChoice() {
    SegmentedControl(
        options = listOf("System", "Light", "Dark"),
        selectedIndex = 0,
        onSelect = {},
        fillWidth = true,
    )
  }

  @Composable
  private fun BottomBar() {
    TaigaBottomBar(
        items =
            listOf(
                BottomBarItem("History", Icons.Outlined.History),
                BottomBarItem("Home", Icons.Outlined.Home),
                BottomBarItem("Settings", Icons.Outlined.Tune),
            ),
        selectedIndex = 1,
        onSelect = {},
    )
  }

  @Test
  fun `a label whose height cuts it off is reported`() {
    compose.setContent {
      TaigaTheme {
        Box(Modifier.height(12.dp)) {
          Text("Ready for tonight", style = MaterialTheme.taiga.text.row, maxLines = 1)
        }
      }
    }

    assertEquals(1, compose.clippedLabels().size)
  }

  @Test
  fun `a label that wraps is not reported`() {
    compose.setContent {
      TaigaTheme {
        Box(Modifier.width(90.dp)) { Text("Ready for tonight", style = MaterialTheme.taiga.text.row) }
      }
    }

    assertEquals(emptyList<String>(), compose.clippedLabels())
  }

  @Test
  fun `the charts reserve the room their labels need at every font scale`() {
    // 1.0 is the room the charts kept before, so the default text size changes nothing.
    assertEquals(
        ChartLabelSpace(left = 48.dp, top = 16.dp, bottom = 24.dp),
        chartLabelSpace(large = false, fontScale = 1f),
    )
    assertEquals(
        ChartLabelSpace(left = 64.dp, top = 18.dp, bottom = 28.dp),
        chartLabelSpace(large = true, fontScale = 1f),
    )

    // A large font gets the same multiple of that room instead of stopping at 1.6.
    assertEquals(
        ChartLabelSpace(left = 96.dp, top = 32.dp, bottom = 48.dp),
        chartLabelSpace(large = false, fontScale = 2f),
    )
    assertEquals(
        ChartLabelSpace(left = 96.dp, top = 27.dp, bottom = 42.dp),
        chartLabelSpace(large = true, fontScale = 1.5f),
    )
  }
}
