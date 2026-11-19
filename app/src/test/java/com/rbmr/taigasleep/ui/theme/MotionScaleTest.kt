package com.rbmr.taigasleep.ui.theme

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
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
 * The one animation scale of the app: the chosen [AnimationMode] resolved with the phone's own
 * animator duration scale, and the [androidx.compose.ui.MotionDurationScale] Compose finds on the
 * coroutine context of the window's recomposer. A running infinite transition is the check that
 * the scale reaches an animation that nothing opts in to.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class MotionScaleTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val motion = AppMotionScale(context)

  @get:Rule val compose = createComposeRule(effectContext = motion)

  @Test
  fun `System follows the phone's animator scale while the app runs`() {
    putAnimatorScale(1f)
    motion.watch()
    try {
      motion.select(AnimationMode.SYSTEM)
      assertEquals("animations are on in the phone", 1f, motion.scaleFactor, 0f)

      // The user turns animations off in the phone's settings while the app is open.
      putAnimatorScale(0f)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("the change reached the running app", 0f, motion.scaleFactor, 0f)
    } finally {
      motion.stop()
    }
  }

  @Test
  fun `On is one and Off is zero whatever the phone says`() {
    putAnimatorScale(0f)
    motion.select(AnimationMode.ON)
    assertEquals(1f, motion.scaleFactor, 0f)

    putAnimatorScale(1f)
    motion.select(AnimationMode.OFF)
    assertEquals(0f, motion.scaleFactor, 0f)
  }

  @Test
  fun `an infinite transition follows every combination of the phone's and the app's setting`() {
    val sample = startTransition()
    for (combination in combinations) {
      putAnimatorScale(combination.phone)
      motion.select(combination.mode)
      val (first, second) = sample()
      val where = "the phone at ${combination.phone} with the app set to ${combination.mode}"

      if (combination.animates) {
        assertTrue("$where stood still at $first", second > first)
      } else {
        assertEquals("$where moved from $first", first, second, 0f)
      }
    }
  }

  /**
   * Composes one infinite transition and returns a sampler: it advances the app's clock and gives
   * the transition's value at two moments [StepMs] apart. The composition's coroutines read [motion]
   * as the window's recomposer context does, so every case runs without a restart.
   */
  private fun startTransition(): () -> Pair<Float, Float> {
    var value = 0f
    compose.mainClock.autoAdvance = false
    compose.setContent {
      value =
          rememberInfiniteTransition(label = "test")
              .animateFloat(
                  initialValue = 0f,
                  targetValue = 1f,
                  animationSpec = infiniteRepeatable(tween(TransitionMs, easing = LinearEasing)),
                  label = "test",
              )
              .value
    }
    return {
      // The first advance applies a new scale to the loop, the second one lets a loop that was
      // stopped at zero run again, before the two values compare its motion.
      repeat(2) {
        compose.mainClock.advanceTimeBy(SettleMs)
        compose.waitForIdle()
      }
      val first = value

      compose.mainClock.advanceTimeBy(StepMs)
      compose.waitForIdle()
      first to value
    }
  }

  /** One row of the setting table: the phone's scale, the app's choice and whether motion runs. */
  private class Combination(val phone: Float, val mode: AnimationMode, val animates: Boolean)

  private fun putAnimatorScale(scale: Float) {
    Settings.Global.putFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        scale,
    )
  }

  private companion object {
    /** One loop of the test transition, long enough that the six cases never wrap around it. */
    const val TransitionMs = 10_000

    /** The clock is advanced this far before the first value is read, so the loop is running. */
    const val SettleMs = 200L

    /** How far the clock moves between the two values. */
    const val StepMs = 200L

    /** System follows the phone; On and Off ignore it. */
    val combinations =
        listOf(
            Combination(phone = 1f, mode = AnimationMode.SYSTEM, animates = true),
            Combination(phone = 0f, mode = AnimationMode.SYSTEM, animates = false),
            Combination(phone = 1f, mode = AnimationMode.ON, animates = true),
            Combination(phone = 0f, mode = AnimationMode.ON, animates = true),
            Combination(phone = 1f, mode = AnimationMode.OFF, animates = false),
            Combination(phone = 0f, mode = AnimationMode.OFF, animates = false),
        )
  }
}
