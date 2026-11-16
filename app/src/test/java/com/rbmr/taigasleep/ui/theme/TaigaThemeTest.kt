package com.rbmr.taigasleep.ui.theme

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the theme hands to everything it draws: the font scale of the chosen text size and the
 * reduce-motion flag, including the phone's own animation setting changing while the app runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi", fontScale = 1.3f)
class TaigaThemeTest {
  @get:Rule val compose = createComposeRule()

  private val context: Context = RuntimeEnvironment.getApplication()
  private var textSize by mutableStateOf(TextSize.SYSTEM)
  private var mode by mutableStateOf(AnimationMode.SYSTEM)

  @Test
  fun `System leaves the phone's font scale unchanged`() {
    assertEquals(listOf(1.3f), fontScalesFor(TextSize.SYSTEM))
  }

  @Test
  fun `a chosen text size replaces the phone's font scale`() {
    // The phone draws at 1.3; each choice gives its own scale regardless.
    assertEquals(listOf(0.85f, 1f, 1.15f), fontScalesFor(TextSize.SMALL, TextSize.MEDIUM, TextSize.LARGE))
  }

  @Test
  fun `the theme provides the reduce-motion flag to its content`() {
    // Off stops every animation; On and System run them here because the phone animates at 1.
    assertEquals(
        listOf(true to 0f, false to 1f, false to 1f),
        motionFor(AnimationMode.OFF, AnimationMode.ON, AnimationMode.SYSTEM),
    )
  }

  @Test
  fun `System follows the phone's animator scale while the app runs`() {
    putAnimatorScale(1f)
    var scale = 0f
    compose.setContent {
      scale = rememberMotionScale(AnimationMode.SYSTEM)
    }
    compose.waitForIdle()
    assertEquals("animations are on in the phone", 1f, scale, 0f)

    // The user turns animations off in the phone's settings while the app is open.
    putAnimatorScale(0f)
    shadowOf(Looper.getMainLooper()).idle()
    compose.waitForIdle()
    assertEquals("the change reached the running app", 0f, scale, 0f)
  }

  /** The font scale the theme draws at for each of [choices], as the composition measures them. */
  private fun fontScalesFor(vararg choices: TextSize): List<Float> {
    val seen = mutableListOf<Float>()
    textSize = choices.first()
    compose.setContent {
      TaigaTheme(textSize = textSize) { seen += LocalDensity.current.fontScale }
    }
    compose.waitForIdle()
    choices.drop(1).forEach { choice ->
      compose.runOnIdle { textSize = choice }
      compose.waitForIdle()
    }
    return seen.distinct()
  }

  /** The reduce-motion flag and the scale the theme hands to its content, per [choices]. */
  private fun motionFor(vararg choices: AnimationMode): List<Pair<Boolean, Float>> {
    var seen = false to 0f
    mode = choices.first()
    compose.setContent {
      TaigaTheme(motionScale = mode.motionScale(animatorScale = 1f)) {
        seen = LocalReduceMotion.current to LocalMotionScale.current
      }
    }
    compose.waitForIdle()
    val observed = mutableListOf<Pair<Boolean, Float>>()
    choices.forEach { choice ->
      compose.runOnIdle { mode = choice }
      compose.waitForIdle()
      observed += seen
    }
    return observed
  }

  private fun putAnimatorScale(scale: Float) {
    Settings.Global.putFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        scale,
    )
  }
}
