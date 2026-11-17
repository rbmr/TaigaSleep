package com.rbmr.taigasleep.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withContext

/**
 * True when no motion may run. It is the one flag every animated component of the app reads, and
 * the theme resolves it from the user's choice and the phone's animation setting. Off means the end
 * state of an animation is shown at once, never that the data or the state behind it changes.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The animator duration scale the app animates at: the phone's own scale for System, one for On and
 * zero for Off. Compose reads that scale from the coroutine an animation runs in, not from a
 * composition, so a component runs its animation inside [animated] with this value: that is what
 * makes On animate on a phone whose own animations are off.
 */
val LocalMotionScale = staticCompositionLocalOf { 1f }

/** The scale element Compose finds on the coroutine of an animation. */
private class AppMotionScale(override val scaleFactor: Float) : MotionDurationScale

/** Runs [block] at [scale], whatever the phone's own animator duration scale says. */
suspend fun <T> animated(scale: Float, block: suspend () -> T): T =
    withContext(AppMotionScale(scale)) { block() }

/** The phone's animator duration scale: the setting that makes every system animation instant. */
private fun animatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * The animation scale for [mode], kept current while the app runs. System follows the phone's
 * animator duration scale and a change to it reaches the app at once through a ContentObserver, so
 * turning animations off in the phone's settings stops them without a restart.
 */
@Composable
fun rememberMotionScale(mode: AnimationMode): Float {
  val context = LocalContext.current
  var scale by remember { mutableFloatStateOf(animatorScale(context)) }
  DisposableEffect(context) {
    val resolver = context.contentResolver
    val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
          override fun onChange(selfChange: Boolean) {
            scale = animatorScale(context)
          }
        }
    resolver.registerContentObserver(
        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
        false,
        observer,
    )
    onDispose { resolver.unregisterContentObserver(observer) }
  }
  return mode.motionScale(scale)
}
