package com.rbmr.taigasleep.ui.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tracker picker: the connected tracker is the first row, the others follow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorPickerSheetTest {
  @get:Rule val compose = createComposeRule()

  private val polar = FoundSensor(SensorKind.POLAR_H10, "S1", "Polar H10", -50)
  private val verity = FoundSensor(SensorKind.POLAR_VERITY, "S2", "Verity Sense", -60)

  private fun show(
      picker: SensorPicker,
      connected: TrackerView?,
      onDisconnect: () -> Unit = {},
  ) {
    compose.setContent {
      TaigaTheme {
        SensorPickerSheet(
            picker = picker,
            connected = connected,
            onSelect = {},
            onScanAgain = {},
            onDisconnect = onDisconnect,
            onDismiss = {},
        )
      }
    }
  }

  @Test
  fun `a connected tracker is its own row with Disconnect and is not listed again`() {
    var disconnected = false
    show(
        picker = SensorPicker(visible = true, found = listOf(polar, verity)),
        connected = TrackerView("S1", "Polar H10", ConnectionState.CONNECTED, 80),
        onDisconnect = { disconnected = true },
    )

    compose.onAllNodesWithText("Polar H10").assertCountEquals(1)
    compose.onNodeWithText("Connected").assertExists()
    compose.onNodeWithText("Verity Sense").assertExists()
    compose.onNodeWithText("Disconnect").performSemanticsAction(SemanticsActions.OnClick)

    assertTrue("the connected tracker's row leaves the tracker", disconnected)
  }

  @Test
  fun `without a connected tracker the sheet lists what was found and offers Scan again`() {
    show(picker = SensorPicker(visible = true, found = listOf(polar)), connected = null)

    compose.onNodeWithText("Polar H10").assertExists()
    compose.onNodeWithText("Scan again").assertExists()
    compose.onNodeWithText("Connected").assertDoesNotExist()
    compose.onNodeWithText("Disconnect").assertDoesNotExist()
  }
}
