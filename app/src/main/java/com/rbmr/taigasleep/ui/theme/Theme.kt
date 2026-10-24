package com.rbmr.taigasleep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors =
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

private val DarkColors =
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

private val LocalTaigaColors = staticCompositionLocalOf { LightTaigaColors }

/** The colors that have no Material slot: `MaterialTheme.taiga.warn` and friends. */
val MaterialTheme.taiga: TaigaColors
  @Composable @ReadOnlyComposable get() = LocalTaigaColors.current

@Composable
fun TaigaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
      LocalTaigaColors provides if (darkTheme) DarkTaigaColors else LightTaigaColors
  ) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TaigaTypography,
        content = content,
    )
  }
}
