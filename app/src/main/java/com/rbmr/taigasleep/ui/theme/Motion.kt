package com.rbmr.taigasleep.ui.theme

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale

/**
 * True when the app shows the end state of its animations at once. It is the theme's resolved
 * animation scale, so it is true only when the app resolves to Off. Off means the end state of an
 * animation is shown at once, never that the data or the state behind it changes.
 *
 * Most animations need nothing from it: they run at the app's scale and finish at once when that
 * scale is zero. A component reads it where the still end state is not the animation's last frame,
 * such as the bear's pose or a spinner that would otherwise stand still.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The one animation scale of the app: the [AnimationMode] the user chose, resolved with the
 * phone's own animator duration scale. System follows the phone, On is one and Off is zero,
 * whatever the phone says.
 *
 * Compose reads the scale an animation runs at from the coroutine context of the composition, not
 * from the composition itself, so this object goes into the context of the window's recomposer.
 * Every Compose animation of the window then follows it without a component opting in: infinite
 * transitions, `animate*AsState`, visibility and crossfades, sheet, navigation and pager
 * transitions, ripples and `Animatable`s that components run themselves.
 *
 * Compose reads the scale when a finite animation starts and on every frame of an infinite one, so
 * a change reaches a running infinite animation at once and a finite one from its next start. An
 * infinite animation that stopped because the scale was zero wakes again when it moves off zero,
 * because the scale is snapshot state that the animation's loop observes.
 */
class AppMotionScale(context: Context) : MotionDurationScale {
  private val appContext = context.applicationContext
  private val current =
      mutableFloatStateOf(AnimationMode.SYSTEM.motionScale(animatorScale(appContext)))
  private var mode = AnimationMode.SYSTEM
  private var watching = false

  /** The scale every animation of the app runs at, for the theme to draw its end states by. */
  val scale: State<Float> = current

  override val scaleFactor: Float get() = current.floatValue

  /** Makes [mode] the app's choice, resolved with the phone's scale as it is now. */
  fun select(mode: AnimationMode) {
    this.mode = mode
    update()
  }

  /**
   * Follows the phone's animator duration scale: a change reaches the running app at once through
   * a ContentObserver, so System follows the phone without a restart. [stop] ends it.
   */
  fun watch() {
    if (watching) return
    watching = true
    resolver().registerContentObserver(
        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
        false,
        phoneObserver,
    )
    update()
  }

  /** Stops following the phone's animator duration scale. */
  fun stop() {
    if (!watching) return
    watching = false
    resolver().unregisterContentObserver(phoneObserver)
  }

  private val phoneObserver =
      object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
          update()
        }
      }

  private fun update() {
    current.floatValue = mode.motionScale(animatorScale(appContext))
  }

  private fun resolver(): ContentResolver = appContext.contentResolver
}

/** The phone's animator duration scale: the setting that makes every system animation instant. */
private fun animatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
