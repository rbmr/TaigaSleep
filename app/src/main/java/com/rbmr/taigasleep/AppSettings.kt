package com.rbmr.taigasleep

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

class AppSettings(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val _themeMode = MutableStateFlow(loadThemeMode())
  val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

  fun setThemeMode(mode: ThemeMode) {
    prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    _themeMode.value = mode
  }

  private fun loadThemeMode(): ThemeMode {
    val value = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
    return runCatching { ThemeMode.valueOf(value) }.getOrDefault(ThemeMode.SYSTEM)
  }

  private companion object {
    const val KEY_THEME_MODE = "theme_mode"
  }
}
