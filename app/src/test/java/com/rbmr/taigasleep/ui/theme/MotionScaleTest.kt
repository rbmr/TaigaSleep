package com.rbmr.taigasleep.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The animation scale the app runs at. Compose reads that scale from the coroutine an animation
 * runs in, so the app puts its own choice there: that is what lets On animate on a phone whose own
 * animations are off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class MotionScaleTest {
  @get:Rule val compose = createComposeRule()

  private val context: Context = RuntimeEnvironment.getApplication()
  private val full = Animatable(0f)
  private val stretched = Animatable(0f)

  @Test
  fun `On finishes an animation at its own pace even when the phone has animations off`() {
    putAnimatorScale(0f)
    runBothAnimations()

    assertTrue("the animation went to ${full.value}, not through it", full.value in 0.05f..0.95f)
  }

  @Test
  fun `an animation runs at the scale it is given`() {
    runBothAnimations()

    assertTrue(
        "normal went to ${full.value}, stretched to ${stretched.value}",
        stretched.value < full.value,
    )
    assertTrue("the stretched animation went to ${stretched.value}, so it never started", stretched.value > 0f)
  }

  /** Starts a one-second animation at normal speed and one at twice its duration, on one clock. */
  private fun runBothAnimations() {
    compose.mainClock.autoAdvance = false
    compose.setContent {
      LaunchedEffect(Unit) {
        coroutineScope {
          launch { animated(1f) { full.animateTo(1f, tween(1_000)) } }
          launch { animated(2f) { stretched.animateTo(1f, tween(1_000)) } }
        }
      }
    }
    compose.mainClock.advanceTimeBy(200)
  }

  private fun putAnimatorScale(scale: Float) {
    Settings.Global.putFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        scale,
    )
  }
}
