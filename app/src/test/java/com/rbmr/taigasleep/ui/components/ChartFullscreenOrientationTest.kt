package com.rbmr.taigasleep.ui.components

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fullscreen chart turns the activity sideways while it is open. What it turns back to is the
 * activity's own orientation from before, not the screen's current one: an activity that left it
 * unspecified rotates again after the chart closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ChartFullscreenOrientationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `closing the chart restores an unspecified orientation`() {
    openAndClose(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
  }

  @Test
  fun `closing the chart restores a pinned orientation`() {
    openAndClose(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
  }

  /** Opens the chart on an activity that asked for [orientation] and closes it again. */
  private fun openAndClose(orientation: Int) {
    var open by mutableStateOf(false)
    compose.setContent {
      TaigaTheme {
        if (open) ChartFullscreenDialog(title = "Heart rate", onDismiss = { open = false }) {}
      }
    }
    val activity = compose.activity
    activity.requestedOrientation = orientation

    compose.runOnUiThread { open = true }
    compose.waitForIdle()
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)

    compose.runOnUiThread { open = false }
    compose.waitForIdle()
    assertEquals(orientation, activity.requestedOrientation)
  }
}
