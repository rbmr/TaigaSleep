package com.rbmr.taigasleep.ui.theme

import android.content.Context
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.insights.Units
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The display choices in one store: the theme, the text size, whether the app animates, the time
 * format and the units. The app container owns the one instance, so the activity that draws the
 * theme and the screen that changes it read and write the same state.
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

  private val _timeFormat = MutableStateFlow(load(KEY_TIME_FORMAT, TimeFormat.SYSTEM))
  val timeFormat: StateFlow<TimeFormat> = _timeFormat.asStateFlow()

  private val _units = MutableStateFlow(load(KEY_UNITS, Units.SYSTEM))
  val units: StateFlow<Units> = _units.asStateFlow()

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

  fun setTimeFormat(format: TimeFormat) {
    prefs.edit().putString(KEY_TIME_FORMAT, format.name).apply()
    _timeFormat.value = format
  }

  fun setUnits(units: Units) {
    prefs.edit().putString(KEY_UNITS, units.name).apply()
    _units.value = units
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
    const val KEY_TIME_FORMAT = "time_format"
    const val KEY_UNITS = "units"
  }
}
