package com.rbmr.taigasleep.ui.theme

/**
 * How large the app's text is, whatever the phone's own font size is set to. The choice becomes the
 * font scale of the density the theme provides, so every text style keeps the size it was drawn at
 * and nothing else in the layout has to know about it.
 */
enum class TextSize(private val factor: Float) {
  /** The phone's font size, unchanged, as the app has always followed it. */
  SYSTEM(1f),
  SMALL(0.85f),
  MEDIUM(1f),
  LARGE(1.15f);

  /** The font scale the app draws at, given the phone's own [phoneFontScale]. */
  fun fontScale(phoneFontScale: Float): Float = if (this == SYSTEM) phoneFontScale else factor
}

/** Whether the app animates: the phone's own animation setting, always, or never. */
enum class AnimationMode {
  SYSTEM,
  ON,
  OFF;

  /**
   * The animator duration scale the app animates at, given the phone's [animatorScale] (the
   * animator duration scale of Settings > Developer options). Zero is the phone's way of turning
   * animation off; On is one whatever the phone says, so the app animates even then.
   */
  fun motionScale(animatorScale: Float): Float =
      when (this) {
        SYSTEM -> animatorScale
        ON -> 1f
        OFF -> 0f
      }

  /** Whether decorative motion is off at the phone's [animatorScale]. */
  fun reduceMotion(animatorScale: Float): Boolean = motionScale(animatorScale) <= 0f
}
