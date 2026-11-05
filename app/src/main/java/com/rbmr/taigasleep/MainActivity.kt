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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rbmr.taigasleep.library.LibraryLiveSync
import com.rbmr.taigasleep.ui.TaigaApp
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import com.rbmr.taigasleep.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val settings by lazy { AppSettings(this) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // Results arrive live while the app is on screen: the event stream is open exactly while the
    // activity is started, and is closed when it is not. Nothing is kept running in the background.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) { LibraryLiveSync.of(applicationContext).run() }
    }
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
