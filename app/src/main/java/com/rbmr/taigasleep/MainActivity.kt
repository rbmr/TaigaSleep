package com.rbmr.taigasleep

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rbmr.taigasleep.notifications.resultsOpened
import com.rbmr.taigasleep.ui.TaigaApp
import com.rbmr.taigasleep.ui.theme.LocalFormats
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import com.rbmr.taigasleep.ui.theme.ThemeMode
import com.rbmr.taigasleep.ui.theme.rememberContrastLevel
import com.rbmr.taigasleep.ui.theme.rememberFormats
import com.rbmr.taigasleep.ui.theme.rememberMotionScale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A start from the results notification read its counts; any other start changes nothing.
    resultsOpened(this, intent)
    enableEdgeToEdge()
    // Results arrive live while the app is on screen: the realtime stream is open exactly while the
    // activity is started, and is closed when it is not. Nothing is kept running in the background.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) { appContainer.liveSync.run() }
    }
    // The display choices are one store of the app, so this screen and the Display screen that
    // changes them always agree.
    val display = appContainer.displaySettings
    setContent {
      val themeMode by display.themeMode.collectAsStateWithLifecycle()
      val textSize by display.textSize.collectAsStateWithLifecycle()
      val animations by display.animations.collectAsStateWithLifecycle()
      val darkTheme =
          when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
          }
      val motionScale = rememberMotionScale(animations)
      val formats = rememberFormats()
      val contrast = rememberContrastLevel()
      val view = LocalView.current
      if (!view.isInEditMode) {
        SideEffect {
          val controller = WindowCompat.getInsetsController(this@MainActivity.window, view)
          controller.isAppearanceLightStatusBars = !darkTheme
          controller.isAppearanceLightNavigationBars = !darkTheme
        }
      }
      TaigaTheme(
          darkTheme = darkTheme,
          contrast = contrast,
          textSize = textSize,
          motionScale = motionScale,
      ) {
        CompositionLocalProvider(LocalFormats provides formats) { TaigaApp() }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    resultsOpened(this, intent)
  }
}
