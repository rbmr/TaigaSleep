package com.rbmr.taigasleep.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The three display choices in one store: the theme, the text size and whether the app animates.
 * The app container owns the one instance, so the activity that draws the theme and the screen that
 * changes it read and write the same state.
 *
 * The theme is kept under the key it has always used, so a choice made before this store existed is
 * still applied. Every other choice defaults to following the phone.
 */
class DisplaySettings(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val _themeMode = MutableStateFlow(load(KEY_THEME_MODE, ThemeMode.SYSTEM))
  val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

  private val _textSize = MutableStateFlow(load(KEY_TEXT_SIZE, TextSize.SYSTEM))
  val textSize: StateFlow<TextSize> = _textSize.asStateFlow()

  private val _animations = MutableStateFlow(load(KEY_ANIMATIONS, AnimationMode.SYSTEM))
  val animations: StateFlow<AnimationMode> = _animations.asStateFlow()

  fun setThemeMode(mode: ThemeMode) {
    prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    _themeMode.value = mode
  }

  fun setTextSize(size: TextSize) {
    prefs.edit().putString(KEY_TEXT_SIZE, size.name).apply()
    _textSize.value = size
  }

  fun setAnimations(mode: AnimationMode) {
    prefs.edit().putString(KEY_ANIMATIONS, mode.name).apply()
    _animations.value = mode
  }

  /** The stored choice under [key], or [default] when there is none or it no longer exists. */
  private inline fun <reified T : Enum<T>> load(key: String, default: T): T {
    val stored = prefs.getString(key, null) ?: return default
    return enumValues<T>().firstOrNull { it.name == stored } ?: default
  }

  private companion object {
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_TEXT_SIZE = "text_size"
    const val KEY_ANIMATIONS = "animations"
  }
}
