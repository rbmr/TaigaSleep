package com.rbmr.taigasleep.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * How much contrast the phone asks the app to draw with. Android 14 lets the user raise the
 * contrast of everything on the phone; a phone without that setting is always at [STANDARD].
 */
enum class ContrastLevel {
  /** The palette the app has always drawn with. */
  STANDARD,
  /** The palette with its text and graphics pulled one step further from their backgrounds. */
  MEDIUM,
  /** The palette with its text and graphics pulled as far from their backgrounds as they go. */
  HIGH;

  companion object {
    /**
     * The level for a platform contrast value: 0 is the standard level and 1 the highest, and the
     * platform puts its three levels at 0, 0.5 and 1. A phone that asks for less contrast than the
     * standard level keeps the standard palette, because there is nothing milder to draw.
     */
    fun from(contrast: Float): ContrastLevel =
        when {
          contrast < 0.25f -> STANDARD
          contrast < 0.75f -> MEDIUM
          else -> HIGH
        }
  }
}

/** The value the platform reports for the standard level. */
private const val STANDARD_CONTRAST = 0f

/**
 * The level a phone at platform release [sdkInt] draws at when its contrast setting reads
 * [contrast]. A release older than the one that added the setting has none to read, so it draws at
 * the standard level.
 */
internal fun systemContrastLevel(sdkInt: Int, contrast: Float): ContrastLevel =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ContrastLevel.from(contrast)
    else ContrastLevel.STANDARD

/**
 * The phone's contrast level, kept current while the app runs: a change in the phone's settings
 * reaches the app at once through the platform's own listener, so the palette follows it without a
 * restart.
 */
@Composable
fun rememberContrastLevel(): ContrastLevel {
  val context = LocalContext.current
  // Only a phone that has the setting is observed at all.
  val contrast =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        rememberPlatformContrast(context)
      } else {
        STANDARD_CONTRAST
      }
  return systemContrastLevel(Build.VERSION.SDK_INT, contrast)
}

/** The phone's contrast value, re-read every time the phone reports a change. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun rememberPlatformContrast(context: Context): Float {
  val manager = remember(context) { context.getSystemService(UiModeManager::class.java) }
  var contrast by remember(manager) { mutableFloatStateOf(manager.contrast) }
  DisposableEffect(manager) {
    val listener = UiModeManager.ContrastChangeListener { contrast = it }
    manager.addContrastChangeListener(context.mainExecutor, listener)
    onDispose { manager.removeContrastChangeListener(listener) }
  }
  return contrast
}
