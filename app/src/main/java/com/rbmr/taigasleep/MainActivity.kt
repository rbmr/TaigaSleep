package com.rbmr.taigasleep

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rbmr.taigasleep.notifications.resultsOpened
import com.rbmr.taigasleep.ui.TaigaApp
import com.rbmr.taigasleep.ui.theme.AppMotionScale
import com.rbmr.taigasleep.ui.theme.LocalFormats
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import com.rbmr.taigasleep.ui.theme.ThemeMode
import com.rbmr.taigasleep.ui.theme.rememberContrastLevel
import com.rbmr.taigasleep.ui.theme.rememberFormats
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A start from the results notification read its counts; any other start changes nothing.
    resultsOpened(this, intent)
    enableEdgeToEdge()
    // The one animation scale of the app. Compose finds the scale an animation runs at in the
    // coroutine context of the recomposer the composition runs in, so the screen is composed in a
    // window recomposer that carries it: every animation of every screen follows the setting, not
    // the phone's own animation setting.
    val motion = AppMotionScale(this)
    val recomposer =
        window.decorView.createLifecycleAwareWindowRecomposer(
            coroutineContext = motion,
            lifecycle = lifecycle,
        )
    // Nothing on this screen is read before the container has built it: the start-up work happens
    // on the container's scope, and this waits for it instead of blocking the main thread.
    lifecycleScope.launch {
      appContainer.awaitReady()
      // Results arrive live while the app is on screen: the realtime stream is open exactly while the
      // activity is started, and is closed when it is not. Nothing is kept running in the background.
      launch { repeatOnLifecycle(Lifecycle.State.STARTED) { appContainer.liveSync.run() } }
      // The display choices are one store of the app, so this screen and the Display screen that
      // changes them always agree.
      val display = appContainer.displaySettings
      // The chosen animation mode becomes the scale the window runs at, for as long as the window
      // exists. The stored choice is applied before the first frame, so the app never starts at the
      // phone's own scale and then switches.
      motion.select(display.animations.value)
      launch { display.animations.collect(motion::select) }
      setContent(parent = recomposer) {
        val themeMode by display.themeMode.collectAsStateWithLifecycle()
        val textSize by display.textSize.collectAsStateWithLifecycle()
        // The scale is snapshot state, so the theme redraws its still end states when it changes.
        val motionScale by motion.scale
        val darkTheme =
            when (themeMode) {
              ThemeMode.SYSTEM -> isSystemInDarkTheme()
              ThemeMode.LIGHT -> false
              ThemeMode.DARK -> true
            }
        // System follows the phone's animator duration scale while the window is composed.
        DisposableEffect(motion) {
          motion.watch()
          onDispose { motion.stop() }
        }
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
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    resultsOpened(this, intent)
  }
}
