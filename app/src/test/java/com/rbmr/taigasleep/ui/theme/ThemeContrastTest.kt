package com.rbmr.taigasleep.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source line under a metric is small text, so WCAG asks for at least 4.5:1 against what it sits
 * on. It draws with `onSurfaceVariant` on a card and inside a sheet; both backgrounds are the same
 * value in each theme, and both are measured so a later change to either is caught.
 *
 * The two higher contrast schemes the phone can ask for have to hold the same rule for every piece
 * of text, and 3:1 for a graphic that carries meaning: a stage, a chart line, a control's edge.
 */
class ThemeContrastTest {
  @Test
  fun `the source line is readable on the card in both themes`() {
    for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
      assertReadable("$name: the card", scheme, scheme.surface)
      assertReadable("$name: the sheet", scheme, scheme.surfaceContainerLow)
    }
  }

  @Test
  fun `body and control text is readable in the higher contrast schemes`() {
    for ((name, palette) in higherContrastPalettes()) {
      for ((what, ink, background) in textPairs(palette)) {
        assertAtLeast("$name: $what", ink, background, TEXT_MINIMUM)
      }
    }
  }

  @Test
  fun `stages, charts and control edges stand out in the higher contrast schemes`() {
    for ((name, palette) in higherContrastPalettes()) {
      for ((what, ink, background) in graphicPairs(palette)) {
        assertAtLeast("$name: $what", ink, background, GRAPHIC_MINIMUM)
      }
    }
  }

  private fun assertReadable(where: String, scheme: ColorScheme, background: Color) {
    val ratio = contrastRatio(scheme.onSurfaceVariant, background)
    assertTrue(
        "$where gives %.2f:1 for the source line, below the 4.5:1 minimum".format(ratio),
        ratio >= TEXT_MINIMUM,
    )
  }

  private fun assertAtLeast(where: String, ink: Color, background: Color, minimum: Double) {
    val ratio = contrastRatio(ink, background)
    assertTrue(
        "$where gives %.2f:1, below the %.1f:1 minimum".format(ratio, minimum),
        ratio >= minimum,
    )
  }

  private companion object {
    const val TEXT_MINIMUM = 4.5
    const val GRAPHIC_MINIMUM = 3.0
  }
}

/** The four schemes above the standard one: medium and high for both themes. */
private fun higherContrastPalettes(): List<Pair<String, TaigaPalette>> =
    listOf(
        "light medium" to LightMediumPalette,
        "light high" to LightHighPalette,
        "dark medium" to DarkMediumPalette,
        "dark high" to DarkHighPalette,
    )

/** Every piece of text the app draws, with the colour it sits on. */
private fun textPairs(palette: TaigaPalette): List<Triple<String, Color, Color>> {
  val scheme = palette.scheme
  val colors = palette.colors
  return listOf(
      Triple("body text on a card", scheme.onSurface, scheme.surface),
      Triple("body text on the background", scheme.onSurface, scheme.background),
      Triple("secondary text on a card", scheme.onSurfaceVariant, scheme.surface),
      Triple("secondary text on a filled surface", scheme.onSurfaceVariant, scheme.surfaceVariant),
      Triple("secondary text in a sheet", scheme.onSurfaceVariant, scheme.surfaceContainerLow),
      Triple("the filled button's label", scheme.onPrimary, scheme.primary),
      Triple("the quiet button's label", scheme.onPrimaryContainer, scheme.primaryContainer),
      Triple("the warning button's label", scheme.onSecondaryContainer, scheme.secondaryContainer),
      Triple("the error button's label", scheme.onErrorContainer, scheme.errorContainer),
      Triple("the label on the third accent", scheme.onTertiary, scheme.tertiary),
      Triple("the ok message", colors.ok, scheme.surface),
      Triple("the warning message", colors.warn, scheme.surface),
      Triple("the error message", colors.bad, scheme.surface),
      Triple("the warning message on its banner", colors.warnInk, colors.warnContainer),
      Triple("the label on a warning button", colors.onWarn, colors.warn),
      Triple("the ok mark on its banner", colors.ok, colors.okContainer),
      Triple("the warning mark on its banner", colors.warn, colors.warnContainer),
      Triple("the error mark on its banner", colors.bad, colors.badContainer),
      Triple("body text on a warning banner", scheme.onSurface, colors.warnContainer),
      Triple("secondary text on a warning banner", scheme.onSurfaceVariant, colors.warnContainer),
      Triple("body text on an error banner", scheme.onSurface, colors.badContainer),
  )
}

/** Every graphic that carries meaning, with what it is drawn on. */
private fun graphicPairs(palette: TaigaPalette): List<Triple<String, Color, Color>> {
  val scheme = palette.scheme
  val colors = palette.colors
  val stages =
      listOf(
          "the wake stage" to colors.stageWake,
          "the rem stage" to colors.stageRem,
          "the light stage" to colors.stageLight,
          "the deep stage" to colors.stageDeep,
      )
  val stagePairs =
      stages.flatMap { (what, stage) ->
        listOf(
            Triple("$what in a stage bar", stage, scheme.surfaceVariant),
            Triple("$what in a stage row", stage, scheme.surface),
        )
      }
  return stagePairs +
      listOf(
          Triple("the heart-rate line", colors.chartHr, scheme.surface),
          Triple("the aurora at the top", colors.auroraStart, scheme.background),
          Triple("the aurora at the bottom", colors.auroraEnd, scheme.background),
          Triple("the aurora across the horizon", colors.auroraEnd, scheme.surface),
          Triple("the bear", colors.bear, scheme.background),
          Triple("the bear on the den floor", colors.bear, scheme.surface),
          Triple("the edge of a secondary button", scheme.outline, scheme.surface),
          Triple("the edge of a warning button", colors.warnBorder, scheme.surface),
          Triple("the edge of a warning banner", colors.warnBorder, colors.warnContainer),
          Triple("the edge of a destructive button", colors.badBorder, scheme.surface),
          Triple("the edge of an error banner", colors.badBorder, colors.badContainer),
      )
}

/** The WCAG contrast ratio of two sRGB colors, from 1:1 to 21:1. */
private fun contrastRatio(first: Color, second: Color): Double {
  val brighter = maxOf(relativeLuminance(first), relativeLuminance(second))
  val darker = minOf(relativeLuminance(first), relativeLuminance(second))
  return (brighter + 0.05) / (darker + 0.05)
}

/** WCAG relative luminance: the sRGB channels are linearized and weighted. */
private fun relativeLuminance(color: Color): Double =
    0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

private fun linear(channel: Float): Double {
  val value = channel.toDouble()
  return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}
