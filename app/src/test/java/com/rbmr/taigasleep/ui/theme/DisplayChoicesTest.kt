package com.rbmr.taigasleep.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two display choices other than the theme, resolved: the text size decides the font scale the
 * theme provides, and the animation choice decides whether decorative motion runs.
 */
class DisplayChoicesTest {
  @Test
  fun `System keeps the phone's font scale, whatever it is`() {
    assertEquals(1f, TextSize.SYSTEM.fontScale(1f), 0f)
    assertEquals(1.3f, TextSize.SYSTEM.fontScale(1.3f), 0f)
    assertEquals(2f, TextSize.SYSTEM.fontScale(2f), 0f)
  }

  @Test
  fun `Small, Medium and Large are the same scale whatever the phone uses`() {
    for (phone in listOf(0.85f, 1f, 1.3f, 2f)) {
      assertEquals("Small at phone $phone", 0.85f, TextSize.SMALL.fontScale(phone), 0f)
      assertEquals("Medium at phone $phone", 1f, TextSize.MEDIUM.fontScale(phone), 0f)
      assertEquals("Large at phone $phone", 1.15f, TextSize.LARGE.fontScale(phone), 0f)
    }
  }

  @Test
  fun `System follows the phone's animator scale`() {
    assertFalse(AnimationMode.SYSTEM.reduceMotion(animatorScale = 1f))
    assertTrue(AnimationMode.SYSTEM.reduceMotion(animatorScale = 0f))
  }

  @Test
  fun `On animates even when the phone has animations off`() {
    assertFalse(AnimationMode.ON.reduceMotion(animatorScale = 1f))
    assertFalse(AnimationMode.ON.reduceMotion(animatorScale = 0f))
  }

  @Test
  fun `the app animates at the chosen scale`() {
    assertEquals(0.5f, AnimationMode.SYSTEM.motionScale(animatorScale = 0.5f), 0f)
    assertEquals(1f, AnimationMode.ON.motionScale(animatorScale = 0f), 0f)
    assertEquals(0f, AnimationMode.OFF.motionScale(animatorScale = 1f), 0f)
  }

  @Test
  fun `Off never animates, whatever the phone does`() {
    assertTrue(AnimationMode.OFF.reduceMotion(animatorScale = 1f))
    assertTrue(AnimationMode.OFF.reduceMotion(animatorScale = 0f))
  }
}
