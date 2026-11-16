package com.rbmr.taigasleep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// The two schemes are internal so the contrast test can measure the values they ship.
internal val LightColors =
    lightColorScheme(
        primary = Color(0xFF1F6F5C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD5EBE2),
        onPrimaryContainer = Color(0xFF1F6F5C),
        secondary = LightTaigaColors.warn,
        onSecondary = LightTaigaColors.onWarn,
        secondaryContainer = LightTaigaColors.warnContainer,
        onSecondaryContainer = LightTaigaColors.warnInk,
        tertiary = LightTaigaColors.auroraEnd,
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF4F7F3),
        onBackground = Color(0xFF12201B),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF12201B),
        surfaceVariant = Color(0xFFEAF1EC),
        onSurfaceVariant = Color(0xFF52695F),
        outline = LightTaigaColors.strong,
        outlineVariant = Color(0xFFD3DFD8),
        error = LightTaigaColors.bad,
        onError = Color(0xFFFFFFFF),
        errorContainer = LightTaigaColors.badContainer,
        onErrorContainer = LightTaigaColors.bad,
        surfaceContainerLowest = Color(0xFFF4F7F3),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFFFFFFF),
        surfaceContainerHighest = Color(0xFFEAF1EC),
    )

internal val DarkColors =
    darkColorScheme(
        primary = Color(0xFF3DD6B0),
        onPrimary = Color(0xFF06241C),
        primaryContainer = Color(0xFF16342C),
        onPrimaryContainer = Color(0xFF3DD6B0),
        secondary = DarkTaigaColors.warn,
        onSecondary = DarkTaigaColors.onWarn,
        secondaryContainer = DarkTaigaColors.warnContainer,
        onSecondaryContainer = DarkTaigaColors.warnInk,
        tertiary = DarkTaigaColors.auroraEnd,
        onTertiary = TaigaNight,
        background = TaigaNight,
        onBackground = Color(0xFFE6F0EA),
        surface = Color(0xFF111E1A),
        onSurface = Color(0xFFE6F0EA),
        surfaceVariant = Color(0xFF182A24),
        onSurfaceVariant = Color(0xFF93ABA0),
        outline = DarkTaigaColors.strong,
        outlineVariant = Color(0xFF233730),
        error = DarkTaigaColors.bad,
        onError = Color(0xFF2A0F0A),
        errorContainer = DarkTaigaColors.badContainer,
        onErrorContainer = DarkTaigaColors.bad,
        surfaceContainerLowest = TaigaNight,
        surfaceContainerLow = Color(0xFF111E1A),
        surfaceContainer = Color(0xFF111E1A),
        surfaceContainerHigh = Color(0xFF182A24),
        surfaceContainerHighest = Color(0xFF182A24),
    )

internal val LightMediumColors =
    LightColors.atHigherContrast(LightTaigaColors.bear, CONTRAST_MEDIUM_STRENGTH)

internal val LightHighColors =
    LightColors.atHigherContrast(LightTaigaColors.bear, CONTRAST_HIGH_STRENGTH)

internal val DarkMediumColors =
    DarkColors.atHigherContrast(DarkTaigaColors.bear, CONTRAST_MEDIUM_STRENGTH)

internal val DarkHighColors =
    DarkColors.atHigherContrast(DarkTaigaColors.bear, CONTRAST_HIGH_STRENGTH)

/**
 * The same scheme with the inks that are read as text or drawn as controls moved [amount] of the
 * way to [ink]. Fills and backgrounds keep their value, and so do the colours written on a fill, so
 * a button's label stays as strong as it is against it.
 */
private fun ColorScheme.atHigherContrast(ink: Color, amount: Float): ColorScheme =
    copy(
        primary = primary.towards(ink, amount),
        secondary = secondary.towards(ink, amount),
        tertiary = tertiary.towards(ink, amount),
        error = error.towards(ink, amount),
        onSurface = onSurface.towards(ink, amount),
        onBackground = onBackground.towards(ink, amount),
        onSurfaceVariant = onSurfaceVariant.towards(ink, amount),
        onPrimaryContainer = onPrimaryContainer.towards(ink, amount),
        onSecondaryContainer = onSecondaryContainer.towards(ink, amount),
        onErrorContainer = onErrorContainer.towards(ink, amount),
        outline = outline.towards(ink, amount),
    )

/** The colors of one theme at one contrast level. */
@Immutable
internal class TaigaPalette(val scheme: ColorScheme, val colors: TaigaColors)

internal val LightPalette = TaigaPalette(LightColors, LightTaigaColors)
internal val LightMediumPalette = TaigaPalette(LightMediumColors, LightMediumTaigaColors)
internal val LightHighPalette = TaigaPalette(LightHighColors, LightHighTaigaColors)
internal val DarkPalette = TaigaPalette(DarkColors, DarkTaigaColors)
internal val DarkMediumPalette = TaigaPalette(DarkMediumColors, DarkMediumTaigaColors)
internal val DarkHighPalette = TaigaPalette(DarkHighColors, DarkHighTaigaColors)

/** The palette the app draws with on a phone that asks for [contrast] in [darkTheme]. */
internal fun paletteFor(darkTheme: Boolean, contrast: ContrastLevel): TaigaPalette =
    when (contrast) {
      ContrastLevel.STANDARD -> if (darkTheme) DarkPalette else LightPalette
      ContrastLevel.MEDIUM -> if (darkTheme) DarkMediumPalette else LightMediumPalette
      ContrastLevel.HIGH -> if (darkTheme) DarkHighPalette else LightHighPalette
    }

private val LocalTaigaColors = staticCompositionLocalOf { LightTaigaColors }

/** The colors that have no Material slot: `MaterialTheme.taiga.warn` and friends. */
val MaterialTheme.taiga: TaigaColors
  @Composable @ReadOnlyComposable get() = LocalTaigaColors.current

/**
 * The theme everything is drawn with: the palette of [darkTheme] at [contrast], the font scale of
 * [textSize] and the motion the app animates at.
 */
@Composable
fun TaigaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    contrast: ContrastLevel = ContrastLevel.STANDARD,
    textSize: TextSize = TextSize.SYSTEM,
    motionScale: Float = 1f,
    content: @Composable () -> Unit,
) {
  // The one place the app's text size is decided: the density carries the font scale every style
  // and every sp value in the app is measured with.
  val density = LocalDensity.current
  val fontScale = textSize.fontScale(density.fontScale)
  val scaledDensity = remember(density, fontScale) { Density(density.density, fontScale) }
  val palette = remember(darkTheme, contrast) { paletteFor(darkTheme, contrast) }

  CompositionLocalProvider(
      LocalTaigaColors provides palette.colors,
      LocalDensity provides scaledDensity,
      LocalReduceMotion provides (motionScale <= 0f),
      LocalMotionScale provides motionScale,
  ) {
    MaterialTheme(
        colorScheme = palette.scheme,
        typography = TaigaTypography,
        content = content,
    )
  }
}
