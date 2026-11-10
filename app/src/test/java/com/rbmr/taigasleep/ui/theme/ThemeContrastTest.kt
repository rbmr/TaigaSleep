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
 */
class ThemeContrastTest {
  @Test
  fun `the source line is readable on the card in both themes`() {
    for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
      assertReadable("$name: the card", scheme, scheme.surface)
      assertReadable("$name: the sheet", scheme, scheme.surfaceContainerLow)
    }
  }

  private fun assertReadable(where: String, scheme: ColorScheme, background: Color) {
    val ratio = contrastRatio(scheme.onSurfaceVariant, background)
    assertTrue(
        "$where gives %.2f:1 for the source line, below the 4.5:1 minimum".format(ratio),
        ratio >= 4.5,
    )
  }
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
