package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dragging a sheet down by its content. A tall sheet scrolls its content back to its top first and
 * only then follows the finger, so a drag from the middle of the content closes it like a drag by
 * its handle does, and a short sheet closes from anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi", sdk = [34])
class TaigaSheetDragTest {
  @get:Rule val compose = createComposeRule()

  private var dismissed = false

  private fun showSheet(rows: Int) {
    compose.setContent {
      TaigaTheme {
        TaigaSheet(onDismiss = { dismissed = true }) {
          Column { repeat(rows) { Text("Row $it", Modifier.padding(24.dp)) } }
        }
      }
    }
    compose.waitForIdle()
  }

  /** A finger that takes [distance] pixels of the sheet's content downwards, then lets go. */
  private fun SemanticsNodeInteraction.dragDown(distance: Float) {
    performTouchInput {
      down(center)
      repeat(20) {
        moveBy(Offset(0f, distance / 20))
        advanceEventTime(16)
      }
      up()
    }
    compose.waitForIdle()
  }

  @Test
  fun `a downward drag from the middle of a tall sheet's content dismisses it`() {
    showSheet(rows = 40)

    compose.onNodeWithText("Row 20").dragDown(distance = 700f)

    assertTrue("The sheet is dismissed by the drag from its content", dismissed)
  }

  @Test
  fun `a short sheet is dismissed by a drag from its content`() {
    showSheet(rows = 2)

    compose.onNodeWithText("Row 1").dragDown(distance = 400f)

    assertTrue("The short sheet is dismissed by the drag from its content", dismissed)
  }

  @Test
  fun `a drag on scrolled content scrolls it before it moves the sheet`() {
    showSheet(rows = 40)
    compose.onNodeWithText("Row 39").performScrollTo()

    // Shorter than the content still has to scroll, so the sheet keeps still and stays open even
    // though the finger has travelled past the distance that dismisses a sheet.
    compose.onNodeWithText("Row 39").dragDown(distance = 200f)

    assertFalse("The scrollable content takes the drag, not the sheet", dismissed)
  }
}
