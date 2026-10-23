package com.rbmr.taigasleep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.ui.TaigaApp
import com.rbmr.taigasleep.ui.theme.TaigaTheme

class MainActivity : ComponentActivity() {
  private val settings by lazy { AppSettings(this) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val themeMode by settings.themeMode.collectAsStateWithLifecycle()
      val darkTheme =
          when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
          }
      val view = LocalView.current
      if (!view.isInEditMode) {
        SideEffect {
          val controller = WindowCompat.getInsetsController(this@MainActivity.window, view)
          controller.isAppearanceLightStatusBars = !darkTheme
          controller.isAppearanceLightNavigationBars = !darkTheme
        }
      }
      TaigaTheme(darkTheme = darkTheme) {
        TaigaApp(
            themeMode = themeMode,
            onThemeModeChange = settings::setThemeMode,
        )
      }
    }
  }
}
