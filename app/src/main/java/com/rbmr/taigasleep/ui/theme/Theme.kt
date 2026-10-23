package com.rbmr.taigasleep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = TaigaForest,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDEBDD),
        onPrimaryContainer = TaigaForestDeep,
        secondary = TaigaAmber,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF6E3C8),
        onSecondaryContainer = Color(0xFF3E2A10),
        tertiary = TaigaViolet,
        onTertiary = Color.White,
        background = TaigaMist,
        onBackground = TaigaInk,
        surface = Color.White,
        onSurface = TaigaInk,
        surfaceVariant = Color(0xFFE1EAE4),
        onSurfaceVariant = Color(0xFF44544C),
        outline = Color(0xFF74857C),
        error = Color(0xFFBA1A1A),
    )

private val DarkColors =
    darkColorScheme(
        primary = TaigaAurora,
        onPrimary = TaigaNight,
        primaryContainer = TaigaForestDeep,
        onPrimaryContainer = Color(0xFFCDEBDD),
        secondary = TaigaAmber,
        onSecondary = TaigaNight,
        secondaryContainer = Color(0xFF4A3518),
        onSecondaryContainer = Color(0xFFF6E3C8),
        tertiary = TaigaViolet,
        onTertiary = TaigaNight,
        background = TaigaNight,
        onBackground = TaigaSnow,
        surface = TaigaNightSurface,
        onSurface = TaigaSnow,
        surfaceVariant = TaigaNightSurfaceHigh,
        onSurfaceVariant = TaigaMistDark,
        outline = Color(0xFF6E857A),
        error = Color(0xFFFFB4AB),
    )

@Composable
fun TaigaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
  MaterialTheme(
      colorScheme = if (darkTheme) DarkColors else LightColors,
      typography = TaigaTypography,
      content = content,
  )
}
