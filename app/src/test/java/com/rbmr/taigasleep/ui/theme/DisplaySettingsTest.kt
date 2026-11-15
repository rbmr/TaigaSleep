package com.rbmr.taigasleep.ui.theme

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The one store of the display choices: the defaults follow the phone, a choice survives a restart,
 * and the theme chosen before this store existed is still the one applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplaySettingsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs
    get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  @Test
  fun `a phone that never chose follows the system for all three`() {
    val settings = DisplaySettings(context)

    assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
    assertEquals(TextSize.SYSTEM, settings.textSize.value)
    assertEquals(AnimationMode.SYSTEM, settings.animations.value)
  }

  @Test
  fun `the theme stored before this store existed is still applied`() {
    // The app wrote the theme under this key in the settings file it has always used.
    prefs.edit().putString("theme_mode", ThemeMode.DARK.name).apply()

    assertEquals(ThemeMode.DARK, DisplaySettings(context).themeMode.value)
  }

  @Test
  fun `every choice is written and read back by a new instance`() {
    val settings = DisplaySettings(context)
    settings.setThemeMode(ThemeMode.LIGHT)
    settings.setTextSize(TextSize.LARGE)
    settings.setAnimations(AnimationMode.OFF)

    val restarted = DisplaySettings(context)
    assertEquals(ThemeMode.LIGHT, restarted.themeMode.value)
    assertEquals(TextSize.LARGE, restarted.textSize.value)
    assertEquals(AnimationMode.OFF, restarted.animations.value)
  }

  @Test
  fun `a choice the app publishes is the one its readers see at once`() {
    val settings = DisplaySettings(context)

    settings.setTextSize(TextSize.SMALL)

    assertEquals(TextSize.SMALL, settings.textSize.value)
  }

  @Test
  fun `a stored value this version does not know falls back to System`() {
    prefs.edit().putString("text_size", "HUGE").apply()

    assertEquals(TextSize.SYSTEM, DisplaySettings(context).textSize.value)
  }
}
