package com.rbmr.taigasleep.ui.components

import androidx.compose.ui.geometry.Offset

/** Where one z is drawn, how large and how visible, in the den's own units. */
internal data class SleepZ(val x: Float, val y: Float, val size: Float, val alpha: Float)

/**
 * The two z's for a point of the loop. One z rises from beside the bear's ear at a steady pace,
 * growing as it fades in and then out; the second runs half a loop behind, so one starts as the
 * other ends. Still, the two stand a quarter and three quarters into their rise.
 */
internal fun sleepZs(clock: Float, animated: Boolean): List<SleepZ> {
  val base = if (animated) clock else 0.25f
  return listOf(base, base + 0.5f).map { phase ->
    val p = ((phase % 1f) + 1f) % 1f
    val alpha =
        when {
          !animated -> Z_STATIC_ALPHA
          p < Z_FADE_IN -> lerp(0f, Z_PEAK_ALPHA, p / Z_FADE_IN)
          p > Z_FADE_OUT -> lerp(Z_PEAK_ALPHA, 0f, (p - Z_FADE_OUT) / (1f - Z_FADE_OUT))
          else -> Z_PEAK_ALPHA
        }
    SleepZ(
        x = ZStart.x + Z_RISE_X * p,
        y = ZStart.y - Z_RISE_Y * p,
        size = lerp(Z_START_SIZE, Z_END_SIZE, p),
        alpha = alpha,
    )
  }
}

internal fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

internal const val Z_FADE_IN = 0.2f
internal const val Z_FADE_OUT = 0.5f
internal const val Z_PEAK_ALPHA = 0.9f
internal val ZStart = Offset(135f, 70f)
internal const val Z_RISE_X = 4f
internal const val Z_RISE_Y = 18f
internal const val Z_START_SIZE = 7f
internal const val Z_END_SIZE = 13f
internal const val Z_STATIC_ALPHA = 0.6f
